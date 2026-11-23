package com.idelmiro.animestv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Busca no Nyaa (feed RSS, sem login) o episódio legendado em PT-BR de animes que não
 * estão no Crunchyroll. Critérios: categoria "Anime - Non-English-translated" (1_3),
 * ordenado por seeds, PT-BR confirmado pelo título ou, se não houver, pela descrição
 * ou pela lista de arquivos da página do torrent.
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

    private val PTBR = Regex(
        """pt[-_ .]?br|portugu[eê]s|portuguese|legendado|\[pt]""",
        RegexOption.IGNORE_CASE
    )
    private const val PTBR_QUERY = "(pt-br|ptbr|legendado|portuguese|portugues)"

    data class Result(
        val title: String,
        val seeders: Int,
        val size: String,
        val infoHash: String,
        val pageUrl: String,
        /** Onde o PT-BR foi confirmado: "título", "descrição" ou "arquivos". */
        val via: String,
        /** true quando é um pacote com vários episódios (o Amnis pede para escolher o arquivo). */
        val batch: Boolean
    ) {
        val magnet: String
            get() = "magnet:?xt=urn:btih:$infoHash&dn=${Uri.encode(title)}" +
                TRACKERS.joinToString("") { "&tr=${Uri.encode(it)}" }
    }

    private class Item(val title: String, val seeders: Int, val size: String, val infoHash: String, val pageUrl: String)

    /**
     * Procura o [episode] do anime pelos [names] (o primeiro é o título principal).
     * Roda em thread de fundo (faz várias consultas de rede).
     */
    fun search(names: List<String>, episode: Int): List<Result> {
        val queries = names.map { cleanName(it) }.filter { it.length >= 3 && isLatin(it) }.distinct().take(3)
        val found = LinkedHashMap<String, Result>()
        var lastError: Exception? = null
        var checks = 0

        for (name in queries) {
            try {
                // 1) Com termos de PT-BR na busca: o título já confirma o idioma.
                for (it in rss("$name $PTBR_QUERY")) {
                    if (it.infoHash in found) continue
                    val batch = matchEpisode(it.title, episode) ?: continue
                    if (PTBR.containsMatchIn(it.title)) found[it.infoHash] = it.toResult("título", batch)
                }
                // 2) Só com o nome: confere a descrição dos que não dizem o idioma no título.
                for (it in rss(name)) {
                    if (it.infoHash in found) continue
                    val batch = matchEpisode(it.title, episode) ?: continue
                    if (PTBR.containsMatchIn(it.title)) {
                        found[it.infoHash] = it.toResult("título", batch)
                        continue
                    }
                    if (checks >= MAX_DESCRIPTION_CHECKS) continue
                    checks++
                    Thread.sleep(PAUSE_MS)
                    val (desc, files) = try { page(it.pageUrl) } catch (_: Exception) { continue }
                    when {
                        PTBR.containsMatchIn(desc) -> found[it.infoHash] = it.toResult("descrição", batch)
                        PTBR.containsMatchIn(files) -> found[it.infoHash] = it.toResult("arquivos", batch)
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
            if (found.values.count { !it.batch } >= 3) break
            Thread.sleep(PAUSE_MS)
        }
        if (found.isEmpty() && lastError != null) throw lastError
        // Episódio avulso antes de pacote; dentro de cada grupo, mais seeds primeiro.
        return found.values.sortedWith(compareBy<Result> { it.batch }.thenByDescending { it.seeders })
    }

    private fun Item.toResult(via: String, batch: Boolean) = Result(title, seeders, size, infoHash, pageUrl, via, batch)

    // ---------- episódio no título ----------

    private val RANGE = Regex("""(?<![\dA-Za-z])0*(\d{1,4})\s*[-~]\s*0*(\d{1,4})(?![\dpP])""")
    private val SINGLE = listOf(
        Regex("""\s-\s0*(\d{1,4})(?:v\d)?(?!\d)"""),                 // "Titulo - 05"
        Regex("""S\d{1,2}\s?E0*(\d{1,4})(?!\d)""", RegexOption.IGNORE_CASE), // S01E05
        Regex("""(?<![A-Za-z])(?:EP?|Epis[oó]dio)\.?\s?0*(\d{1,4})(?!\d)""", RegexOption.IGNORE_CASE),
        Regex("""\[0*(\d{1,4})(?:v\d)?]"""),                          // "[05]"
    )
    private val TAGS = Regex("""\[[^\]]*]|\([^)]*\)""")

    /** null = não é este episódio; false = episódio avulso; true = pacote que contém o episódio. */
    fun matchEpisode(title: String, episode: Int): Boolean? {
        for (r in SINGLE) {
            for (m in r.findAll(title)) {
                if (m.groupValues[1].toIntOrNull() == episode) return false
            }
        }
        // Pacotes "01-12" / "01 ~ 12" (fora de colchetes com resolução, ano etc.).
        val plain = title.replace(TAGS, " ")
        for (m in RANGE.findAll(plain)) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a < b && b - a < 200 && episode in a..b) return true
        }
        return null
    }

    // ---------- rede ----------

    private fun rss(query: String): List<Item> {
        val url = BASE + "?page=rss&c=1_3&f=0&s=seeders&o=desc&q=" + URLEncoder.encode(query, "UTF-8")
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
