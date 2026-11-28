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
    val streams: List<Stream> = emptyList(),
    /** Número da temporada, quando se sabe por fora do nome (ex.: a anterior de "Season 2" é a 1). */
    val seasonHint: Int? = null,
    /** Código do trailer no YouTube (do AniList), se houver. */
    val trailer: String? = null,
    /** Capa maior (para o destaque da tela inicial). */
    val coverXl: String? = null
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
    val delayed: Boolean = false,
    /** Na tela de temporadas: o selo no lugar do horário (nota ou "Em breve"). */
    val badge: String? = null
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

    /**
     * true = animes do Crunchyroll também são buscados no Nyaa, como os de outros serviços
     * (para quando o Crunchyroll estiver fora do ar).
     */
    var crViaNyaa: Boolean
        get() = sp.getBoolean("crViaNyaa", false)
        set(v) = sp.edit().putBoolean("crViaNyaa", v).apply()

    /** Pasta base dos downloads (null = memória interna). */
    var downloadPath: String?
        get() = sp.getString("downloadPath", null)
        set(v) = sp.edit().putString("downloadPath", v).apply()

    /**
     * Com pendrive/HD: true = baixa direto no disco (bom para SSD); false = baixa na memória
     * interna e move quando termina (necessário para HD mecânico e pendrive).
     */
    var downloadDirect: Boolean
        get() = sp.getBoolean("downloadDirect", false)
        set(v) = sp.edit().putBoolean("downloadDirect", v).apply()

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
        val anime = parseMedia(media) ?: return null
        return Airing(anime = anime, episode = o.optInt("episode"), airingAt = o.getLong("airingAt"))
    }

    /** Temporada de anime (a do Japão): WINTER = jan–mar, SPRING = abr–jun, SUMMER = jul–set, FALL = out–dez. */
    data class Season(val name: String, val year: Int) {
        private val idx get() = NAMES.indexOf(name)
        val label get() = "${listOf("Inverno", "Primavera", "Verão", "Outono")[idx]} $year"
        val months get() = listOf("jan–mar", "abr–jun", "jul–set", "out–dez")[idx]

        companion object {
            val NAMES = listOf("WINTER", "SPRING", "SUMMER", "FALL")
        }
    }

    /** A temporada de [today] e as anteriores, [n] no total (da mais nova para a mais antiga). */
    fun lastSeasons(today: java.time.LocalDate, n: Int): List<Season> {
        var i = (today.monthValue - 1) / 3
        var y = today.year
        return List(n) {
            Season(Season.NAMES[i], y).also { if (--i < 0) { i = 3; y-- } }
        }
    }

    private val SEASON_QUERY = """
        query (#page: Int, #season: MediaSeason, #year: Int) {
          Page(page: #page, perPage: 50) {
            pageInfo { hasNextPage }
            media(season: #season, seasonYear: #year, type: ANIME, isAdult: false,
                  format_in: [TV, TV_SHORT, ONA], sort: POPULARITY_DESC) {
              id isAdult episodes status averageScore
              nextAiringEpisode { episode }
              title { romaji english }
              synonyms
              coverImage { large }
              bannerImage
              externalLinks { site url type }
              trailer { id site }
            }
          }
        }
    """.trimIndent().replace('#', '$')

    /**
     * Animes de uma temporada, do mais popular para o menos. Cada um vem como um [Airing] em
     * que episode = quantos episódios já saíram (0 = ainda não estreou).
     */
    fun fetchSeason(s: Season): List<Airing> {
        val out = ArrayList<Airing>()
        var page = 1
        while (page <= 4) {
            val vars = JSONObject().put("page", page).put("season", s.name).put("year", s.year)
            val json = post(JSONObject().put("query", SEASON_QUERY).put("variables", vars).toString())
            val pageObj = json.getJSONObject("data").getJSONObject("Page")
            val arr = pageObj.getJSONArray("media")
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val anime = parseMedia(m) ?: continue
                val total = m.optInt("episodes", 0)
                val next = m.optJSONObject("nextAiringEpisode")?.optInt("episode", 0) ?: 0
                val aired = when (m.optString("status")) {
                    "NOT_YET_RELEASED" -> 0
                    "FINISHED" -> total
                    else -> if (next > 0) next - 1 else total
                }
                val score = m.optInt("averageScore", 0)
                val badge = when {
                    aired <= 0 -> "Em breve"
                    score > 0 -> String.format(java.util.Locale("pt", "BR"), "★ %.1f", score / 10.0)
                    else -> ""
                }
                out += Airing(anime, aired, 0L, badge = badge)
            }
            if (!pageObj.getJSONObject("pageInfo").optBoolean("hasNextPage")) break
            page++
            Thread.sleep(400)
        }
        return out
    }

    // ---------- tela inicial e busca ----------

    /** Um anime com o que a tela inicial e a busca mostram. */
    data class Show(
        val anime: Anime,
        /** Episódios que já saíram (0 = ainda não estreou). */
        val aired: Int,
        val total: Int,
        /** Nota de 0 a 100 (0 = sem nota). */
        val score: Int,
        val genres: List<String>,
        val description: String?,
        /** "Outono 2026" etc. */
        val season: String?,
        /** TV, MOVIE, ONA… */
        val format: String?,
        val status: String,
        val nextEpisode: Int,
        /** Quando sai o próximo episódio no Japão (segundos), 0 = não se sabe. */
        val nextAiringAt: Long,
        val startMonth: Int,
        val startDay: Int
    ) {
        fun toAiring(): Airing = Airing(anime, aired, 0L, badge = scoreLabel ?: if (aired <= 0) "Em breve" else "")
        val scoreLabel: String? get() = if (score > 0) String.format(java.util.Locale("pt", "BR"), "★ %.1f", score / 10.0) else null
    }

    private val MEDIA_FIELDS = """
        id isAdult episodes status averageScore format season seasonYear genres
        description(asHtml: false)
        startDate { month day }
        nextAiringEpisode { episode airingAt }
        trailer { id site }
        title { romaji english }
        synonyms
        coverImage { large extraLarge }
        bannerImage
        externalLinks { site url type }
    """

    private fun parseShow(m: JSONObject): Show? {
        val anime = parseMedia(m) ?: return null
        val total = m.optInt("episodes", 0)
        val nextObj = m.optJSONObject("nextAiringEpisode")
        val next = nextObj?.optInt("episode", 0) ?: 0
        val status = m.optString("status")
        val aired = when (status) {
            "NOT_YET_RELEASED" -> 0
            "FINISHED" -> maxOf(total, 1)
            else -> if (next > 0) next - 1 else total
        }
        val genres = m.optJSONArray("genres")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
        val desc = m.str("description")?.replace(Regex("<[^>]+>"), "")?.replace(Regex("\\s+"), " ")?.trim()
        val seasonName = m.str("season")
        val year = m.optInt("seasonYear", 0)
        val season = if (seasonName != null && year > 0 && seasonName in Season.NAMES) Season(seasonName, year).label else null
        val start = m.optJSONObject("startDate")
        return Show(anime, aired, total, m.optInt("averageScore", 0), genres, desc, season, m.str("format"), status,
            next, nextObj?.optLong("airingAt", 0L) ?: 0L, start?.optInt("month", 0) ?: 0, start?.optInt("day", 0) ?: 0)
    }

    private fun page(query: String, vars: JSONObject): JSONArray {
        val json = post(JSONObject().put("query", query).put("variables", vars).toString())
        return json.getJSONObject("data").getJSONObject("Page").getJSONArray("media")
    }

    private fun shows(arr: JSONArray): List<Show> = (0 until arr.length()).mapNotNull { parseShow(arr.getJSONObject(it)) }

    /** Os animes em alta agora no AniList (o destaque da tela inicial). */
    fun fetchTrending(n: Int = 10): List<Show> = shows(page("""
        query (#n: Int) { Page(page: 1, perPage: #n) {
          media(type: ANIME, isAdult: false, sort: TRENDING_DESC) { $MEDIA_FIELDS }
        } }
    """.replace('#', '$'), JSONObject().put("n", n)))

    /** Vários animes de uma vez, pelo código do AniList. */
    fun fetchByIds(ids: Collection<Int>): List<Show> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Show>()
        for (chunk in ids.distinct().chunked(50)) {
            out += shows(page("""
                query (#ids: [Int]) { Page(page: 1, perPage: 50) {
                  media(id_in: #ids, type: ANIME) { $MEDIA_FIELDS }
                } }
            """.replace('#', '$'), JSONObject().put("ids", JSONArray(chunk))))
        }
        return out
    }

    /** Os animes mais populares de uma temporada (com tudo o que a tela inicial mostra). */
    fun fetchSeasonShows(s: Season, n: Int = 20): List<Show> = shows(page("""
        query (#season: MediaSeason, #year: Int, #n: Int) { Page(page: 1, perPage: #n) {
          media(season: #season, seasonYear: #year, type: ANIME, isAdult: false,
                format_in: [TV, TV_SHORT, ONA], sort: POPULARITY_DESC) { $MEDIA_FIELDS }
        } }
    """.replace('#', '$'), JSONObject().put("season", s.name).put("year", s.year).put("n", n)))

    /** Busca pelo nome (japonês ou inglês), do mais parecido para o menos. */
    fun search(text: String): List<Show> = shows(page("""
        query (#q: String) { Page(page: 1, perPage: 30) {
          media(search: #q, type: ANIME, isAdult: false, sort: [SEARCH_MATCH, POPULARITY_DESC]) { $MEDIA_FIELDS }
        } }
    """.replace('#', '$'), JSONObject().put("q", text)))

    /** A temporada seguinte a [s]. */
    fun nextSeason(s: Season): Season {
        val i = Season.NAMES.indexOf(s.name)
        return if (i == 3) Season(Season.NAMES[0], s.year + 1) else Season(Season.NAMES[i + 1], s.year)
    }

    /** Temporada anterior: o anime e quantos episódios ela teve. */
    data class Prequel(val anime: Anime, val episodes: Int)

    private val PREQUEL_QUERY = """
        query (#id: Int) {
          Media(id: #id) {
            relations {
              edges {
                relationType
                node {
                  id type format episodes isAdult
                  title { romaji english }
                  synonyms
                  coverImage { large }
                  bannerImage
                  externalLinks { site url type }
                }
              }
            }
          }
        }
    """.trimIndent().replace('#', '$')

    /**
     * Temporada anterior (série de TV/web) do anime [id], ou null se não houver. Filmes,
     * especiais e OVAs que aparecem como "anterior" são pulados.
     */
    fun fetchPrequel(id: Int): Prequel? {
        val vars = JSONObject().put("id", id)
        val json = post(JSONObject().put("query", PREQUEL_QUERY).put("variables", vars).toString())
        val edges = json.optJSONObject("data")?.optJSONObject("Media")?.optJSONObject("relations")
            ?.optJSONArray("edges") ?: return null
        for (i in 0 until edges.length()) {
            val e = edges.getJSONObject(i)
            if (e.optString("relationType") != "PREQUEL") continue
            val node = e.optJSONObject("node") ?: continue
            if (node.optString("type") != "ANIME") continue
            if (node.optString("format") !in setOf("TV", "TV_SHORT", "ONA")) continue
            val eps = node.optInt("episodes", 0)
            if (eps <= 0) continue
            val anime = parseMedia(node) ?: continue
            return Prequel(anime, eps)
        }
        return null
    }

    private fun parseMedia(media: JSONObject): Anime? {
        if (media.optBoolean("isAdult")) return null
        val t = media.optJSONObject("title")
        val title = t?.str("romaji") ?: t?.str("english") ?: return null
        val cover = media.optJSONObject("coverImage")?.str("large")
        val coverXl = media.optJSONObject("coverImage")?.str("extraLarge")
        val trailer = media.optJSONObject("trailer")?.takeIf { it.optString("site").equals("youtube", true) }?.str("id")
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
        return Anime(media.getInt("id"), title, cover, cr, media.str("bannerImage"), alt.toList(), streams,
            trailer = trailer, coverXl = coverXl)
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
