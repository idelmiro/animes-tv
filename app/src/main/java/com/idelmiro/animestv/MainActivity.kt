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
import android.view.KeyEvent
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

    internal val scope = MainScope()
    internal lateinit var prefs: Prefs
    internal lateinit var daysRow: LinearLayout
    internal lateinit var status: TextView
    internal lateinit var weekLabel: TextView
    internal lateinit var btnFav: Button
    internal lateinit var adapter: AnimeAdapter

    internal val zone: ZoneId = ZoneId.systemDefault()
    internal var monday: LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    internal var selected: LocalDate = LocalDate.now()
    internal val cache = HashMap<LocalDate, List<Airing>>()
    internal var loading: Job? = null
    internal var loadError: String? = null
    internal val ui = Handler(Looper.getMainLooper())
    /** Janela "Abrindo no Amnis…" e o aviso de demora, enquanto o Amnis não assume a tela. */
    internal var launchWatch: Pair<android.app.Dialog, Runnable>? = null

    // Teste guiado de formas de abrir o Crunchyroll. Fica salvo nas preferências porque
    // o Android pode fechar este app enquanto o Crunchyroll toca o vídeo.
    internal class TestRun(val kind: String, val variants: List<CrunchyrollLauncher.Variant>, val question: String)

    internal var isPhone = false

    /**
     * Celular: menos margem, cabeçalho em duas linhas na vertical (botões roláveis para o
     * lado), número de colunas pela largura da tela e cards que não "vazam" por cima dos
     * dias ao rolar a lista.
     */
    internal fun adaptForPhone(grid: RecyclerView) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = findViewById<LinearLayout>(R.id.root)
        root.setPadding(dp(10), dp(8), dp(10), 0)
        root.clipChildren = true
        grid.clipChildren = true
        grid.clipToPadding = true
        val title = findViewById<TextView>(R.id.titleText)
        title.textSize = 20f
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            findViewById<LinearLayout>(R.id.header).orientation = LinearLayout.VERTICAL
            title.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            findViewById<View>(R.id.headerScroll).layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val widthDp = resources.configuration.screenWidthDp - 20
        val span = maxOf(2, widthDp / 140)
        grid.layoutManager = GridLayoutManager(this, span)
        val colPx = (widthDp * d / span).toInt() - dp(16) - dp(8)
        adapter.posterHeight = (colPx * 1.42f).toInt()
    }

    internal val dayNames = arrayOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")
    internal val dateFmt = DateTimeFormatter.ofPattern("dd/MM")

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(TvScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        // Tela inicial, busca e menu lateral ficam por cima da semana/temporadas.
        val oldRoot = findViewById<View>(R.id.root)
        (oldRoot.parent as ViewGroup).removeView(oldRoot)
        home = HomeView(this)
        searchView = SearchView(this)
        sideMenu = SideMenu(this)
        val frame = android.widget.FrameLayout(this)
        frame.addView(oldRoot, android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(home.view, android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(searchView.view, android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(sideMenu.view, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)

        daysRow = findViewById(R.id.days)
        status = findViewById(R.id.status)
        weekLabel = findViewById(R.id.weekLabel)
        btnFav = findViewById(R.id.btnFav)

        adapter = AnimeAdapter(prefs) { if (seasonMode) onSeasonAnimeClick(it) else onAnimeClick(it) }
        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 6)
        grid.adapter = adapter
        isPhone = !Device.isTv(this)
        if (isPhone) adaptForPhone(grid)
        else {
            // TV: 6 colunas (como as linhas da tela inicial), capas na proporção certa.
            val d = resources.displayMetrics.density
            val widthDp = resources.configuration.screenWidthDp - 96
            grid.layoutManager = GridLayoutManager(this, 6)
            adapter.posterHeight = ((widthDp * d / 6) - 18 * d).let { (it * 1.42f).toInt() }
        }

        findViewById<Button>(R.id.btnMenu).setOnClickListener { openMenu() }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { changeWeek(-1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { changeWeek(1) }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            cache.clear()
            seasonCache.clear()
            load()
            HomeWorker.runNow(this)
        }
        btnFav.setOnClickListener {
            prefs.onlyFavorites = !prefs.onlyFavorites
            updateFavButton()
            updateDays()
            showDay()
        }

        updateFavButton()
        showScreen(Screen.HOME)
        home.start()
        PlayerLog.crashed(this)?.let { log ->
            // Fechou depois de o vídeo começar a ser lido (evento 8 = arquivo carregado):
            // provável problema de decodificação na TV. Na próxima vez, usa um modo mais compatível.
            var note = ""
            if (log.contains("mpv: evento 8") && prefs.videoMode < 2) {
                prefs.videoMode = prefs.videoMode + 1
                note = "\n\nNa próxima vez vou usar um modo de vídeo mais compatível (modo ${prefs.videoMode})."
            }
            showPlayerLog(log, crashed = true, note = note)
        }
        HomeWorker.schedule(this)
        HomeWorker.runNow(this)
    }

    override fun onResume() {
        super.onResume()
        if (screen == Screen.HOME) home.refreshLocal()
        // Episódios que terminaram de baixar na memória interna vão para o pendrive/HD.
        Downloads.finishMoves(applicationContext)
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

    internal fun askTestResult(t: TestRun, idx: Int) {
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

    internal fun runTest(t: TestRun, index: Int) {
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
    internal fun withEpisode(a: Airing, then: (CrunchyrollApi.Episode) -> Unit) {
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

    internal fun watchEpisode(a: Airing) {
        withEpisode(a) { ep ->
            CrunchyrollLauncher.openEpisode(this, ep.id, ep.slug, a.anime.crunchyrollUrl)?.let { toast(it) }
        }
    }

    internal fun testEpisode(a: Airing) {
        withEpisode(a) { ep ->
            val t = TestRun(
                "ep",
                CrunchyrollLauncher.episodeVariants(this, ep.id, ep.slug),
                "O Crunchyroll abriu o episódio ${ep.number} de \"${a.anime.title}\" (tocando ou pronto para tocar)?"
            )
            runTest(t, 0)
        }
    }

    internal fun testSeries(a: Airing) {
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
        if (::home.isInitialized) home.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- semana e carregamento ----------

    internal fun changeWeek(delta: Long) {
        val dayIndex = selected.dayOfWeek.value - 1L
        monday = monday.plusWeeks(delta)
        selected = monday.plusDays(dayIndex)
        load()
    }

    internal fun load() {
        if (seasonMode) {
            buildSeasonTabs()
            loadSeason()
            return
        }
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
                // Logo depois de ligar a TV a internet pode ainda não estar pronta: tenta de novo sozinho.
                var attempt = 0
                val list = withContext(Dispatchers.IO) {
                    while (true) {
                        try { return@withContext AniList.fetchSchedule(start, end) } catch (e: java.io.IOException) {
                            if (++attempt > 8) throw e
                            withContext(Dispatchers.Main) { setStatus("Esperando a internet da TV ficar pronta… (tentativa ${attempt + 1})") }
                            kotlinx.coroutines.delay(minOf(15_000L, 3_000L * attempt))
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    emptyList()
                }
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

    internal fun weekList(): List<Airing> =
        (cache[monday] ?: emptyList()).filter { !prefs.onlyCrunchyroll || it.anime.crunchyrollUrl != null }

    internal fun dayOf(a: Airing): LocalDate =
        Instant.ofEpochMilli(a.releaseMillis(prefs.offsetMinutes)).atZone(zone).toLocalDate()

    // ---------- dias ----------

    internal fun buildDays() {
        daysRow.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (i in 0 until 7) {
            val d = monday.plusDays(i.toLong())
            val tv = inflater.inflate(R.layout.item_day, daysRow, false) as TextView
            tv.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && selected != d) selectDay(d)
            }
            tv.setOnClickListener { selectDay(d) }
            if (isPhone) {
                tv.textSize = 13f
                (tv.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = (2 * resources.displayMetrics.density).toInt()
                    marginEnd = marginStart
                }
            }
            daysRow.addView(tv)
        }
        updateDays()
        val idx = (selected.toEpochDay() - monday.toEpochDay()).toInt()
        if (idx in 0..6) daysRow.getChildAt(idx).post { daysRow.getChildAt(idx)?.requestFocus() }
    }

    internal fun updateDays() {
        if (seasonMode) {
            updateSeasonTabs()
            return
        }
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

    internal fun selectDay(d: LocalDate) {
        selected = d
        updateDays()
        showDay()
    }

    // ---------- grade ----------

    internal fun showDay() {
        if (seasonMode) {
            showSeason()
            return
        }
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

    // ---------- temporadas (as 4 últimas) ----------

    internal var seasonMode = false
    internal val seasonCache = HashMap<AniList.Season, List<Airing>>()
    internal var selectedSeason = 0
    internal var seasons: List<AniList.Season> = emptyList()

    /** Troca entre a semana (calendário) e as últimas 4 temporadas. */
    internal fun toggleMode() {
        if (!seasonMode) selectedSeason = 0
        screen = if (seasonMode) Screen.WEEK else Screen.SEASONS
        applySeasonMode(!seasonMode)
    }

    // ---------- telas: início, semana, temporadas, busca ----------

    enum class Screen { HOME, WEEK, SEASONS, SEARCH }

    internal var screen = Screen.HOME
    internal lateinit var home: HomeView
    internal lateinit var sideMenu: SideMenu
    internal lateinit var searchView: SearchView
    private var lastFocus: View? = null

    internal fun showScreen(s: Screen) {
        screen = s
        home.view.visibility = if (s == Screen.HOME) View.VISIBLE else View.GONE
        searchView.view.visibility = if (s == Screen.SEARCH) View.VISIBLE else View.GONE
        findViewById<View>(R.id.root).visibility = if (s == Screen.WEEK || s == Screen.SEASONS) View.VISIBLE else View.GONE
        when (s) {
            Screen.HOME -> { home.refreshLocal(); home.focusStart() }
            Screen.SEARCH -> searchView.focus()
            Screen.WEEK -> applySeasonMode(false)
            Screen.SEASONS -> applySeasonMode(true)
        }
    }

    /** Abre a semana no dia [d]. */
    internal fun openWeekDay(d: LocalDate) {
        monday = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        selected = d
        showScreen(Screen.WEEK)
    }

    /** Abre as temporadas na aba [i] (0 = a atual). */
    internal fun openSeasonTab(i: Int) {
        selectedSeason = i
        showScreen(Screen.SEASONS)
    }

    internal fun openMenu() {
        lastFocus = currentFocus
        sideMenu.show(screen)
    }

    /** Menu aberto: as telas de trás não recebem o foco. */
    internal fun blockBackground(block: Boolean) {
        val mode = if (block) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        (findViewById<View>(R.id.root) as ViewGroup).descendantFocusability = mode
        (home.view as ViewGroup).descendantFocusability = mode
        (searchView.view as ViewGroup).descendantFocusability = mode
    }

    internal fun restoreFocus() {
        val f = lastFocus
        lastFocus = null
        if (f != null && f.isShown) f.requestFocus()
        else if (screen == Screen.HOME) home.focusStart()
    }

    /** Anime da tela inicial ou da busca: episódios se já estreou, trailer se ainda não. */
    internal fun openShow(s: AniList.Show) {
        if (s.aired <= 0) playTrailer(s.anime) else onSeasonAnimeClick(s.toAiring())
    }

    /** Trailer do YouTube indicado no AniList; sem ele, oferece procurar no YouTube. */
    internal fun playTrailer(a: Anime) {
        val id = a.trailer
        if (id != null) {
            startActivity(Intent(this, TrailerActivity::class.java)
                .putExtra(TrailerActivity.EXTRA_ID, id).putExtra(TrailerActivity.EXTRA_TITLE, a.title))
            return
        }
        val fav = prefs.isFav(a.id)
        dialog().setTitle(a.title)
            .setMessage("Ainda não estreou e o AniList não tem trailer deste anime.")
            .setPositiveButton("Procurar no YouTube") { _, _ ->
                openUrl("https://www.youtube.com/results?search_query=" + android.net.Uri.encode("${a.title} trailer"))
            }
            .setNeutralButton(if (fav) "Remover dos favoritos" else "Favoritar") { _, _ ->
                prefs.toggleFav(a.id)
                home.refreshLocal()
                HomeWorker.runNow(this)
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::home.isInitialized) home.onKey()
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (::sideMenu.isInitialized && (sideMenu.isOpen || screen != Screen.HOME)) {
                if (!down) { if (sideMenu.isOpen) sideMenu.hide() else showScreen(Screen.HOME) }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (!down) { if (sideMenu.isOpen) sideMenu.hide() else openMenu() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down && !sideMenu.isOpen && screen != Screen.SEARCH) {
                // ◀ na borda esquerda abre o menu (como na Apple TV).
                val f = currentFocus
                if (f != null && f.focusSearch(View.FOCUS_LEFT) == null) {
                    openMenu()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    internal fun applySeasonMode(on: Boolean) {
        seasonMode = on
        findViewById<TextView>(R.id.titleText).text = if (seasonMode) "Temporadas" else "Semana"
        val vis = if (seasonMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnPrev).visibility = vis
        findViewById<View>(R.id.btnNext).visibility = vis
        weekLabel.visibility = vis
        loading?.cancel()
        load()
    }

    internal fun buildSeasonTabs() {
        // Sempre as 4 últimas, a partir de hoje (em jan/2027: inverno 2027 e as 3 últimas de 2026).
        seasons = AniList.lastSeasons(LocalDate.now(), 4)
        if (selectedSeason !in seasons.indices) selectedSeason = 0
        daysRow.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (i in seasons.indices) {
            val tv = inflater.inflate(R.layout.item_day, daysRow, false) as TextView
            tv.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && selectedSeason != i) selectSeason(i) }
            tv.setOnClickListener { selectSeason(i) }
            if (isPhone) tv.textSize = 13f
            daysRow.addView(tv)
        }
        updateSeasonTabs()
        daysRow.getChildAt(selectedSeason)?.let { v -> v.post { v.requestFocus() } }
    }

    internal fun updateSeasonTabs() {
        val favs = prefs.favorites
        for (i in 0 until minOf(daysRow.childCount, seasons.size)) {
            val s = seasons[i]
            val tv = daysRow.getChildAt(i) as TextView
            val hasFav = seasonCache[s]?.any { it.anime.id in favs } == true
            val sb = SpannableStringBuilder("${s.label}\n${if (i == 0) "atual • " else ""}${s.months}\n")
            val dotStart = sb.length
            sb.append(if (hasFav) "●" else " ")
            sb.setSpan(ForegroundColorSpan(0xFFFF4D4D.toInt()), dotStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.8f), dotStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = sb
            tv.isSelected = i == selectedSeason
        }
    }

    internal fun selectSeason(i: Int) {
        selectedSeason = i
        updateSeasonTabs()
        loadSeason()
    }

    internal fun loadSeason() {
        val s = seasons.getOrNull(selectedSeason) ?: return
        if (seasonCache[s] != null) {
            showSeason()
            return
        }
        loading?.cancel()
        adapter.items = emptyList()
        setStatus("Carregando os animes de ${s.label}…")
        loading = scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { AniList.fetchSeason(s) }
                seasonCache[s] = list
                if (seasonMode && seasons.getOrNull(selectedSeason) == s) {
                    updateSeasonTabs()
                    showSeason()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("Não consegui carregar ${s.label} (${e.message ?: e.javaClass.simpleName}). Aperte Atualizar.")
            }
        }
    }

    internal fun showSeason() {
        val s = seasons.getOrNull(selectedSeason) ?: return
        val all = seasonCache[s] ?: return
        val favs = prefs.favorites
        var items = all.filter { !prefs.onlyCrunchyroll || it.anime.crunchyrollUrl != null }
        if (prefs.onlyFavorites) items = items.filter { it.anime.id in favs }
        adapter.items = items
        setStatus(
            when {
                items.isNotEmpty() -> null
                prefs.onlyFavorites -> "Nenhum favorito em ${s.label}. Aperte \"Só favoritos\" para ver todos."
                else -> "Nenhum anime em ${s.label}."
            }
        )
    }

    /** Anime na tela de temporadas: Crunchyroll abre a página da série; os outros, os episódios. */
    internal fun onSeasonAnimeClick(a: Airing) {
        if (a.episode <= 0) {
            playTrailer(a.anime)
            return
        }
        val cr = a.anime.crunchyrollUrl
        if (cr == null || prefs.crViaNyaa) {
            onOtherServiceClick(a)
            return
        }
        val fav = prefs.isFav(a.anime.id)
        val actions = listOf<Pair<String, () -> Unit>>(
            "Abrir no Crunchyroll" to { CrunchyrollLauncher.openSeries(this, cr)?.let { toast(it) } },
            "Episódios (buscar no Nyaa)…" to { chooseEarlierEpisode(a, true) },
            (if (fav) "Remover dos favoritos" else "Adicionar aos favoritos") to { toggleFav(a) }
        )
        dialog().setTitle("${a.anime.title} — ${a.episode} episódio(s)")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    internal fun setStatus(text: String?) {
        status.text = text ?: ""
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    internal fun updateFavButton() {
        btnFav.text = if (prefs.onlyFavorites) "♥ Só favoritos" else "♡ Todos"
    }

    internal fun onAnimeClick(a: Airing) {
        if (a.anime.crunchyrollUrl == null || prefs.crViaNyaa) {
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

    internal fun toggleFav(a: Airing) {
        prefs.toggleFav(a.anime.id)
        if (::home.isInitialized) home.refreshLocal()
        updateDays()
        if (prefs.onlyFavorites) showDay() else adapter.notifyDataSetChanged()
        HomeWorker.runNow(this)
    }

    // ---------- animes fora do Crunchyroll: Nyaa + Amnis ----------

    internal fun onOtherServiceClick(a: Airing) {
        val fav = prefs.isFav(a.anime.id)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        val sv = Downloads.savedFor(this, a.anime.id, a.episode)
        val label = when {
            sv.any { it.complete } -> "▶ Assistir episódio ${a.episode} (já baixado)"
            sv.isNotEmpty() -> "▶ Continuar episódio ${a.episode} (download em andamento)"
            else -> "▶ Buscar episódio ${a.episode} legendado (PT-BR)"
        }
        actions += label to { searchNyaa(a, a.episode) }
        actions += (if (a.episode > 1) "Episódios anteriores…" else "Temporadas anteriores…") to { chooseEarlierEpisode(a) }
        if (a.anime.crunchyrollUrl != null) actions += "Abrir no Crunchyroll" to { watchEpisode(a) }
        for (st in a.anime.streams) actions += "Abrir no ${st.site}" to { openUrl(st.url) }
        actions += (if (fav) "Remover dos favoritos" else "Adicionar aos favoritos") to { toggleFav(a) }
        val sites = (if (a.anime.crunchyrollUrl != null) listOf("Crunchyroll") else emptyList()) + a.anime.streams.map { it.site }
        val where = sites.joinToString(", ").ifEmpty { "serviço não informado" }
        val note = if (a.delayed) " • exibido no Japão há 1 semana" else ""
        dialog().setTitle("${a.anime.title} — Ep. ${a.episode}\n($where$note)")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /** Temporada anterior de cada anime já consultada no AniList (null = não tem). */
    internal val prequels = HashMap<Int, AniList.Prequel?>()

    /**
     * Segunda janela: lista os episódios anteriores (do mais recente para o primeiro) e, no
     * fim, a temporada anterior, se houver. [wholeSeason] = é uma temporada anterior inteira
     * ([a].episode é o último episódio dela); [back] = para onde o Voltar leva.
     */
    internal fun chooseEarlierEpisode(a: Airing, wholeSeason: Boolean = false, back: (() -> Unit)? = null) {
        val id = a.anime.id
        val cached = prequels.containsKey(id)
        if (!cached) {
            var job: Job? = null
            val prog = progressDialog(a.anime.title, "Procurando temporadas anteriores no AniList…") { job?.cancel() }
            job = scope.launch {
                val pre = try {
                    withContext(Dispatchers.IO) { AniList.fetchPrequel(id) }.also { prequels[id] = it }
                } catch (e: CancellationException) {
                    prog.dialog.dismiss(); throw e
                } catch (_: Exception) { null }
                prog.dialog.dismiss()
                showEarlier(a, wholeSeason, back, pre)
            }
        } else showEarlier(a, wholeSeason, back, prequels[id])
    }

    internal fun showEarlier(a: Airing, wholeSeason: Boolean, back: (() -> Unit)?, pre: AniList.Prequel?) {
        val last = if (wholeSeason) a.episode else a.episode - 1
        val eps = if (last < 1) emptyList() else (last downTo maxOf(1, last - 99)).toList()
        val goBack = back ?: { onOtherServiceClick(a) }
        if (eps.isEmpty() && pre == null) {
            toast("Não há episódios anteriores nem temporada anterior.")
            goBack()
            return
        }
        val mine = Downloads.saved(this).filter { it.animeId == a.anime.id }
        val done = mine.filter { it.complete }.map { it.episode }.toSet()
        val partial = mine.filter { !it.complete }.map { it.episode }.toSet()
        val labels = eps.map {
            when (it) {
                in done -> "Episódio $it • baixado"
                in partial -> "Episódio $it • em andamento"
                else -> "Episódio $it"
            }
        }.toMutableList()
        if (pre != null) labels += "◀ Temporada anterior: ${pre.anime.title} (${pre.episodes} episódios)"
        dialog().setTitle(if (wholeSeason) "${a.anime.title} — episódios" else "${a.anime.title} — episódios anteriores")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < eps.size) searchNyaa(a, eps[which])
                else if (pre != null) chooseEarlierEpisode(Airing(prequelWithSeason(a.anime, pre.anime), pre.episodes, 0L), true) {
                    showEarlier(a, wholeSeason, back, pre)
                }
            }
            .setNegativeButton("Voltar") { _, _ -> goBack() }
            .show()
    }

    /** A temporada anterior de uma "Season N" é a N-1 (ajuda a não pegar episódios da temporada errada). */
    internal fun prequelWithSeason(current: Anime, prequel: Anime): Anime {
        val s = current.seasonHint ?: Nyaa.seasonFromNames(listOf(current.title) + current.altTitles) ?: return prequel
        if (Nyaa.seasonFromNames(listOf(prequel.title) + prequel.altTitles) != null || s < 2) return prequel
        return prequel.copy(seasonHint = s - 1)
    }

    // ---------- baixados ----------

    /** Tudo o que está baixado ou em andamento, do usado mais recentemente para o mais antigo. */
    internal fun showSaved() {
        val list = Downloads.saved(this).sortedByDescending { it.lastActivity }
        if (list.isEmpty()) {
            dialog().setTitle("Baixados")
                .setMessage("Nenhum episódio baixado ou em andamento.")
                .setPositiveButton("OK", null).show()
            return
        }
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR"))
        val labels = list.map { sv ->
            val name = sv.title.ifBlank { sv.release }
            val state = if (sv.complete) "✔ baixado" else "⏳ ${sv.percent}% baixado"
            val stop = if (sv.pos > 30) " • parou em ${clock(sv.pos)}" else ""
            val dub = if (sv.dub) " • dublado em inglês" else ""
            val whenTxt = if (sv.lastActivity > 0) " • ${fmt.format(java.util.Date(sv.lastActivity))}" else ""
            "$name — Ep. ${sv.episode}\n$state$stop$dub • ${Downloads.human(sv.size)}$whenTxt"
        }.toTypedArray()
        dialog().setTitle("Baixados e em andamento (${list.size})")
            .setItems(labels) { _, which -> savedActions(list[which]) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    internal fun savedActions(sv: Downloads.Saved) {
        val name = sv.title.ifBlank { sv.release }
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (sv.complete) {
            if (sv.pos > 30) {
                actions += "▶ Continuar de ${clock(sv.pos)}" to { playSaved(name, sv.animeId, sv.episode, sv, sv.pos) }
                actions += "Assistir do começo" to { playSaved(name, sv.animeId, sv.episode, sv, 0) }
            } else actions += "▶ Assistir" to { playSaved(name, sv.animeId, sv.episode, sv, 0) }
        } else {
            val from = if (sv.pos > 30) ", de ${clock(sv.pos)}" else ""
            actions += "▶ Continuar (${sv.percent}% baixado$from)" to { resumeDownload(name, sv.animeId, sv.episode, sv) }
        }
        actions += "Apagar" to {
            if (sv.dir.absolutePath in Downloads.inUse) toast("Esse episódio está sendo usado agora.")
            else dialog().setTitle("Apagar $name — Ep. ${sv.episode}?")
                .setMessage("${Downloads.human(sv.size)} serão liberados.")
                .setPositiveButton("Apagar") { _, _ ->
                    sv.dir.deleteRecursively()
                    toast("Apagado.")
                    showSaved()
                }
                .setNegativeButton("Voltar") { _, _ -> savedActions(sv) }
                .show()
        }
        dialog().setTitle("$name — Ep. ${sv.episode}")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("Voltar") { _, _ -> showSaved() }
            .show()
    }

    /** Janela com indicador girando e um texto que é atualizado com a etapa real. */
    internal class Progress(val dialog: android.app.Dialog, val text: TextView)

    internal fun progressDialog(title: String, message: String, onCancel: (() -> Unit)?): Progress {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad / 2)
        }
        box.addView(ProgressBar(this).apply { isIndeterminate = true })
        val text = TextView(this).apply {
            setPadding(pad, 0, 0, 0)
            setTextColor(0xFFC9D1D9.toInt())
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

    /** Antes de buscar no Nyaa, vê se o episódio já está baixado (ou em andamento) e oferece continuar. */
    internal fun searchNyaa(a: Airing, episode: Int) {
        val saved = Downloads.savedFor(this, a.anime.id, episode)
        if (saved.isEmpty() || prefs.useAmnis) {
            searchNyaaNow(a, episode)
            return
        }
        val actions = ArrayList<Pair<String, () -> Unit>>()
        for (sv in saved) {
            val info = "${Downloads.human(sv.size)}${if (sv.dub) " • dublado em inglês" else ""} • ${sv.release}"
            if (sv.complete) {
                if (sv.pos > 30) {
                    actions += "▶ Continuar de ${clock(sv.pos)}\n$info" to { playSaved(a, episode, sv, sv.pos) }
                    actions += "Assistir do começo" to { playSaved(a, episode, sv, 0) }
                } else actions += "▶ Assistir o baixado\n$info" to { playSaved(a, episode, sv, 0) }
            } else {
                val from = if (sv.pos > 30) ", de ${clock(sv.pos)}" else ""
                actions += "▶ Continuar (${sv.percent}% baixado$from)\n$info" to { resumeDownload(a, episode, sv) }
            }
        }
        actions += "Buscar outra versão no Nyaa" to { searchNyaaNow(a, episode) }
        actions += (if (saved.size > 1) "Apagar os guardados deste episódio" else "Apagar o guardado") to {
            saved.forEach { it.dir.deleteRecursively() }
            toast("Apagado.")
        }
        val state = if (saved.any { it.complete }) "já está baixado" else "tem download em andamento"
        dialog().setTitle("${a.anime.title} — Ep. $episode $state")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    internal fun clock(s: Long) = if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)

    internal fun playSaved(a: Airing, episode: Int, sv: Downloads.Saved, start: Long) =
        playSaved(a.anime.title, a.anime.id, episode, sv, start)

    internal fun playSaved(title: String, animeId: Int, episode: Int, sv: Downloads.Saved, start: Long) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_LOCAL_FILE, sv.video!!.absolutePath)
            .putExtra(PlayerActivity.EXTRA_DIR, sv.dir.absolutePath)
            .putExtra(PlayerActivity.EXTRA_TITLE, title)
            .putExtra(PlayerActivity.EXTRA_EPISODE, episode)
            .putExtra(PlayerActivity.EXTRA_ANIME_ID, animeId)
            .putExtra(PlayerActivity.EXTRA_DUB, sv.dub)
            .putExtra(PlayerActivity.EXTRA_START, start))
    }

    /** Download que ficou pela metade: retoma o torrent aproveitando o que já foi baixado. */
    internal fun resumeDownload(a: Airing, episode: Int, sv: Downloads.Saved) =
        resumeDownload(a.anime.title, a.anime.id, episode, sv)

    internal fun resumeDownload(title: String, animeId: Int, episode: Int, sv: Downloads.Saved) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_MAGNET, sv.magnet)
            .putExtra(PlayerActivity.EXTRA_TITLE, title)
            .putExtra(PlayerActivity.EXTRA_EPISODE, episode)
            .putExtra(PlayerActivity.EXTRA_ANIME_ID, animeId)
            .putExtra(PlayerActivity.EXTRA_RELEASE, sv.release)
            .putExtra(PlayerActivity.EXTRA_DUB, sv.dub)
            .putExtra(PlayerActivity.EXTRA_START, if (sv.pos > 30) sv.pos else 0L))
    }

    internal fun searchNyaaNow(a: Airing, episode: Int, lang: Nyaa.Lang = Nyaa.Lang.PTBR) {
        var job: Job? = null
        val dubbed = lang == Nyaa.Lang.EN_DUB
        val heading = "${a.anime.title} — Ep. $episode" + if (dubbed) " (dublado em inglês)" else ""
        val prog = progressDialog(heading, "Preparando a busca…") { job?.cancel() }
        job = scope.launch {
            try {
                val names = listOf(a.anime.title) + a.anime.altTitles
                val results = runInterruptible(Dispatchers.IO) {
                    Nyaa.search(names, episode, lang, a.anime.seasonHint) { msg -> ui.post { prog.text.text = msg } }
                }
                prog.dialog.dismiss()
                if (results.isEmpty()) {
                    val b = if (!dubbed) dialog().setTitle("Sem legenda PT-BR")
                        .setMessage("Não encontrei o episódio $episode de \"${a.anime.title}\" com legenda em português. " +
                            "As legendas dependem dos fansubs e podem demorar; tente de novo mais tarde.\n\n" +
                            "Se quiser, posso procurar o mesmo episódio dublado em inglês.")
                        .setPositiveButton("Buscar dublado em inglês") { _, _ -> searchNyaaNow(a, episode, Nyaa.Lang.EN_DUB) }
                    else dialog().setTitle("Sem versão dublada")
                        .setMessage("Também não encontrei o episódio $episode de \"${a.anime.title}\" dublado em inglês. " +
                            "Nem todo anime ganha dublagem, e ela costuma sair semanas depois do episódio original.")
                        .setPositiveButton("OK", null)
                    if (a.episode > 1) b.setNegativeButton("Outro episódio") { _, _ -> chooseEarlierEpisode(a) }
                    a.anime.streams.firstOrNull()?.let { st ->
                        b.setNeutralButton("Abrir no ${st.site}") { _, _ -> openUrl(st.url) }
                    }
                    b.show()
                    return@launch
                }
                val shown = results.take(12)
                val onlySecondary = shown.none { !it.secondary }
                val labels = shown.map { r ->
                    val kind = if (r.batch) " • pacote" else ""
                    val big = if (!r.batch && tooBig(r.size)) " • maior que ${Downloads.capLabel(this@MainActivity)} (vai pedir confirmação)" else ""
                    val what = if (r.secondary) "⚠ ${r.note.replaceFirstChar { it.uppercase() }}, no ${r.via}" else "${lang.short} no ${r.via}"
                    "${r.seeders} seeds • ${r.size}$big • $what$kind\n${r.title}"
                }.toTypedArray()
                val play = { r: Nyaa.Result ->
                    if (prefs.useAmnis) openInAmnis(r, episode)
                    else startActivity(Intent(this@MainActivity, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_MAGNET, r.magnet)
                        .putExtra(PlayerActivity.EXTRA_TITLE, a.anime.title)
                        .putExtra(PlayerActivity.EXTRA_EPISODE, episode)
                        .putExtra(PlayerActivity.EXTRA_ANIME_ID, a.anime.id)
                        .putExtra(PlayerActivity.EXTRA_RELEASE, r.title)
                        .putExtra(PlayerActivity.EXTRA_DUB, dubbed))
                }
                val b = dialog().setTitle(if (onlySecondary) "$heading — sem PT-BR, só português de Portugal" else heading)
                    .setItems(labels) { _, which ->
                        val r = shown[which]
                        if (r.secondary) confirmPortugal(r) { play(r) } else play(r)
                    }
                    .setNegativeButton("Fechar", null)
                if (onlySecondary && !dubbed) b.setNeutralButton("Buscar dublado em inglês") { _, _ -> searchNyaaNow(a, episode, Nyaa.Lang.EN_DUB) }
                b.show()
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

    /** Legenda de Portugal (ou sem dizer qual): avisa a diferença antes de assistir. */
    internal fun confirmPortugal(r: Nyaa.Result, go: () -> Unit) {
        val unknown = !r.note.contains("Portugal")
        val first = if (unknown)
            "Este lançamento tem legenda em português, mas não diz se é do Brasil ou de Portugal. Pode ser de Portugal."
        else "Este lançamento não tem legenda do Brasil: a legenda é em português de Portugal."
        dialog().setTitle(if (unknown) "Português: Brasil ou Portugal?" else "Legenda em português de Portugal")
            .setMessage("$first\n\nDá para entender tudo, mas algumas palavras e o jeito de falar são diferentes, por exemplo:\n" +
                "• \"ecrã\" em vez de \"tela\", \"telemóvel\" em vez de \"celular\", \"autocarro\" em vez de \"ônibus\"\n" +
                "• \"tu\" e \"vós\" no lugar de \"você\" e \"vocês\"\n" +
                "• \"estou a fazer\" em vez de \"estou fazendo\"\n\n${r.title}")
            .setPositiveButton("Assistir mesmo assim") { _, _ -> go() }
            .setNegativeButton("Voltar", null)
            .show()
    }

    /** Tamanho do Nyaa ("1.4 GiB", "650.2 MiB") passa do limite de downloads? */
    internal fun tooBig(size: String): Boolean {
        val m = Regex("""([\d.]+)\s*GiB""").find(size) ?: return false
        return (m.groupValues[1].toDoubleOrNull() ?: 0.0) * 1073741824.0 > Downloads.cap(this)
    }

    /**
     * Abre o magnet no Amnis e acompanha de verdade: a janela de espera só some quando o
     * Amnis assume a tela (o Android pausa este app). Se isso não acontecer em alguns
     * segundos, avisa e oferece tentar de novo.
     */
    internal fun openInAmnis(r: Nyaa.Result, episode: Int) {
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

    internal fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
            toast("Nenhum app desta TV abre esse link.")
        }
    }

    // ---------- ajustes ----------

    internal fun openSettings() {
        val options = arrayOf(
            "Atraso do legendado em relação ao Japão: ${prefs.offsetMinutes} min",
            "Mostrar: ${if (prefs.onlyCrunchyroll) "só Crunchyroll" else "todos os serviços"}",
            "Player dos episódios do Nyaa: ${if (prefs.useAmnis) "Amnis" else "Animes TV"}",
            "Animes do Crunchyroll: ${if (prefs.crViaNyaa) "buscar no Nyaa (Crunchyroll fora do ar)" else "abrir no Crunchyroll"}",
            "Modo de vídeo do player: ${videoModeName(prefs.videoMode)}",
            "Downloads: ${Downloads.count(this)} episódio(s), ${Downloads.human(Downloads.used(this))} de ${Downloads.capLabel(this)} • ${Downloads.locationName(this)}",
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
                3 -> {
                    prefs.crViaNyaa = !prefs.crViaNyaa
                    HomeWorker.runNow(this)
                    toast(if (prefs.crViaNyaa) "Animes do Crunchyroll vão ser buscados no Nyaa, como os outros"
                        else "Animes do Crunchyroll voltam a abrir no Crunchyroll")
                }
                4 -> {
                    val labels = (0..3).map { videoModeName(it) }.toTypedArray()
                    dialog().setTitle("Modo de vídeo").setSingleChoiceItems(labels, prefs.videoMode) { d, which ->
                        prefs.videoMode = which
                        d.dismiss()
                        toast("Modo de vídeo: ${videoModeName(which)}")
                    }.show()
                }
                5 -> showDownloads()
                6 -> {
                    HomeWorker.runNow(this)
                    toast("Publicando… confira o resultado em Diagnóstico daqui a alguns segundos.")
                }
                7 -> showDiagnostics()
                8 -> checkUpdate()
            }
        }.show()
    }

    // ---------- downloads: local e limite ----------

    internal fun showDownloads() {
        val ok = Downloads.available(this)
        val mode = if (!Downloads.usesExternal(this)) "" else if (prefs.downloadDirect)
            "Modo: direto no disco (SSD)\n" else "Modo: memória interna primeiro, depois o disco (HD/pendrive)\n"
        val msg = "Local: ${Downloads.locationName(this)}\nLimite: ${Downloads.capLabel(this)}\n" + mode +
            (if (ok) "Ocupado agora: ${Downloads.human(Downloads.used(this))} em ${Downloads.count(this)} episódio(s)."
            else "O pendrive escolhido não está conectado.") +
            "\n\nQuando um novo download não cabe no limite, os episódios usados há mais tempo são apagados primeiro."
        dialog().setTitle("Downloads").setMessage(msg)
            .setPositiveButton("Local e limite") { _, _ -> chooseDownloadLocation() }
            .setNeutralButton("Apagar todos") { _, _ ->
                Downloads.clearAll(this)
                toast("Downloads apagados.")
            }
            .setNegativeButton("Fechar", null).show()
    }

    internal fun chooseDownloadLocation() {
        val vols = Downloads.volumes(this)
        val current = prefs.downloadPath
        val labels = vols.map { "${it.name}: ${Downloads.human(it.free)} livres de ${Downloads.human(it.total)}" }.toTypedArray()
        val sel = vols.indexOfFirst { (if (it.removable) it.path.absolutePath else null) == current }
        dialog().setTitle("Onde guardar os downloads").setSingleChoiceItems(labels, sel) { d, which ->
            d.dismiss()
            val v = vols[which]
            val newPath = if (v.removable) v.path.absolutePath else null
            if (newPath == current) { chooseCap(v); return@setSingleChoiceItems }
            val n = Downloads.count(this)
            val apply = {
                Downloads.clearAll(this)
                prefs.downloadPath = newPath
                chooseCap(v)
            }
            if (n == 0) apply() else dialog().setTitle("Trocar o local")
                .setMessage("Os $n episódio(s) guardados em ${Downloads.locationName(this)} (${Downloads.human(Downloads.used(this))}) serão apagados. Continuar?")
                .setPositiveButton("Trocar") { _, _ -> apply() }
                .setNegativeButton("Cancelar", null).show()
        }.setNegativeButton("Voltar", null).show()
    }

    internal fun chooseCap(v: Downloads.Volume) {
        // Só oferece limites que cabem no espaço livre (somado ao que os downloads já ocupam).
        val room = v.free + Downloads.used(this)
        val options = intArrayOf(1, 2, 3, 5, 10, 15, 20).filter { it * 1073741824L <= room - 200L * 1024 * 1024 }
        if (options.isEmpty()) {
            toast("Esse local tem pouco espaço livre (${Downloads.human(v.free)}).")
            return
        }
        val labels = options.map { "$it GB" }.toTypedArray()
        dialog().setTitle("Limite dos downloads em ${v.name}")
            .setSingleChoiceItems(labels, options.indexOf(prefs.downloadCapGb)) { d, which ->
                prefs.downloadCapGb = options[which]
                // Se o limite diminuiu, já apaga os mais antigos até caber.
                Downloads.makeRoom(this, 0, java.io.File(""))
                d.dismiss()
                toast("Downloads em ${v.name}, até ${options[which]} GB.")
                if (v.removable) chooseMode()
            }.show()
    }

    /** Disco externo: baixar direto nele (SSD) ou na memória interna primeiro (HD/pendrive). */
    internal fun chooseMode() {
        val labels = arrayOf(
            "HD ou pendrive: baixa na memória interna da TV e move para o disco quando termina",
            "SSD: baixa direto no disco (não usa a memória interna)")
        dialog().setTitle("Que tipo de disco é?")
            .setSingleChoiceItems(labels, if (prefs.downloadDirect) 1 else 0) { d, which ->
                prefs.downloadDirect = which == 1
                d.dismiss()
            }.show()
    }

    internal fun chooseOffset() {
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

    internal fun showDiagnostics() {
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
            appendLine("Modo de vídeo do player: ${prefs.videoMode} (0 = hardware, 1 = hardware com cópia, 2 = software)")
            appendLine("Downloads: ${Downloads.count(this@MainActivity)} episódio(s), ${Downloads.human(Downloads.used(this@MainActivity))}")
            appendLine("Amnis (torrent): ${if (Amnis.isInstalled(this@MainActivity)) "instalado" else "não instalado"}")
            appendLine("Forma de abrir anime: ${prefs.openMethod ?: CrunchyrollLauncher.DEFAULT_SERIES_METHOD + " (padrão)"}")
            appendLine("Forma de abrir episódio: ${prefs.episodeMethod ?: CrunchyrollLauncher.DEFAULT_EPISODE_METHOD + " (padrão)"}")
            appendLine("Códigos de episódio vêm de: ${CrunchyrollApi.lastSource.ifEmpty { "nenhuma busca ainda" }}")
            appendLine("Favoritos: ${prefs.favorites.size}")
            appendLine("Tela inicial: ${prefs.homeStatus}")
            if (loadError != null) appendLine("Último erro de carregamento: $loadError")
        }
        val b = dialog().setTitle("Diagnóstico").setMessage(text).setPositiveButton("OK", null)
        PlayerLog.last(this)?.let { log -> b.setNeutralButton("Registro do player") { _, _ -> showPlayerLog(log, crashed = false) } }
        b.show()
    }

    internal fun videoModeName(m: Int) = when (m) {
        0 -> "hardware (padrão)"
        1 -> "hardware com cópia"
        2 -> "só processador (mais pesado)"
        else -> "leve: vídeo direto, legenda simples"
    }

    /** Mostra o registro do player (as últimas linhas, que dizem onde parou). */
    internal fun showPlayerLog(text: String, crashed: Boolean, note: String = "") {
        val tail = text.lines().filter { it.isNotBlank() }.takeLast(45).joinToString("\n")
        val intro = if (crashed) "O player fechou inesperadamente na última vez. Últimas etapas registradas:\n\n" else ""
        dialog().setTitle(if (crashed) "O player fechou" else "Registro do player")
            .setMessage(intro + tail + note)
            .setPositiveButton("OK") { _, _ -> if (crashed) PlayerLog.dismiss(this) }
            .setOnCancelListener { if (crashed) PlayerLog.dismiss(this) }
            .show()
    }

    internal fun checkUpdate() {
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

    internal fun dialog() = TvDialog(this)

    internal fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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

    /** Altura do pôster em pixels (celular: proporcional à coluna); 0 = a do layout (TV). */
    var posterHeight = 0

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
        if (posterHeight > 0) v.findViewById<ImageView>(R.id.poster).layoutParams.height = posterHeight
        // Cantos arredondados também na imagem (não só no fundo).
        v.findViewById<View>(R.id.posterBox).clipToOutline = true
        v.setOnFocusChangeListener { view, hasFocus ->
            val s = if (hasFocus) 1.08f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(120).start()
            view.elevation = if (hasFocus) 12f else 0f
        }
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        holder.time.text = a.badge ?: Instant.ofEpochMilli(a.releaseMillis(prefs.offsetMinutes))
            .atZone(ZoneId.systemDefault()).format(timeFmt)
        holder.time.visibility = if (holder.time.text.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
        holder.ep.text = a.episode.toString()
        holder.ep.visibility = if (a.episode > 0) View.VISIBLE else View.INVISIBLE
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
