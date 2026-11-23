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
    val crunchyrollUrl: String?,
    val banner: String? = null,
    /** Outros nomes do anime (inglês, sinônimos), usados na busca do Nyaa. */
    val altTitles: List<String> = emptyList(),
    /** Links de streaming que não são do Crunchyroll (Netflix, Disney+...). */
    val streams: List<Stream> = emptyList()
) {
    /** Nome curto do serviço para o selo do card. */
    val serviceLabel: String?
        get() = if (crunchyrollUrl != null) null else streams.firstOrNull()?.site ?: "Outro"
}

data class Stream(val site: String, val url: String)

/** Um episódio na grade. [airingAt] é o horário de exibição no Japão (segundos desde 1970). */
data class Airing(
    val anime: Anime,
    val episode: Int,
    val airingAt: Long,
    /** true = anime fora do Crunchyroll, mostrado uma semana depois da exibição real. */
    val delayed: Boolean = false
) {
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

    // Chave nova: a partir desta versão o padrão é mostrar todos os serviços.
    var onlyCrunchyroll: Boolean
        get() = sp.getBoolean("onlyCr2", false)
        set(v) = sp.edit().putBoolean("onlyCr2", v).apply()

    /** true = abrir os episódios do Nyaa no Amnis em vez do player do próprio app. */
    var useAmnis: Boolean
        get() = sp.getBoolean("useAmnis", false)
        set(v) = sp.edit().putBoolean("useAmnis", v).apply()

    /**
     * Modo de vídeo do player: 0 = decodificação de vídeo direta pelo hardware;
     * 1 = hardware com cópia (mais compatível); 2 = só software (mais pesado, mas sem
     * depender do decodificador da TV); 3 = leve: o vídeo vai direto do decodificador para a
     * tela (sem passar pela placa de vídeo) e a legenda é mostrada como texto simples.
     */
    var videoMode: Int
        get() = sp.getInt("videoMode2", 0)
        set(v) = sp.edit().putInt("videoMode2", v.coerceIn(0, 3)).apply()

    /** Pasta base dos downloads (null = memória interna). */
    var downloadPath: String?
        get() = sp.getString("downloadPath", null)
        set(v) = sp.edit().putString("downloadPath", v).apply()

    /** Limite total dos downloads, em GB. */
    var downloadCapGb: Int
        get() = sp.getInt("downloadCapGb", 1)
        set(v) = sp.edit().putInt("downloadCapGb", v).apply()

    var offsetMinutes: Int
        get() = sp.getInt("offset", 60)
        set(v) = sp.edit().putInt("offset", v).apply()

    var openMethod: String?
        get() = sp.getString("openMethod", null)
        set(v) = sp.edit().putString("openMethod", v).apply()

    var episodeMethod: String?
        get() = sp.getString("episodeMethod", null)
        set(v) = sp.edit().putString("episodeMethod", v).apply()

    var pendingTest: String?
        get() = sp.getString("pendingTest", null)
        set(v) = sp.edit().putString("pendingTest", v).commit().let { }

    var homeStatus: String
        get() = sp.getString("homeStatus", "ainda não executado") ?: ""
        set(v) = sp.edit().putString("homeStatus", v).apply()

    var channelId: Long
        get() = sp.getLong("channelId", -1L)
        set(v) = sp.edit().putLong("channelId", v).apply()

    var programIds: Set<String>
        get() = sp.getStringSet("programIds", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("programIds", v).apply()

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
                synonyms
                coverImage { large }
                bannerImage
                externalLinks { site url type }
              }
            }
          }
        }
    """.trimIndent().replace('#', '$')

    private const val WEEK = 7 * 86400L

    /**
     * Programação para a grade, entre [start] e [end]: animes do Crunchyroll em tempo real;
     * os de outros serviços com uma semana de atraso (cada episódio aparece 7 dias depois
     * da exibição no Japão), para dar tempo de saírem as legendas dos fansubs.
     */
    fun fetchSchedule(start: Long, end: Long): List<Airing> =
        fetch(start - WEEK, end).mapNotNull { a ->
            when {
                a.anime.crunchyrollUrl != null -> a.takeIf { it.airingAt > start }
                a.airingAt < end - WEEK -> a.copy(airingAt = a.airingAt + WEEK, delayed = true)
                else -> null
            }
        }

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
        val streams = ArrayList<Stream>()
        val links: JSONArray? = media.optJSONArray("externalLinks")
        if (links != null) {
            for (i in 0 until links.length()) {
                val l = links.getJSONObject(i)
                val site = l.str("site") ?: continue
                val url = l.str("url") ?: continue
                if (site.equals("Crunchyroll", ignoreCase = true)) {
                    if (cr == null) cr = url
                } else if (l.str("type").equals("STREAMING", ignoreCase = true)) {
                    streams.add(Stream(site, url))
                }
            }
        }
        val alt = LinkedHashSet<String>()
        t?.str("english")?.let { alt.add(it) }
        media.optJSONArray("synonyms")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { alt.add(it) }
        }
        alt.remove(title)
        return Airing(
            anime = Anime(media.getInt("id"), title, cover, cr, media.str("bannerImage"), alt.toList(), streams),
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
