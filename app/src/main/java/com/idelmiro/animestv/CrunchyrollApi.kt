package com.idelmiro.animestv

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Consulta pública (sem login) ao catálogo do Crunchyroll, só para descobrir
 * o código de um episódio a partir do código da série e do número do episódio.
 */
object CrunchyrollApi {

    data class Episode(val id: String, val number: Int, val slug: String, val title: String)

    private const val BASE = "https://www.crunchyroll.com"
    private const val LIST_URL = "https://raw.githubusercontent.com/idelmiro/animes-tv/data/episodes.json"
    private val USER_AGENTS = listOf(
        "Crunchyroll/3.70.0 Android/12 okhttp/4.12.0",
        "Dalvik/2.1.0 (Linux; U; Android 12; Smart TV)",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    )
    private var ua = USER_AGENTS[0]
    private var directBlocked = false
    private var list: JSONObject? = null
    private var listTime = 0L
    var lastSource = ""
        private set

    private var token: String? = null
    private var tokenExpires = 0L
    private val anonId = UUID.randomUUID().toString()
    private val cache = HashMap<String, Episode>()

    fun seriesIdFromUrl(url: String?): String? =
        url?.let { Regex("/series/([A-Z0-9]+)").find(it)?.groupValues?.get(1) }

    /**
     * Episódio legendado (áudio japonês) de número [number]; se não achar, o mais recente.
     * Tenta direto no Crunchyroll; se a TV for bloqueada, usa a lista gerada no GitHub.
     */
    @Synchronized
    fun findEpisode(seriesId: String, number: Int): Episode {
        cache["$seriesId-$number"]?.let { return it }
        var directError: String? = null
        if (!directBlocked) {
            try {
                return findDirect(seriesId, number).also { lastSource = "Crunchyroll direto"; cache["$seriesId-$number"] = it }
            } catch (e: Exception) {
                directError = e.message
            }
        }
        try {
            return findInList(seriesId, number).also { lastSource = "lista do GitHub"; cache["$seriesId-$number"] = it }
        } catch (e: Exception) {
            throw Exception(
                listOfNotNull(directError?.let { "direto: $it" }, "lista do GitHub: ${e.message}").joinToString("; ")
            )
        }
    }

    private fun loadList(): JSONObject {
        val now = System.currentTimeMillis()
        list?.takeIf { now - listTime < 30 * 60 * 1000 }?.let { return it }
        val conn = URL(LIST_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            val code = conn.responseCode
            if (code == 404) throw Exception("lista não acessível (o repositório no GitHub precisa ser público)")
            if (code != 200) throw Exception("erro $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).also { list = it; listTime = now }
        } finally {
            conn.disconnect()
        }
    }

    /** Arte horizontal (16:9) da série no Crunchyroll, vinda da lista do GitHub. */
    @Synchronized
    fun wideImage(seriesId: String?): String? {
        if (seriesId == null) return null
        return try {
            loadList().optJSONObject("images")?.optString(seriesId)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun findInList(seriesId: String, number: Int): Episode {
        val data = loadList()
        val e = data.optJSONObject("episodes")?.optJSONObject("$seriesId:$number")
            ?: data.optJSONObject("latest")?.optJSONObject(seriesId)
            ?: throw Exception("episódio ainda não está na lista (ela é atualizada a cada 2 horas)")
        return Episode(e.getString("id"), e.optInt("n", number), e.optString("slug"), "")
    }

    private fun findDirect(seriesId: String, number: Int): Episode {
        val seasons = get("/content/v2/cms/series/$seriesId/seasons?locale=pt-BR").getJSONArray("data")
        val japanese = (0 until seasons.length()).map { seasons.getJSONObject(it) }
            .filter { s -> s.optString("audio_locale") == "ja-JP" || s.optJSONArray("audio_locales")?.toList()?.contains("ja-JP") == true }
            .ifEmpty { (0 until seasons.length()).map { seasons.getJSONObject(it) } }
            .sortedBy { it.optInt("season_sequence_number", it.optInt("season_number")) }
        if (japanese.isEmpty()) throw Exception("série sem temporadas no Crunchyroll")

        var latest: Episode? = null
        for (season in japanese.reversed()) {
            val eps = get("/content/v2/cms/seasons/${season.getString("id")}/episodes?locale=pt-BR").getJSONArray("data")
            val items = (0 until eps.length()).map { eps.getJSONObject(it) }
            if (latest == null && items.isNotEmpty()) {
                latest = toEpisode(items.maxByOrNull { it.optDouble("sequence_number", 0.0) }!!)
            }
            val match = items.firstOrNull { it.optInt("episode_number", -1) == number }
            if (match != null) return toEpisode(match).also { cache["$seriesId-$number"] = it }
        }
        return latest ?: throw Exception("nenhum episódio encontrado")
    }

    private fun toEpisode(o: JSONObject): Episode {
        // Prefere a versão com áudio japonês (legendada).
        var id = o.getString("id")
        o.optJSONArray("versions")?.let { v ->
            for (i in 0 until v.length()) {
                val ver = v.getJSONObject(i)
                if (ver.optString("audio_locale") == "ja-JP") id = ver.optString("guid", id)
            }
        }
        return Episode(id, o.optInt("episode_number"), o.optString("slug_title"), o.optString("title"))
    }

    private fun JSONArray.toList() = (0 until length()).map { optString(it) }

    private fun authToken(): String {
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenExpires) return it }
        var last: Exception? = null
        for (candidate in listOf(ua) + USER_AGENTS.filter { it != ua }) {
            try {
                ua = candidate
                return requestToken(now)
            } catch (e: Exception) {
                last = e
            }
        }
        directBlocked = true
        throw last ?: Exception("sem acesso")
    }

    private fun requestToken(now: Long): String {
        val conn = URL("$BASE/auth/v1/token").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("cr_web:".toByteArray(), Base64.NO_WRAP))
            conn.setRequestProperty("ETP-Anonymous-ID", anonId)
            conn.outputStream.use { it.write("grant_type=${URLEncoder.encode("client_id", "UTF-8")}".toByteArray()) }
            if (conn.responseCode != 200) throw Exception("Crunchyroll recusou o acesso (${conn.responseCode})")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val t = json.getString("access_token")
            token = t
            tokenExpires = now + (json.optLong("expires_in", 300) - 30) * 1000
            return t
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String): JSONObject {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Authorization", "Bearer " + authToken())
            val code = conn.responseCode
            if (code == 401) token = null
            if (code != 200) throw Exception("Crunchyroll respondeu com erro $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }
}
