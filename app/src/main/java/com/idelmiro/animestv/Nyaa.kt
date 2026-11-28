package com.idelmiro.animestv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Busca no Nyaa (feed RSS, sem login) o episódio de animes que não estão no Crunchyroll.
 * Por padrão, legendado em PT-BR: categoria "Anime - Non-English-translated" (1_3).
 * Se não houver, dá para buscar dublado em inglês: categoria "Anime - English-translated"
 * (1_2). Sempre ordenado por seeds; o idioma é confirmado pelo título ou, se não houver,
 * pela descrição ou pela lista de arquivos da página do torrent.
 */
object Nyaa {
    private const val BASE = "https://nyaa.si/"
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AnimesTV"

    /** Quantas páginas de descrição ler por busca (cada uma é uma consulta a mais ao site). */
    private const val MAX_DESCRIPTION_CHECKS = 6
    private const val PAUSE_MS = 1200L

    private val TRACKERS = listOf(
        "http://nyaa.tracker.wf:7777/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
    )

    // ---------- idioma ----------
    //
    // As descrições variam muito: "Portuguese (Brazilian)", "NF_Portuguese(Brazil)", "PT(BR)",
    // "pt-BR", "Legendado"… Português de Portugal aparece como "Portuguese (European)",
    // "PT(EU)", "pt-PT"; e às vezes só "Portuguese", sem dizer qual.

    /** Português do Brasil. */
    private val BR = Regex(
        """(?<![a-z])(?:pt|por)\W{0,2}br(?![a-z])|(?<![a-z])pob(?![a-z])|brazil|brasil|legendado""",
        RegexOption.IGNORE_CASE
    )
    /** Português de Portugal. */
    private val PT_EU = Regex(
        """(?<![a-z])pt\W{0,2}(?:pt|eu)(?![a-z])|portugu\w*\W{0,8}(?:nf_portuguese\W{0,3})?(?:european|europeu|portugal)|european\W{0,3}portugu|de portugal""",
        RegexOption.IGNORE_CASE
    )
    /** Português sem dizer qual. */
    private val PT_ANY = Regex("""portugu(?:ese|[eê]s)(?![a-z])|\[pt]""", RegexOption.IGNORE_CASE)

    /** Lançamentos com várias legendas (costumam ficar na categoria em inglês e trazer PT-BR). */
    private val MULTI = Regex("""multi[-_ .]?subs?|multiple[-_ .]subtitles?|multi(?![a-z])""", RegexOption.IGNORE_CASE)
    private const val MAX_MULTI_CHECKS = 4
    private const val PTBR_QUERY = "(pt-br|ptbr|legendado|portuguese|portugues)"

    private val DUB = Regex(
        """(?<![a-z])(?:eng(?:lish)?[-_ .]?dub(?:bed)?|dub(?:bed)?|dual[-_ .]?audio|multi[-_ .]?audio|english[-_ .]audio)(?![a-z])""",
        RegexOption.IGNORE_CASE
    )
    /** Na descrição: também "Audio: English…" / "English audio". */
    private val DUB_DESC = Regex(
        DUB.pattern + """|audio[^\n<]{0,40}english|english[-_ .]?(?:dub|audio)""",
        RegexOption.IGNORE_CASE
    )
    private const val DUB_QUERY = "(dub|dubbed|dual|multi-audio|eng-dub)"

    /** O que foi encontrado no texto: o idioma pedido ou uma opção secundária (com aviso). */
    internal class Found(val secondary: Boolean, val note: String)

    private val FOUND_MAIN = Found(false, "")
    private val FOUND_EU = Found(true, "português de Portugal")
    private val FOUND_UNKNOWN = Found(true, "português (não diz se é do Brasil)")

    /** O que procurar: legenda em português ou dublagem em inglês. */
    enum class Lang(
        internal val category: String,
        internal val query: String,
        /** "com legenda PT-BR" / "dublado em inglês" */
        val label: String,
        /** Curto, para a lista de resultados. */
        val short: String
    ) {
        PTBR("1_3", PTBR_QUERY, "com legenda PT-BR", "PT-BR"),
        EN_DUB("1_2", DUB_QUERY, "dublado em inglês", "Dublado");

        internal fun classify(text: String, page: Boolean): Found? = when (this) {
            PTBR -> when {
                BR.containsMatchIn(text) -> FOUND_MAIN
                PT_EU.containsMatchIn(text) -> FOUND_EU
                PT_ANY.containsMatchIn(text) -> FOUND_UNKNOWN
                else -> null
            }
            EN_DUB -> if ((if (page) DUB_DESC else DUB).containsMatchIn(text)) FOUND_MAIN else null
        }
    }

