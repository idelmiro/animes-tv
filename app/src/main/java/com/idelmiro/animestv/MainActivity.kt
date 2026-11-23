package com.idelmiro.animestv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private lateinit var daysRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var weekLabel: TextView
    private lateinit var btnFav: Button
    private lateinit var adapter: AnimeAdapter

    private val zone: ZoneId = ZoneId.systemDefault()
    private var monday: LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private var selected: LocalDate = LocalDate.now()
    private val cache = HashMap<LocalDate, List<Airing>>()
    private var loading: Job? = null
    private var loadError: String? = null
    private val ui = Handler(Looper.getMainLooper())
    /** Janela "Abrindo no Amnis…" e o aviso de demora, enquanto o Amnis não assume a tela. */
    private var launchWatch: Pair<AlertDialog, Runnable>? = null

    // Teste guiado de formas de abrir o Crunchyroll. Fica salvo nas preferências porque
    // o Android pode fechar este app enquanto o Crunchyroll toca o vídeo.
    private class TestRun(val kind: String, val variants: List<CrunchyrollLauncher.Variant>, val question: String)

    private val dayNames = arrayOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")
    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        daysRow = findViewById(R.id.days)
        status = findViewById(R.id.status)
        weekLabel = findViewById(R.id.weekLabel)
        btnFav = findViewById(R.id.btnFav)

        adapter = AnimeAdapter(prefs) { onAnimeClick(it) }
        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 6)
        grid.adapter = adapter

        findViewById<Button>(R.id.btnPrev).setOnClickListener { changeWeek(-1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { changeWeek(1) }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            cache.clear()
            load()
            HomeWorker.runNow(this)
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { openSettings() }
        btnFav.setOnClickListener {
            prefs.onlyFavorites = !prefs.onlyFavorites
            updateFavButton()
            updateDays()
            showDay()
        }

        updateFavButton()
        load()
        HomeWorker.schedule(this)
        HomeWorker.runNow(this)
    }

    override fun onResume() {
        super.onResume()
        val saved = prefs.pendingTest ?: return
        prefs.pendingTest = null
        try {
            val o = JSONObject(saved)
            val arr = o.getJSONArray("variants")
            val variants = (0 until arr.length()).map { i ->
                val v = arr.getJSONObject(i)
                CrunchyrollLauncher.Variant(v.getString("key"), v.getString("label"),
                    Intent.parseUri(v.getString("uri"), Intent.URI_INTENT_SCHEME))
            }
            askTestResult(TestRun(o.getString("kind"), variants, o.getString("question")), o.getInt("index"))
        } catch (_: Exception) {
        }
    }

    private fun askTestResult(t: TestRun, idx: Int) {
        val v = t.variants.getOrNull(idx) ?: return
        val hasNext = idx + 1 < t.variants.size
        val b = dialog().setTitle("Teste ${idx + 1} de ${t.variants.size}")
            .setMessage("Usei: ${v.label}\n\n${t.question}")
            .setPositiveButton("Sim, funcionou") { _, _ ->
                if (t.kind == "ep") {
                    prefs.episodeMethod = v.key
                    HomeWorker.runNow(this)
                    toast("Pronto! A partir de agora \"Assistir\" vai direto ao episódio.")
                } else {
                    prefs.openMethod = v.key
                    toast("Pronto! A partir de agora os animes abrem desse jeito.")
                }
            }
        if (hasNext) b.setNegativeButton("Não, testar a próxima") { _, _ -> runTest(t, idx + 1) }
        else b.setNegativeButton("Não (era a última)") { _, _ ->
            toast("Nenhuma forma funcionou. Me conte o que aconteceu em cada uma.")
        }
        b.setNeutralButton("Parar", null).show()
    }

    private fun runTest(t: TestRun, index: Int) {
        if (t.variants.isEmpty()) {
            toast("Não encontrei o app do Crunchyroll.")
            return
        }
        val v = t.variants.getOrNull(index) ?: return
        val arr = JSONArray()
        for (x in t.variants) {
            arr.put(JSONObject().put("key", x.key).put("label", x.label).put("uri", x.intent.toUri(Intent.URI_INTENT_SCHEME)))
        }
        prefs.pendingTest = JSONObject().put("kind", t.kind).put("question", t.question)
            .put("index", index).put("variants", arr).toString()
        if (!CrunchyrollLauncher.launch(this, v)) {
            // O Android recusou na hora: pula direto para a próxima.
            prefs.pendingTest = null
            if (index + 1 < t.variants.size) runTest(t, index + 1)
            else toast("O Crunchyroll recusou todas as formas.")
        }
    }

    /** Busca o código do episódio no Crunchyroll e chama [then] com ele. */
    private fun withEpisode(a: Airing, then: (CrunchyrollApi.Episode) -> Unit) {
        val sid = CrunchyrollApi.seriesIdFromUrl(a.anime.crunchyrollUrl)
        if (sid == null) {
            toast("Este anime não tem link do Crunchyroll.")
            return
        }
        toast("Procurando o episódio ${a.episode} no Crunchyroll…")
        scope.launch {
            try {
                val ep = withContext(Dispatchers.IO) { CrunchyrollApi.findEpisode(sid, a.episode) }
                then(ep)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dialog().setTitle("Episódio").setMessage("Não consegui achar o episódio: ${e.message}\n\nVou abrir a página do anime.")
                    .setPositiveButton("OK") { _, _ ->
                        CrunchyrollLauncher.openSeries(this@MainActivity, a.anime.crunchyrollUrl)?.let { toast(it) }
                    }.show()
            }
        }
    }

    private fun watchEpisode(a: Airing) {
        withEpisode(a) { ep ->
            CrunchyrollLauncher.openEpisode(this, ep.id, ep.slug, a.anime.crunchyrollUrl)?.let { toast(it) }
        }
    }

    private fun testEpisode(a: Airing) {
        withEpisode(a) { ep ->
            val t = TestRun(
                "ep",
                CrunchyrollLauncher.episodeVariants(this, ep.id, ep.slug),
                "O Crunchyroll abriu o episódio ${ep.number} de \"${a.anime.title}\" (tocando ou pronto para tocar)?"
            )
            runTest(t, 0)
        }
    }

    private fun testSeries(a: Airing) {
        val url = a.anime.crunchyrollUrl
        if (url == null) {
            toast("Este anime não tem link do Crunchyroll. Teste com outro.")
            return
        }
        val t = TestRun(
            "series",
            CrunchyrollLauncher.seriesVariants(this, url),
            "O Crunchyroll abriu a página de \"${a.anime.title}\"?"
        )
        runTest(t, 0)
    }

    override fun onPause() {
        super.onPause()
        // Outro app (o Amnis) assumiu a tela: a abertura deu certo.
        launchWatch?.let { (dlg, timeout) ->
            ui.removeCallbacks(timeout)
            dlg.dismiss()
            launchWatch = null
        }
    }

    override fun onDestroy() {
        launchWatch?.let { ui.removeCallbacks(it.second) }
        scope.cancel()
        super.onDestroy()
    }

    // ---------- semana e carregamento ----------

    private fun changeWeek(delta: Long) {
        val dayIndex = selected.dayOfWeek.value - 1L
        monday = monday.plusWeeks(delta)
        selected = monday.plusDays(dayIndex)
        load()
    }

    private fun load() {
        weekLabel.text = "${monday.format(dateFmt)} – ${monday.plusDays(6).format(dateFmt)}"
        buildDays()
        if (cache[monday] != null) {
            showDay()
            return
        }
        loading?.cancel()
        loadError = null
        adapter.items = emptyList()
        setStatus("Carregando programação…")
        val week = monday
        loading = scope.launch {
            try {
                val off = prefs.offsetMinutes * 60L
                val start = week.atStartOfDay(zone).toEpochSecond() - off
                val end = week.plusDays(7).atStartOfDay(zone).toEpochSecond() - off
                val list = withContext(Dispatchers.IO) { AniList.fetchSchedule(start, end) }
                cache[week] = list
                if (week == monday) {
                    updateDays()
                    showDay()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: e.javaClass.simpleName
                setStatus("Não consegui carregar a programação ($loadError). Aperte Atualizar.")
            }
        }
    }

    private fun weekList(): List<Airing> =
        (cache[monday] ?: emptyList()).filter { !prefs.onlyCrunchyroll || it.anime.crunchyrollUrl != null }

    private fun dayOf(a: Airing): LocalDate =
        Instant.ofEpochMilli(a.releaseMillis(prefs.offsetMinutes)).atZone(zone).toLocalDate()

    // ---------- dias ----------

    private fun buildDays() {
        daysRow.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (i in 0 until 7) {
            val d = monday.plusDays(i.toLong())
            val tv = inflater.inflate(R.layout.item_day, daysRow, false) as TextView
            tv.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && selected != d) selectDay(d)
            }
            tv.setOnClickListener { selectDay(d) }
            daysRow.addView(tv)
        }
        updateDays()
        val idx = (selected.toEpochDay() - monday.toEpochDay()).toInt()
        if (idx in 0..6) daysRow.getChildAt(idx).post { daysRow.getChildAt(idx)?.requestFocus() }
    }

    private fun updateDays() {
        val favs = prefs.favorites
        val list = weekList()
        val today = LocalDate.now()
        for (i in 0 until daysRow.childCount) {
            val d = monday.plusDays(i.toLong())
            val tv = daysRow.getChildAt(i) as TextView
            val hasFav = list.any { it.anime.id in favs && dayOf(it) == d }
            val name = if (d == today) "Hoje" else dayNames[i]
            val sb = SpannableStringBuilder("${d.dayOfMonth}\n$name\n")
            val dotStart = sb.length
            sb.append(if (hasFav) "●" else " ")
            sb.setSpan(ForegroundColorSpan(0xFFFF4D4D.toInt()), dotStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.8f), dotStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = sb
            tv.isSelected = d == selected
        }
    }

    private fun selectDay(d: LocalDate) {
        selected = d
        updateDays()
        showDay()
    }

    // ---------- grade ----------

    private fun showDay() {
        if (cache[monday] == null) return
        val favs = prefs.favorites
        var items = weekList().filter { dayOf(it) == selected }
        if (prefs.onlyFavorites) items = items.filter { it.anime.id in favs }
        adapter.items = items.sortedBy { it.airingAt }
        setStatus(
            when {
                items.isNotEmpty() -> null
                prefs.onlyFavorites -> "Nenhum favorito neste dia. Aperte \"Só favoritos\" para ver todos."
                else -> "Nenhum anime neste dia."
            }
        )
    }

    private fun setStatus(text: String?) {
        status.text = text ?: ""
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateFavButton() {
        btnFav.text = if (prefs.onlyFavorites) "♥ Só favoritos" else "♡ Todos"
    }

    private fun onAnimeClick(a: Airing) {
        if (a.anime.crunchyrollUrl == null) {
            onOtherServiceClick(a)
            return
        }
        val fav = prefs.isFav(a.anime.id)
        val options = arrayOf(
            "▶ Assistir episódio ${a.episode}",
            "Abrir a página do anime",
            if (fav) "Remover dos favoritos" else "Adicionar aos favoritos",
            "Testar formas de abrir o episódio",
            "Testar formas de abrir a página do anime"
        )
        dialog().setTitle("${a.anime.title} — Ep. ${a.episode}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> watchEpisode(a)
                    1 -> CrunchyrollLauncher.openSeries(this, a.anime.crunchyrollUrl)?.let { toast(it) }
                    2 -> toggleFav(a)
                    3 -> testEpisode(a)
                    4 -> testSeries(a)
                }
            }.show()
    }

    private fun toggleFav(a: Airing) {
        prefs.toggleFav(a.anime.id)
        updateDays()
        if (prefs.onlyFavorites) showDay() else adapter.notifyDataSetChanged()
        HomeWorker.runNow(this)
    }

    // ---------- animes fora do Crunchyroll: Nyaa + Amnis ----------

    private fun onOtherServiceClick(a: Airing) {
        val fav = prefs.isFav(a.anime.id)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "▶ Buscar episódio ${a.episode} legendado (PT-BR)" to { searchNyaa(a, a.episode) }
        if (a.episode > 1) actions += "Episódios anteriores…" to { chooseEarlierEpisode(a) }
        for (st in a.anime.streams) actions += "Abrir no ${st.site}" to { openUrl(st.url) }
        actions += (if (fav) "Remover dos favoritos" else "Adicionar aos favoritos") to { toggleFav(a) }
        val where = a.anime.streams.joinToString(", ") { it.site }.ifEmpty { "serviço não informado" }
        val note = if (a.delayed) " • exibido no Japão há 1 semana" else ""
        dialog().setTitle("${a.anime.title} — Ep. ${a.episode}\n($where$note)")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /** Segunda janela: lista os episódios anteriores (do mais recente para o primeiro). */
    private fun chooseEarlierEpisode(a: Airing) {
        val eps = (a.episode - 1 downTo maxOf(1, a.episode - 60)).toList()
        val labels = eps.map { "Episódio $it" }.toTypedArray()
        dialog().setTitle("${a.anime.title} — episódios anteriores")
            .setItems(labels) { _, which -> searchNyaa(a, eps[which]) }
            .setNegativeButton("Voltar") { _, _ -> onOtherServiceClick(a) }
            .show()
    }

    /** Janela com indicador girando e um texto que é atualizado com a etapa real. */
    private class Progress(val dialog: AlertDialog, val text: TextView)

    private fun progressDialog(title: String, message: String, onCancel: (() -> Unit)?): Progress {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad / 2)
        }
        box.addView(ProgressBar(this).apply { isIndeterminate = true })
        val text = TextView(this).apply {
            setPadding(pad, 0, 0, 0)
            textSize = 16f
            this.text = message
        }
        box.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val b = dialog().setTitle(title).setView(box).setCancelable(onCancel != null)
        if (onCancel != null) {
            b.setNegativeButton("Cancelar") { _, _ -> onCancel() }
            b.setOnCancelListener { onCancel() }
        }
        return Progress(b.show(), text)
    }

    private fun searchNyaa(a: Airing, episode: Int) {
        var job: Job? = null
        val prog = progressDialog("${a.anime.title} — Ep. $episode", "Preparando a busca…") { job?.cancel() }
        job = scope.launch {
            try {
                val names = listOf(a.anime.title) + a.anime.altTitles
                val results = runInterruptible(Dispatchers.IO) {
                    Nyaa.search(names, episode) { msg -> ui.post { prog.text.text = msg } }
                }
                prog.dialog.dismiss()
                if (results.isEmpty()) {
                    val b = dialog().setTitle("Sem legenda PT-BR")
                        .setMessage("Não encontrei o episódio $episode de \"${a.anime.title}\" com legenda em português. " +
                            "As legendas dependem dos fansubs e podem demorar; tente de novo mais tarde.")
                        .setPositiveButton("OK", null)
                    if (a.episode > 1) b.setNegativeButton("Outro episódio") { _, _ -> chooseEarlierEpisode(a) }
                    a.anime.streams.firstOrNull()?.let { st ->
                        b.setNeutralButton("Abrir no ${st.site}") { _, _ -> openUrl(st.url) }
                    }
                    b.show()
                    return@launch
                }
                val shown = results.take(12)
                val labels = shown.map { r ->
                    val kind = if (r.batch) " • pacote" else ""
                    val big = if (!r.batch && tooBig(r.size)) " • maior que 1 GB" else ""
                    "${r.seeders} seeds • ${r.size}$big • PT-BR no ${r.via}$kind\n${r.title}"
                }.toTypedArray()
                dialog().setTitle("${a.anime.title} — Ep. $episode")
                    .setItems(labels) { _, which ->
                        val r = shown[which]
                        if (prefs.useAmnis) openInAmnis(r, episode)
                        else startActivity(Intent(this@MainActivity, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_MAGNET, r.magnet)
                            .putExtra(PlayerActivity.EXTRA_TITLE, a.anime.title)
                            .putExtra(PlayerActivity.EXTRA_EPISODE, episode))
                    }
                    .setNegativeButton("Fechar", null)
                    .show()
            } catch (e: CancellationException) {
                prog.dialog.dismiss()
                throw e
            } catch (e: Exception) {
                prog.dialog.dismiss()
                dialog().setTitle("Busca no Nyaa").setMessage("Não consegui buscar: ${e.message}")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    /** Tamanho do Nyaa ("1.4 GiB", "650.2 MiB") passa do limite de downloads? */
    private fun tooBig(size: String): Boolean {
        val m = Regex("""([\d.]+)\s*GiB""").find(size) ?: return false
        return (m.groupValues[1].toDoubleOrNull() ?: 0.0) * 1073741824.0 > Downloads.CAP
    }

    /**
     * Abre o magnet no Amnis e acompanha de verdade: a janela de espera só some quando o
     * Amnis assume a tela (o Android pausa este app). Se isso não acontecer em alguns
     * segundos, avisa e oferece tentar de novo.
     */
    private fun openInAmnis(r: Nyaa.Result, episode: Int) {
        val hint = if (r.batch) "\n\nÉ um pacote: escolha o episódio $episode na lista do Amnis." else ""
        val prog = progressDialog("Abrindo no Amnis", "Enviando o link para o Amnis…\n${r.title}$hint", null)
        val msg = Amnis.open(this, r.magnet)
        if (msg != null) {
            // Sem Amnis: o Android mostrou a lista de outros apps.
            prog.dialog.dismiss()
            toast(msg)
            return
        }
        prog.text.text = "Esperando o Amnis abrir…\n${r.title}$hint"
        val timeout = Runnable {
            launchWatch = null
            prog.dialog.dismiss()
            dialog().setTitle("O Amnis não abriu")
                .setMessage("Mandei o link, mas o Amnis não apareceu na tela em 10 segundos.\n\n${r.title}")
                .setPositiveButton("Tentar de novo") { _, _ -> openInAmnis(r, episode) }
                .setNegativeButton("Fechar", null)
                .show()
        }
        launchWatch = prog.dialog to timeout
        ui.postDelayed(timeout, 10_000)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
            toast("Nenhum app desta TV abre esse link.")
        }
    }

    // ---------- ajustes ----------

    private fun openSettings() {
        val options = arrayOf(
            "Atraso do legendado em relação ao Japão: ${prefs.offsetMinutes} min",
            "Mostrar: ${if (prefs.onlyCrunchyroll) "só Crunchyroll" else "todos os serviços"}",
            "Player dos episódios do Nyaa: ${if (prefs.useAmnis) "Amnis" else "Animes TV"}",
            "Downloads: ${Downloads.count(this)} episódio(s), ${Downloads.human(Downloads.used(this))} de 1 GB",
            "Atualizar o canal \"Animes de hoje\" agora",
            "Diagnóstico",
            "Verificar atualização (versão ${BuildConfig.VERSION_CODE})"
        )
        dialog().setTitle("Ajustes").setItems(options) { _, which ->
            when (which) {
                0 -> chooseOffset()
                1 -> {
                    prefs.onlyCrunchyroll = !prefs.onlyCrunchyroll
                    updateDays()
                    showDay()
                    toast(if (prefs.onlyCrunchyroll) "Mostrando só animes do Crunchyroll" else "Mostrando animes de todos os serviços")
                }
                2 -> {
                    prefs.useAmnis = !prefs.useAmnis
                    toast(if (prefs.useAmnis) "Episódios do Nyaa vão abrir no Amnis" else "Episódios do Nyaa vão tocar no próprio Animes TV")
                }
                3 -> dialog().setTitle("Downloads")
                    .setMessage("Os episódios baixados ficam guardados até o limite de 1 GB; os mais antigos são apagados " +
                        "automaticamente antes de cada novo download.\n\nOcupado agora: ${Downloads.human(Downloads.used(this))} " +
                        "em ${Downloads.count(this)} episódio(s).")
                    .setPositiveButton("Apagar todos") { _, _ ->
                        Downloads.clearAll(this)
                        toast("Downloads apagados.")
                    }
                    .setNegativeButton("Fechar", null).show()
                4 -> {
                    HomeWorker.runNow(this)
                    toast("Publicando… confira o resultado em Diagnóstico daqui a alguns segundos.")
                }
                5 -> showDiagnostics()
                6 -> checkUpdate()
            }
        }.show()
    }

    private fun chooseOffset() {
        val values = intArrayOf(0, 30, 60, 90, 120, 180)
        val labels = values.map { if (it == 0) "Sem atraso" else "$it minutos" }.toTypedArray()
        val current = values.indexOf(prefs.offsetMinutes)
        dialog().setTitle("Atraso do legendado").setSingleChoiceItems(labels, current) { d, which ->
            prefs.offsetMinutes = values[which]
            cache.clear()
            d.dismiss()
            load()
            HomeWorker.runNow(this)
        }.show()
    }

    private fun showDiagnostics() {
        val pkgs = CrunchyrollLauncher.installedPackages(this)
        val handlers = CrunchyrollLauncher.handlersFor(this, CrunchyrollLauncher.SAMPLE_URL)
        val text = buildString {
            appendLine("Versão do app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) — ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("Apps do Crunchyroll instalados:")
            appendLine(if (pkgs.isEmpty()) "  nenhum" else pkgs.joinToString("\n") { "  $it" })
            appendLine()
            appendLine("Quem abre links de série do Crunchyroll:")
            appendLine(if (handlers.isEmpty()) "  ninguém" else handlers.joinToString("\n") { "  $it" })
            appendLine()
            for (p in pkgs) {
                appendLine("Links aceitos por $p:")
                append(ManifestReader.report(this@MainActivity, p))
                appendLine()
            }
            appendLine("Amnis (torrent): ${if (Amnis.isInstalled(this@MainActivity)) "instalado" else "não instalado"}")
            appendLine("Forma de abrir anime: ${prefs.openMethod ?: CrunchyrollLauncher.DEFAULT_SERIES_METHOD + " (padrão)"}")
            appendLine("Forma de abrir episódio: ${prefs.episodeMethod ?: CrunchyrollLauncher.DEFAULT_EPISODE_METHOD + " (padrão)"}")
            appendLine("Códigos de episódio vêm de: ${CrunchyrollApi.lastSource.ifEmpty { "nenhuma busca ainda" }}")
            appendLine("Favoritos: ${prefs.favorites.size}")
            appendLine("Tela inicial: ${prefs.homeStatus}")
            if (loadError != null) appendLine("Último erro de carregamento: $loadError")
        }
        dialog().setTitle("Diagnóstico").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun checkUpdate() {
        toast("Verificando atualização…")
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Updater.fetchLatest() }
                if (r.version <= BuildConfig.VERSION_CODE) {
                    toast("Você já está na versão mais recente (${BuildConfig.VERSION_CODE}).")
                    return@launch
                }
                toast("Baixando a versão ${r.version}…")
                val file = withContext(Dispatchers.IO) { Updater.download(this@MainActivity, r.apkUrl) }
                Updater.install(this@MainActivity, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dialog().setTitle("Atualização").setMessage("Não foi possível atualizar: ${e.message}")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

class AnimeAdapter(
    private val prefs: Prefs,
    private val onClick: (Airing) -> Unit
) : RecyclerView.Adapter<AnimeAdapter.VH>() {

    var items: List<Airing> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val time: TextView = v.findViewById(R.id.time)
        val ep: TextView = v.findViewById(R.id.ep)
        val heart: TextView = v.findViewById(R.id.heart)
        val title: TextView = v.findViewById(R.id.title)
        val service: TextView = v.findViewById(R.id.service)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_anime, parent, false)
        v.setOnFocusChangeListener { view, hasFocus ->
            val s = if (hasFocus) 1.08f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(120).start()
            view.elevation = if (hasFocus) 12f else 0f
        }
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        holder.time.text = Instant.ofEpochMilli(a.releaseMillis(prefs.offsetMinutes))
            .atZone(ZoneId.systemDefault()).format(timeFmt)
        holder.ep.text = a.episode.toString()
        holder.heart.visibility = if (prefs.isFav(a.anime.id)) View.VISIBLE else View.GONE
        holder.title.text = a.anime.title
        val svc = a.anime.serviceLabel
        holder.service.visibility = if (svc == null) View.GONE else View.VISIBLE
        holder.service.text = svc?.let { shortService(it) }
        ImageLoader.load(a.anime.cover, holder.poster)
        holder.itemView.setOnClickListener { onClick(a) }
    }

    override fun getItemCount() = items.size

    private fun shortService(site: String): String = when {
        site.contains("Netflix", true) -> "Netflix"
        site.contains("Disney", true) -> "Disney+"
        site.contains("Prime", true) || site.contains("Amazon", true) -> "Prime"
        site.contains("HIDIVE", true) -> "HIDIVE"
        site.contains("Max", true) || site.contains("HBO", true) -> "Max"
        else -> site.take(10)
    }
}
