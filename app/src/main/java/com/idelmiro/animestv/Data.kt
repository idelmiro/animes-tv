package com.idelmiro.animestv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Anime(
    val id: Int,
    val title: String,
    val cover: String?,
    val crunchyrollUrl: String?
)

/** Um episódio na grade. [airingAt] é o horário de exibição no Japão (segundos desde 1970). */
data class Airing(val anime: Anime, val episode: Int, val airingAt: Long) {
    /** Horário estimado em que o episódio legendado sai no Crunchyroll. */
    fun releaseMillis(offsetMinutes: Int): Long = (airingAt + offsetMinutes * 60L) * 1000L
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("animestv", Context.MODE_PRIVATE)

    var favorites: Set<Int>
        get() = (sp.getStringSet("favs", emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = sp.edit().putStringSet("favs", value.map { it.toString() }.toSet()).apply()

    fun isFav(id: Int) = id in favorites

    fun toggleFav(id: Int) {
        favorites = if (isFav(id)) favorites - id else favorites + id
    }

    var onlyFavorites: Boolean
        get() = sp.getBoolean("onlyFavs", false)
        set(v) = sp.edit().putBoolean("onlyFavs", v).apply()

    var onlyCrunchyroll: Boolean
        get() = sp.getBoolean("onlyCr", true)
        set(v) = sp.edit().putBoolean("onlyCr", v).apply()

    var offsetMinutes: Int
        get() = sp.getInt("offset", 60)
        set(v) = sp.edit().putInt("offset", v).apply()

    var openMethod: String?
        get() = sp.getString("openMethod", null)
        set(v) = sp.edit().putString("openMethod", v).apply()

    var episodeMethod: String?
        get() = sp.getString("episodeMethod", null)
        set(v) = sp.edit().putString("episodeMethod", v).apply()

    var homeStatus: String
        get() = sp.getString("homeStatus", "ainda não executado") ?: ""
        set(v) = sp.edit().putString("homeStatus", v).apply()

    var homeIds: Set<String>
        get() = sp.getStringSet("homeIds", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("homeIds", v).apply()
}

/** Consulta a programação no AniList (API pública e gratuita). */
object AniList {
    private const val ENDPOINT = "https://graphql.anilist.co"

    // '#' é trocado por '$' (evita conflito com os templates do Kotlin).
    private val QUERY = """
        query (#page: Int, #start: Int, #end: Int) {
          Page(page: #page, perPage: 50) {
            pageInfo { hasNextPage }
            airingSchedules(airingAt_greater: #start, airingAt_lesser: #end, sort: TIME) {
              episode
              airingAt
              media {
                id
                isAdult
                title { romaji english }
                coverImage { large }
                externalLinks { site url }
              }
            }
          }
        }
    """.trimIndent().replace('#', '$')

    /** Busca todos os episódios exibidos entre [start] e [end] (segundos, horário do Japão). */
    fun fetch(start: Long, end: Long): List<Airing> {
        val out = ArrayList<Airing>()
        var page = 1
        while (page <= 20) {
            val vars = JSONObject().put("page", page).put("start", start).put("end", end)
            val body = JSONObject().put("query", QUERY).put("variables", vars).toString()
            val json = post(body)
            val pageObj = json.getJSONObject("data").getJSONObject("Page")
            val arr = pageObj.getJSONArray("airingSchedules")
            for (i in 0 until arr.length()) parse(arr.getJSONObject(i))?.let { out.add(it) }
            if (!pageObj.getJSONObject("pageInfo").optBoolean("hasNextPage")) break
            page++
            Thread.sleep(400)
        }
        return out
    }

    private fun parse(o: JSONObject): Airing? {
        val media = o.optJSONObject("media") ?: return null
        if (media.optBoolean("isAdult")) return null
        val t = media.optJSONObject("title")
        val title = t?.str("romaji") ?: t?.str("english") ?: return null
        val cover = media.optJSONObject("coverImage")?.str("large")
        var cr: String? = null
        val links: JSONArray? = media.optJSONArray("externalLinks")
        if (links != null) {
            for (i in 0 until links.length()) {
                val l = links.getJSONObject(i)
                if (l.str("site").equals("Crunchyroll", ignoreCase = true)) {
                    cr = l.str("url")
                    break
                }
            }
        }
        return Airing(
            anime = Anime(media.getInt("id"), title, cover, cr),
            episode = o.optInt("episode"),
            airingAt = o.getLong("airingAt")
        )
    }

    private fun post(body: String): JSONObject {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code == 429) throw Exception("muitas consultas seguidas, espere um minuto")
            if (code !in 200..299) throw Exception("AniList respondeu com erro $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}

fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