    data class Result(
        val title: String,
        val seeders: Int,
        val size: String,
        val infoHash: String,
        val pageUrl: String,
        /** Onde o idioma foi confirmado: "título", "descrição" ou "arquivos". */
        val via: String,
        /** true quando é um pacote com vários episódios (o Amnis pede para escolher o arquivo). */
        val batch: Boolean,
        /** Opção secundária: português de Portugal (ou sem dizer qual), não do Brasil. */
        val secondary: Boolean = false,
        /** Qual é a opção secundária ("português de Portugal"…). */
        val note: String = ""
    ) {
        val magnet: String
            get() = "magnet:?xt=urn:btih:$infoHash&dn=${Uri.encode(title)}" +
                TRACKERS.joinToString("") { "&tr=${Uri.encode(it)}" }
    }

    private class Item(val title: String, val seeders: Int, val size: String, val infoHash: String, val pageUrl: String)

    /** Uma busca por nome; [season] > 1 quando o nome é o "nome-base" (sem "Season 2"). */
    private data class Query(val text: String, val season: Int?)

    private val SEASON_NAME = Regex(
        """^(.*?)\s+(?:Season\s*(\d+)|(\d+)(?:st|nd|rd|th)\s+Season|S(\d+))$""", RegexOption.IGNORE_CASE
    )

    /**
     * Nomes para buscar: o principal, o nome-base da temporada (ex.: "Ao no Hako Season 2" →
     * "Ao no Hako", aceitando só títulos que digam a temporada 2) e os nomes alternativos.
     */
    private fun queries(names: List<String>): List<Query> {
        val clean = names.map { cleanName(it) }.filter { it.length >= 3 && isLatin(it) }.distinct()
        val out = ArrayList<Query>()
        clean.firstOrNull()?.let { out += Query(it, null) }
        for (n in clean) {
            val m = SEASON_NAME.find(n) ?: continue
            val season = (2..4).firstNotNullOfOrNull { m.groupValues[it].toIntOrNull() } ?: continue
            val base = m.groupValues[1].trim()
            if (season > 1 && base.length >= 3) {
                out += Query(base, season)
                break
            }
        }
        clean.drop(1).forEach { out += Query(it, null) }
        return out.distinctBy { it.text.lowercase() }.take(3)
    }

    /** Temporada que o nome indica ("Season 2", "2nd Season", "S2"), ou null. */
    fun seasonFromNames(names: List<String>): Int? = names.firstNotNullOfOrNull { n ->
        SEASON_NAME.find(cleanName(n))?.let { m -> (2..4).firstNotNullOfOrNull { m.groupValues[it].toIntOrNull() } }
    }

    private val STATED_SEASON = Regex(
        """(?<![a-z0-9])S(\d{1,2})E\d|season\W{0,2}(\d{1,2})(?![0-9])|(?<![0-9])(\d)(?:st|nd|rd|th)\s+season""",
        RegexOption.IGNORE_CASE
    )

    /** Temporada que o título do lançamento diz ser, ou null. */
    private fun statedSeason(title: String): Int? =
        STATED_SEASON.find(title)?.let { m -> (1..3).firstNotNullOfOrNull { m.groupValues[it].toIntOrNull() } }

