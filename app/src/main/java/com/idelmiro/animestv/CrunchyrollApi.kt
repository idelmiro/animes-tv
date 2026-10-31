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
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    private var token: String? = null
    private var tokenExpires = 0L
    private val anonId = UUID.randomUUID().toString()
    private val cache = HashMap<String, Episode>()

    fun seriesIdFromUrl(url: String?): String? =
        url?.let { Regex("/series/([A-Z0-9]+)").find(it)?.groupValues?.get(1) }

    /** Episódio legendado (áudio japonês) de número [number]; se não achar, o mais recente. */
    @Synchronized
    fun findEpisode(seriesId: String, number: Int): Episode {
        cache["$seriesId-$number"]?.let { return it }
        val seasons = get("/content/v2/cms/series/$seriesId/seasons?locale=pt-BR").getJSONArray("data")
        val japanese = (0 until seasons.length()).map { seasons.getJSONObject(it) }
            .filter { s -> s.optString("audio_locale") == "ja-JP" || s.optJSONArray("audio_locales")?.toList()?.contains("ja-JP") == true }
            .ifEmpty { (0 until seasons.length()).map { seasons.getJSONObject(it) } }
            .sortedBy { it.optInt("season_sequence_number", it.optInt("season_number")) }
        if (japanese.isEmpty()) throw Exception("série sem temporadas no Crunchyroll")

        var latest: Episode? = null
        for (season in japanese.reversed()) {
            val eps = get("/content/v2/cms/seasons/${season.getString("id")}/episodes?locale=pt-BR").getJSONArray("data")
            val list = (0 until eps.length()).map { eps.getJSONObject(it) }
            if (latest == null && list.isNotEmpty()) {
                latest = toEpisode(list.maxByOrNull { it.optDouble("sequence_number", 0.0) }!!)
            }
            val match = list.firstOrNull { it.optInt("episode_number", -1) == number }
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
        val conn = URL("$BASE/auth/v1/token").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", UA)
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
            conn.setRequestProperty("User-Agent", UA)
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