    /** O título diz que é desta temporada? ("S02E07", "Season 2", "2nd Season", "(Season 02)") */
    private fun hasSeason(title: String, season: Int): Boolean = Regex(
        """(?<![a-z0-9])S0*$season(?:\s?E\d|(?![0-9]))|season\W{0,2}0*$season(?![0-9])|(?<![0-9])$season(?:st|nd|rd|th)\s+season""",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(title)

    /**
     * Procura o [episode] do anime pelos [names] (o primeiro é o título principal).
     * Roda em thread de fundo (faz várias consultas de rede).
     */
    fun search(names: List<String>, episode: Int, lang: Lang = Lang.PTBR, season: Int? = null,
               progress: (String) -> Unit = {}): List<Result> {
        val queries = queries(names)
        // Temporada esperada: lançamentos que dizem ser de outra temporada são descartados.
        val expected = season ?: seasonFromNames(names)
        if (queries.isEmpty()) throw Exception("o anime não tem um nome em letras latinas para buscar")
        val found = LinkedHashMap<String, Result>()
        var lastError: Exception? = null
        var checks = 0

        fun mainCount() = found.values.count { !it.secondary && !it.batch }

        fun status(step: String) {
            val main = found.values.count { !it.secondary }
            val other = found.size - main
            val sb = StringBuilder()
            sb.append(if (main == 0) "Nenhum lançamento ${lang.label} confirmado ainda." else "$main lançamento(s) ${lang.label} encontrado(s).")
            if (other > 0) sb.append("\n$other em português de Portugal (ou sem dizer qual), como opção secundária.")
            progress("$step\n\n$sb")
        }

        /** Guarda o resultado; devolve true se é o idioma pedido (não secundário). */
        fun keep(it: Item, batch: Boolean, f: Found?, via: String): Boolean {
            f ?: return false
            val prev = found[it.infoHash]
            if (prev == null || (prev.secondary && !f.secondary))
                found[it.infoHash] = Result(it.title, it.seeders, it.size, it.infoHash, it.pageUrl, via, batch, f.secondary, f.note)
            return !f.secondary
        }

        fun isMain(it: Item) = found[it.infoHash]?.secondary == false

        /** Lê a página (descrição e arquivos) e classifica o idioma. */
        fun checkPage(it: Item, batch: Boolean) {
            val (desc, files) = try { page(it.pageUrl) } catch (_: Exception) { return }
            if (!keep(it, batch, lang.classify(desc, true), "descrição")) keep(it, batch, lang.classify(files, true), "arquivos")
        }

        for ((qi, q) in queries.withIndex()) {
            val name = q.text
            val which = if (queries.size > 1) " (nome ${qi + 1} de ${queries.size})" else ""
            /** Episódio certo (e, no nome-base, a temporada certa)? null = não serve. */
            fun episodeOf(it: Item): Boolean? {
                if (q.season != null && !hasSeason(it.title, q.season)) return null
                if (expected != null) statedSeason(it.title)?.let { st -> if (st != expected) return null }
                return matchEpisode(it.title, episode)
            }
            try {
                // 1) Com os termos do idioma na busca: o título já diz o idioma.
                status("Buscando \"$name\" ${lang.label} no Nyaa$which…")
                for (it in rss("$name ${lang.query}", lang.category)) {
                    if (isMain(it)) continue
                    val batch = episodeOf(it) ?: continue
                    keep(it, batch, lang.classify(it.title, false), "título")
                }
                // 2) Só com o nome: confere a descrição dos que não dizem o idioma no título.
                Thread.sleep(PAUSE_MS)
                status("Buscando outros lançamentos de \"$name\"$which…")
                for (it in rss(name, lang.category)) {
                    if (isMain(it)) continue
                    val batch = episodeOf(it) ?: continue
                    if (keep(it, batch, lang.classify(it.title, false), "título")) continue
                    if (checks >= MAX_DESCRIPTION_CHECKS) continue
                    checks++
                    status("Conferindo o idioma na página do lançamento ($checks de no máximo $MAX_DESCRIPTION_CHECKS)…\n${it.title}")
                    Thread.sleep(PAUSE_MS)
                    checkPage(it, batch)
                }
                // 3) PT-BR: lançamentos multi-legenda ficam na categoria em inglês (1_2), com o
                //    português só na descrição.
                if (lang == Lang.PTBR && mainCount() < 3) {
                    Thread.sleep(PAUSE_MS)
                    status("Buscando lançamentos com várias legendas de \"$name\"$which…")
                    var multiChecks = 0
                    for (it in rss(name, "1_2")) {
                        if (isMain(it)) continue
                        val batch = episodeOf(it) ?: continue
                        if (keep(it, batch, lang.classify(it.title, false), "título")) continue
                        if (!MULTI.containsMatchIn(it.title) || multiChecks >= MAX_MULTI_CHECKS) continue
                        multiChecks++
                        status("Conferindo as legendas do lançamento ($multiChecks de no máximo $MAX_MULTI_CHECKS)…\n${it.title}")
                        Thread.sleep(PAUSE_MS)
                        checkPage(it, batch)
                    }
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                status("Erro ao consultar o Nyaa: ${e.message}. Tentando continuar…")
            }
            if (mainCount() >= 3) break
            Thread.sleep(PAUSE_MS)
        }
        if (found.isEmpty() && lastError != null) throw lastError
        // PT-BR antes de Portugal; episódio avulso antes de pacote; mais seeds primeiro.
        return found.values.sortedWith(compareBy<Result> { it.secondary }.thenBy { it.batch }.thenByDescending { it.seeders })
    }

    // ---------- episódio no título ----------

    private val RANGE = Regex("""(?<![\dA-Za-z])0*(\d{1,4})\s*[-~]\s*0*(\d{1,4})(?![\dpP])""")
    private val SINGLE = listOf(
        Regex("""\s-\s0*(\d{1,4})(?:v\d)?(?!\d)"""),                 // "Titulo - 05"
        Regex("""S\d{1,2}\s?E0*(\d{1,4})(?!\d)""", RegexOption.IGNORE_CASE), // S01E05
        Regex("""(?<![A-Za-z])(?:EP?|Epis[oó]dio)\.?\s?0*(\d{1,4})(?!\d)""", RegexOption.IGNORE_CASE),
        Regex("""\[0*(\d{1,4})(?:v\d)?]"""),                          // "[05]"
    )
    private val TAGS = Regex("""\[[^\]]*]|\([^)]*\)""")
    /** "Season 2", "2nd Season": não confundir com pacote "2 - 07". */
    private val SEASON_WORDS = Regex("""season\s*\d+|\d+(?:st|nd|rd|th)\s+season""", RegexOption.IGNORE_CASE)

    /** null = não é este episódio; false = episódio avulso; true = pacote que contém o episódio. */
    fun matchEpisode(title: String, episode: Int): Boolean? {
        for (r in SINGLE) {
            for (m in r.findAll(title)) {
                if (m.groupValues[1].toIntOrNull() == episode) return false
            }
        }
        // Pacotes "01-12" / "01 ~ 12" (fora de colchetes com resolução, ano etc.).
        val plain = title.replace(TAGS, " ").replace(SEASON_WORDS, " ")
        for (m in RANGE.findAll(plain)) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a < b && b - a < 200 && episode in a..b) return true
        }
        return null
    }

    // ---------- rede ----------

    private fun rss(query: String, category: String): List<Item> {
        val url = BASE + "?page=rss&c=$category&f=0&s=seeders&o=desc&q=" + URLEncoder.encode(query, "UTF-8")
        val xml = get(url)
        val out = ArrayList<Item>()
        for (m in Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val block = m.groupValues[1]
            val title = tag(block, "title") ?: continue
            val hash = tag(block, "nyaa:infoHash") ?: continue
            val seeders = tag(block, "nyaa:seeders")?.toIntOrNull() ?: 0
            if (seeders <= 0) continue
            out.add(Item(title, seeders, tag(block, "nyaa:size") ?: "", hash, tag(block, "guid") ?: ""))
        }
        return out
    }

    /** Lê a descrição e a lista de arquivos da página do torrent. */
    private fun page(url: String): Pair<String, String> {
        if (!url.startsWith(BASE)) return "" to ""
        val html = get(url)
        fun grab(r: Regex) = r.find(html)?.groupValues?.get(1)?.let { unescape(it.replace(Regex("<[^>]+>"), " ")) } ?: ""
        val desc = grab(Regex("""id="torrent-description"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL))
        val files = grab(Regex("""class="torrent-file-list[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL))
        return desc to files
    }

    private fun tag(block: String, name: String): String? {
        val m = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block) ?: return null
        var v = m.groupValues[1].trim()
        if (v.startsWith("<![CDATA[")) v = v.removePrefix("<![CDATA[").removeSuffix("]]>")
        return unescape(v).trim().takeIf { it.isNotEmpty() }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", UA)
            val code = conn.responseCode
            if (code == 429) throw Exception("o Nyaa pediu para esperar um pouco (muitas consultas)")
            if (code !in 200..299) throw Exception("o Nyaa respondeu com erro $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- texto ----------

    private fun unescape(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")

    /** Tira pontuação que atrapalha a busca do Nyaa (":", aspas, "|" etc.). */
    private fun cleanName(s: String) =
        s.replace(Regex("""[:;"|()\[\]!?,.~]"""), " ").replace(Regex("""\s+"""), " ").trim()

    private fun isLatin(s: String) = s.count { it.code < 0x250 } >= s.length * 0.9
}

/** Abre links magnéticos no Amnis (player de torrent para Android TV). */
object Amnis {
    const val PACKAGE = "com.amnis"

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Devolve uma mensagem de erro, ou null se abriu. */
    fun open(ctx: Context, magnet: String): String? {
        val uri = Uri.parse(magnet)
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return null
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        // Sem Amnis: deixa o Android oferecer outro app que aceite magnet.
        return try {
            ctx.startActivity(
                Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Abrir com")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Amnis não encontrado nesta TV; escolha outro app."
        } catch (_: Exception) {
            "Nenhum app nesta TV abre links magnéticos. Instale o Amnis."
        }
    }
}
