package com.idelmiro.animestv

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private const val BG = 0xFF2B2B2E.toInt()
/** Texto escuro sobre o branco do foco. */
private const val INK = 0xFF16161A.toInt()
private const val ACCENT = 0xFFF47521.toInt()
private const val MUTED = 0xFF9AA4B0.toInt()

/** Medidas pensadas para a TV (960 dp de largura); no celular tudo encolhe junto. */
internal class Ui(private val act: MainActivity) {
    private val dm = act.resources.displayMetrics
    val f = (act.resources.configuration.screenWidthDp / 960f).coerceIn(0.55f, 1f)
    fun dp(v: Float) = (v * f * dm.density).toInt()
    fun dp(v: Int) = dp(v.toFloat())
    fun sp(v: Float) = v * f

    fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false, lines: Int = 1) = TextView(act).apply {
        textSize = sp(size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    fun round(r: Float, color: Int) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(color) }

    fun clip(v: View, r: Float) {
        val rad = dp(r).toFloat()
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, rad)
        }
        v.clipToOutline = true
    }

    /** Borda branca quando o item está em foco (o "selecionado" do controle). */
    fun focusRing(r: Float): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = dp(r).toFloat(); setStroke(dp(3), Color.WHITE); setColor(Color.TRANSPARENT)
        })
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    fun growOnFocus(v: View, scale: Float = 1.07f) {
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(130).start()
            view.elevation = if (has) dp(10).toFloat() else 0f
        }
    }

    /** Botão arredondado: [primary] = branco; os outros ficam translúcidos e viram brancos no foco. */
    fun pill(label: String, primary: Boolean): Button = Button(act).apply {
        text = label
        isAllCaps = false
        textSize = sp(if (primary) 17f else 18f)
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        defaultFocusHighlightEnabled = false
        minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
        val h = dp(46)
        setPadding(if (primary) dp(24) else 0, 0, if (primary) dp(24) else 0, 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = h / 2f; setColor(Color.WHITE) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = h / 2f; setColor(if (primary) 0xE6FFFFFF.toInt() else 0x33FFFFFF) })
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(INK, if (primary) INK else Color.WHITE)))
        layoutParams = LinearLayout.LayoutParams(if (primary) ViewGroup.LayoutParams.WRAP_CONTENT else h, h).apply { marginEnd = dp(14) }
        isFocusable = true
        growOnFocus(this, 1.06f)
    }
}

/** Tela inicial: destaque (em alta no AniList) e as linhas, como num app de streaming. */
internal class HomeView(private val act: MainActivity) {
    private val ui = Ui(act)
    private val main = Handler(Looper.getMainLooper())
    val view = ScrollView(act)
    private val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val shows = HashMap<Int, AniList.Show>()
    private val zone: ZoneId = ZoneId.systemDefault()

    // destaque
    private val heroH = (act.resources.displayMetrics.heightPixels * 0.88f).toInt()
    private val hero = FrameLayout(act)
    private val heroImg = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val heroLabel = ui.text(15f, ACCENT, true)
    private val heroTitle = ui.text(36f, Color.WHITE, true, 2)
    private val heroMeta = ui.text(16f, 0xFFE6E9EE.toInt(), true)
    private val heroDesc = ui.text(15f, 0xFFB9C2CC.toInt(), false, 2)
    private val heroAvail = ui.text(15f, Color.WHITE, true)
    val pill = ui.pill("☰   Início", true)
    private val btnPlay = ui.pill("▶   Ver episódios", true)
    private val btnFav = ui.pill("+", false)
    private val btnNext = ui.pill("›", false)
    private val dots = LinearLayout(act).apply { gravity = Gravity.CENTER }
    private var trending: List<AniList.Show> = emptyList()
    private var heroIndex = 0
    private var lastKey = 0L

    // linhas
    private val rowContinue = Row("Continuar assistindo")
    private val rowFav = Row("Meus favoritos")
    private val rowWeek = Row("Esta semana")
    private val rowTop = Row("Top 10 da temporada")
    private val rowNext = Row("Chegando na próxima temporada")
    private val rowPast = Row("Temporadas anteriores")

    private inner class Row(title: String) {
        val section = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val titleView = ui.text(21f, Color.WHITE, true).apply { text = title; setPadding(ui.dp(48), ui.dp(14), ui.dp(48), 0) }
        val items = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(48), ui.dp(14), ui.dp(48), ui.dp(18))
            clipChildren = false; clipToPadding = false
        }
        init {
            section.addView(titleView)
            val hs = HorizontalScrollView(act).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipChildren = false; clipToPadding = false
            }
            hs.addView(items)
            section.addView(hs)
        }
        fun set(views: List<View>) {
            items.removeAllViews()
            views.forEach { items.addView(it) }
            section.visibility = if (views.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    init {
        view.isFillViewport = true
        view.isVerticalScrollBarEnabled = false
        view.setBackgroundColor(BG)
        view.addView(content)
        buildHero()
        // A primeira linha sobe por cima do fim do destaque (como na Apple TV).
        content.addView(rowContinue.section, LinearLayout.LayoutParams(-1, -2).apply { topMargin = -ui.dp(150) })
        for (r in listOf(rowFav, rowWeek, rowTop, rowNext, rowPast)) content.addView(r.section, LinearLayout.LayoutParams(-1, -2))
        content.addView(View(act), LinearLayout.LayoutParams(1, ui.dp(40)))
    }

    private fun buildHero() {
        hero.addView(heroImg, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF52B2B2E.toInt(), 0xB32B2B2E.toInt(), 0x002B2B2E))
        }, FrameLayout.LayoutParams((act.resources.displayMetrics.widthPixels * 0.66f).toInt(), -1))
        hero.addView(View(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(BG, 0x002B2B2E))
        }, FrameLayout.LayoutParams(-1, (heroH * 0.5f).toInt(), Gravity.BOTTOM))

        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(heroLabel)
        col.addView(heroTitle, lp(top = 10))
        col.addView(heroMeta, lp(top = 12))
        col.addView(heroDesc, lp(top = 12))
        col.addView(heroAvail, lp(top = 12))
        val buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        buttons.addView(btnPlay); buttons.addView(btnFav); buttons.addView(btnNext)
        col.addView(buttons, lp(top = 18))
        col.clipChildren = false
        hero.addView(col, FrameLayout.LayoutParams((act.resources.displayMetrics.widthPixels * 0.52f).toInt(), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = ui.dp(48); bottomMargin = ui.dp(190)
        })
        hero.addView(dots, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(165) })
        hero.addView(pill, FrameLayout.LayoutParams(-2, ui.dp(46), Gravity.TOP or Gravity.START).apply { leftMargin = ui.dp(28); topMargin = ui.dp(24) })
        hero.clipChildren = false
        content.addView(hero, LinearLayout.LayoutParams(-1, heroH))

        heroTitle.text = "Animes TV"
        heroLabel.text = "CARREGANDO OS DESTAQUES…"
        btnPlay.setOnClickListener { trending.getOrNull(heroIndex)?.let { act.openShow(it) } }
        btnFav.setOnClickListener {
            trending.getOrNull(heroIndex)?.let { s ->
                act.prefs.toggleFav(s.anime.id)
                act.toast(if (act.prefs.isFav(s.anime.id)) "Adicionado aos favoritos" else "Removido dos favoritos")
                showHero(heroIndex)
                refreshLocal()
                HomeWorker.runNow(act)
            }
        }
        btnNext.setOnClickListener { if (trending.isNotEmpty()) showHero((heroIndex + 1) % trending.size) }
        pill.setOnClickListener { act.openMenu() }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(top) }

    /** Primeiro foco da tela. */
    fun focusStart() { btnPlay.requestFocus() }

    fun onKey() { lastKey = System.currentTimeMillis() }

    private val rotate = object : Runnable {
        override fun run() {
            if (view.visibility == View.VISIBLE && trending.size > 1 && System.currentTimeMillis() - lastKey > 12_000)
                showHero((heroIndex + 1) % trending.size)
            main.postDelayed(this, 12_000)
        }
    }

    private fun showHero(i: Int) {
        val s = trending.getOrNull(i) ?: return
        heroIndex = i
        val a = s.anime
        ImageLoader.load(a.banner ?: a.coverXl ?: a.cover, heroImg)
        heroLabel.text = "#${i + 1}  EM ALTA NO ANILIST"
        heroTitle.text = a.title
        heroMeta.text = listOfNotNull(formatName(s.format), s.genres.take(2).joinToString(" · ").ifBlank { null },
            s.scoreLabel, s.season).joinToString("   ·   ")
        heroDesc.text = s.description ?: ""
        heroDesc.visibility = if (s.description.isNullOrBlank()) View.GONE else View.VISIBLE
        val cr = a.crunchyrollUrl != null && !act.prefs.crViaNyaa
        heroAvail.text = when {
            s.aired <= 0 -> if (a.trailer != null) "Estreia em breve · trailer disponível" else "Estreia em breve"
            cr -> "Ep. ${s.aired} no Crunchyroll"
            else -> "Ep. ${s.aired} · busca com legenda PT-BR"
        }
        btnPlay.text = when {
            s.aired > 0 -> "▶   Ver episódios"
            a.trailer != null -> "▶   Ver trailer"
            else -> "Ver detalhes"
        }
        btnFav.text = if (act.prefs.isFav(a.id)) "♥" else "+"
        dots.removeAllViews()
        for (k in trending.indices) dots.addView(View(act).apply {
            background = ui.round(4f, if (k == i) Color.WHITE else 0x66FFFFFF)
        }, LinearLayout.LayoutParams(if (k == i) ui.dp(22) else ui.dp(7), ui.dp(7)).apply { marginStart = ui.dp(4); marginEnd = ui.dp(4) })
    }

    private fun formatName(f: String?) = when (f) {
        "TV", "TV_SHORT" -> "Série"; "ONA" -> "Série web"; "MOVIE" -> "Filme"; "OVA" -> "OVA"; "SPECIAL" -> "Especial"; else -> null
    }

    // ---------- carregamento ----------

    private suspend fun <T> net(block: () -> T): T = withContext(Dispatchers.IO) {
        var attempt = 0
        var result: T? = null
        while (result == null) {
            try { result = block() } catch (e: java.io.IOException) {
                if (++attempt > 6) throw e
                delay(minOf(15_000L, 3_000L * attempt))
            }
        }
        result!!
    }

    private fun launch(block: suspend () -> Unit): Job = act.scope.launch {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            PlayerLog.add("home: ${e.message}")
        }
    }

    fun start() {
        main.removeCallbacks(rotate)
        main.postDelayed(rotate, 12_000)
        launch {
            val t = net { AniList.fetchTrending(12) }
            // Com banner primeiro (o destaque fica mais bonito), no máximo 10.
            trending = (t.filter { it.anime.banner != null } + t.filter { it.anime.banner == null }).take(10)
            trending.forEach { shows[it.anime.id] = it }
            showHero(0)
        }
        refreshLocal()
        loadWeek()
        loadSeasons()
        buildPast()
    }

    fun stop() { main.removeCallbacks(rotate) }

    /** Continuar assistindo e favoritos (mudam sem precisar da internet). */
    fun refreshLocal() {
        val saved = Downloads.saved(act).sortedByDescending { it.lastActivity }.take(15)
        val favs = act.prefs.favorites
        buildContinue(saved)
        buildFavorites(favs)
        val missing = (saved.map { it.animeId } + favs).filter { it > 0 && it !in shows }
        if (missing.isNotEmpty()) launch {
            net { AniList.fetchByIds(missing) }.forEach { shows[it.anime.id] = it }
            buildContinue(saved)
            buildFavorites(favs)
        }
        if (trending.isNotEmpty()) btnFav.text = if (act.prefs.isFav(trending[heroIndex].anime.id)) "♥" else "+"
    }

    private fun buildContinue(saved: List<Downloads.Saved>) {
        rowContinue.set(saved.map { sv ->
            val s = shows[sv.animeId]
            val stop = if (sv.pos > 30) " · parou em ${act.clock(sv.pos)}" else ""
            val state = (if (sv.complete) "Baixado · ${Downloads.human(sv.size)}" else "${sv.percent}% baixado") +
                if (sv.dub) " · dublado" else ""
            landscape(s?.anime?.banner ?: s?.anime?.cover, sv.title.ifBlank { s?.anime?.title ?: sv.release },
                "Ep. ${sv.episode}$stop", state, if (sv.complete) null else sv.percent, null) { act.savedActions(sv) }
        })
    }

    private fun buildFavorites(favs: Set<Int>) {
        val now = System.currentTimeMillis() / 1000
        val list = favs.mapNotNull { shows[it] }.sortedWith(compareBy<AniList.Show> { if (it.status == "RELEASING") 0 else 1 }
            .thenBy { if (it.nextAiringAt > 0) it.nextAiringAt else Long.MAX_VALUE })
        rowFav.set(list.map { s ->
            val sub = when {
                s.status == "NOT_YET_RELEASED" -> "Em breve"
                s.status == "RELEASING" && s.nextAiringAt > 0 -> "Ep. ${s.aired} · próximo ${weekday(s.nextAiringAt)}"
                s.total > 0 -> "${s.total} episódios"
                else -> s.season ?: ""
            }
            val novo = s.status == "RELEASING" && s.aired > 0 && s.nextAiringAt > 0 && now - (s.nextAiringAt - 7 * 86400) < 3 * 86400
            poster(s.anime.cover, s.anime.title, sub, if (novo) "Novo ep." else null, null) { act.openShow(s) }
        })
    }

    private fun weekday(epoch: Long): String =
        arrayOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom")[Instant.ofEpochSecond(epoch).atZone(zone).dayOfWeek.value - 1]

    private fun loadWeek() = launch {
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val list = act.cache[monday] ?: run {
            val off = act.prefs.offsetMinutes * 60L
            val start = monday.atStartOfDay(zone).toEpochSecond() - off
            val end = monday.plusDays(7).atStartOfDay(zone).toEpochSecond() - off
            net { AniList.fetchSchedule(start, end) }.also { act.cache[monday] = it }
        }
        buildWeek(monday, list)
    }

    private fun buildWeek(monday: LocalDate, all: List<Airing>) {
        val favs = act.prefs.favorites
        val list = all.filter { !act.prefs.onlyCrunchyroll || it.anime.crunchyrollUrl != null }
        val today = LocalDate.now()
        val names = arrayOf("Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado", "Domingo")
        rowWeek.titleView.text = "Esta semana   ·   ${monday.dayOfMonth}/${monday.monthValue} – ${monday.plusDays(6).dayOfMonth}/${monday.plusDays(6).monthValue}"
        rowWeek.set((0 until 7).map { i ->
            val d = monday.plusDays(i.toLong())
            val day = list.filter { act.dayOf(it) == d }.sortedBy { if (it.anime.id in favs) 0 else 1 }
            val nFav = day.count { it.anime.id in favs }
            val info = "${day.size} episódios" + if (nFav > 0) " · $nFav ♥" else ""
            dayTile(if (d == today) "Hoje" else names[i], "%02d/%02d".format(d.dayOfMonth, d.monthValue), info,
                day.mapNotNull { it.anime.cover }.distinct().take(4), d == today) { act.openWeekDay(d) }
        })
    }

    private fun loadSeasons() {
        val cur = AniList.lastSeasons(LocalDate.now(), 1)[0]
        val nxt = AniList.nextSeason(cur)
        launch {
            val top = net { AniList.fetchSeasonShows(cur, 10) }
            top.forEach { shows[it.anime.id] = it }
            rowTop.titleView.text = "Top 10 do ${cur.label}"
            rowTop.set(top.mapIndexed { i, s ->
                poster(s.anime.cover, s.anime.title, s.genres.firstOrNull() ?: "", null, i + 1) { act.openShow(s) }
            })
        }
        launch {
            val next = net { AniList.fetchSeasonShows(nxt, 15) }
            next.forEach { shows[it.anime.id] = it }
            rowNext.titleView.text = "Chegando no ${nxt.label}"
            val months = arrayOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")
            rowNext.set(next.map { s ->
                val quando = when {
                    s.aired > 0 -> "já estreou"
                    s.startMonth in 1..12 && s.startDay > 0 -> "estreia em %02d/%02d".format(s.startDay, s.startMonth)
                    s.startMonth in 1..12 -> "estreia em ${months[s.startMonth - 1]}"
                    else -> "estreia em breve"
                }
                landscape(s.anime.banner ?: s.anime.cover, s.anime.title, quando, null, null,
                    if (s.anime.trailer != null) "▶ Trailer" else null) { act.openShow(s) }
            })
        }
    }

    private fun buildPast() {
        val seasons = AniList.lastSeasons(LocalDate.now(), 4)
        val colors = intArrayOf(0xFFB5541C.toInt(), 0xFF2F6B3F.toInt(), 0xFF2B4F86.toInt())
        val tiles = (1..3).map { i ->
            tile(seasons[i].label, seasons[i].months, colors[i - 1]) { act.openSeasonTab(i) }
        } + tile("Calendário da semana", "o que sai em cada dia", 0xFF3A3F4B.toInt()) { act.openWeekDay(LocalDate.now()) }
        rowPast.set(tiles)
    }

    // ---------- cartões ----------

    private fun cardBase(w: Int, h: Int, r: Float, onClick: () -> Unit): FrameLayout = FrameLayout(act).apply {
        layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = ui.dp(18) }
        background = ui.round(r, 0xFF3A3A3D.toInt())
        foreground = ui.focusRing(r)
        isFocusable = true; isClickable = true
        ui.clip(this, r)
        ui.growOnFocus(this)
        setOnClickListener { onClick() }
    }

    private fun image(url: String?) = ImageView(act).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        ImageLoader.load(url, this)
    }

    private fun shade() = View(act).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
    }

    private fun badge(text: String, color: Int = 0x99000000.toInt(), textColor: Int = Color.WHITE) = ui.text(12f, textColor, true).apply {
        this.text = text
        background = ui.round(6f, color)
        setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
    }

    /** Cartão deitado: Continuar assistindo e Próxima temporada. */
    private fun landscape(img: String?, title: String, line1: String, line2: String?, progress: Int?, tag: String?, onClick: () -> Unit): View {
        val w = ui.dp(232); val h = ui.dp(130)
        val card = cardBase(w, h, 12f, onClick)
        card.addView(image(img), FrameLayout.LayoutParams(-1, -1))
        card.addView(shade(), FrameLayout.LayoutParams(-1, (h * 0.75f).toInt(), Gravity.BOTTOM))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(10)) }
        col.addView(ui.text(14f, Color.WHITE, true).apply { text = title })
        col.addView(ui.text(12f, 0xFFDFE4EA.toInt(), true).apply { text = line1 }, lp(top = 4))
        if (progress != null) {
            val track = FrameLayout(act).apply { background = ui.round(2f, 0x40FFFFFF) }
            track.addView(View(act).apply { background = ui.round(2f, ACCENT) },
                FrameLayout.LayoutParams(((w - ui.dp(24)) * progress.coerceIn(0, 100) / 100f).toInt(), -1))
            col.addView(track, LinearLayout.LayoutParams(-1, ui.dp(4)).apply { topMargin = ui.dp(6) })
        }
        if (line2 != null) col.addView(ui.text(11f, 0xFFB9C2CC.toInt()).apply { text = line2 }, lp(top = 5))
        card.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        if (tag != null) card.addView(badge(tag), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = ui.dp(10); rightMargin = ui.dp(10)
        })
        return card
    }

    /** Capa em pé, com o nome embaixo: Favoritos e Top 10 (com o número grande). */
    private fun poster(img: String?, title: String, sub: String, tag: String?, rank: Int?, onClick: () -> Unit): View {
        // 6 capas ocupam a mesma largura que 4 cartões de "Continuar assistindo".
        val w = POSTER_W; val h = (POSTER_W * 1.43f).toInt()
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(w, -2).apply { marginEnd = ui.dp(18) }
            clipChildren = false
        }
        val card = cardBase(w, h, 12f, onClick).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = 0 }
        card.addView(image(img), FrameLayout.LayoutParams(-1, -1))
        if (rank != null) {
            card.addView(shade(), FrameLayout.LayoutParams(-1, (h * 0.5f).toInt(), Gravity.BOTTOM))
            card.addView(ui.text(56f, Color.WHITE, true).apply {
                text = rank.toString()
                setShadowLayer(12f, 0f, 3f, 0xCC000000.toInt())
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = ui.dp(10); bottomMargin = ui.dp(4) })
        }
        if (tag != null) card.addView(badge(tag, ACCENT, 0xFF1A0E05.toInt()), FrameLayout.LayoutParams(-2, -2).apply {
            leftMargin = ui.dp(8); topMargin = ui.dp(8)
        })
        box.addView(card)
        box.addView(ui.text(13f, Color.WHITE, true, 2).apply { text = title }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        if (sub.isNotBlank()) box.addView(ui.text(11f, MUTED).apply { text = sub }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
        return box
    }

    /** Dia da semana com uma colagem das capas dos animes do dia. */
    private val POSTER_W = (ui.dp(232) * 4 + ui.dp(18) * 3 - ui.dp(18) * 5) / 6

    private fun dayTile(name: String, date: String, info: String, covers: List<String>, today: Boolean, onClick: () -> Unit): View {
        val w = POSTER_W; val h = (POSTER_W * 1.7f).toInt()
        val card = cardBase(w, h, 14f, onClick)
        card.background = ui.round(14f, if (today) 0xFF2A2F52.toInt() else 0xFF1A2140.toInt())
        // Colagem das capas na metade de cima; o nome do dia fica livre embaixo.
        val cw = (w * 0.38f).toInt(); val ch = (cw * 1.4f).toInt()
        val spots = listOf(intArrayOf((w * 0.08f).toInt(), (w * 0.07f).toInt()), intArrayOf((w * 0.52f).toInt(), (w * 0.11f).toInt()),
            intArrayOf((w * 0.14f).toInt(), (w * 0.42f).toInt()), intArrayOf((w * 0.56f).toInt(), (w * 0.48f).toInt()))
        covers.forEachIndexed { k, url ->
            val s = spots[k]
            val img = image(url).apply { elevation = ui.dp(4).toFloat() }
            ui.clip(img, 6f)
            card.addView(img, FrameLayout.LayoutParams(cw, ch).apply { leftMargin = s[0]; topMargin = s[1] })
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, ui.dp(10), ui.dp(12)) }
        if (today) col.addView(badge("HOJE", ACCENT, 0xFF1A0E05.toInt()), lp())
        col.addView(ui.text(21f, Color.WHITE, true).apply { text = name }, lp(top = 4))
        col.addView(ui.text(14f, 0xD9FFFFFF.toInt(), true).apply { text = date }, lp(top = 2))
        col.addView(ui.text(11f, 0xFFB9C2CC.toInt(), false, 1).apply { text = info }, lp(top = 4))
        card.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        if (today) card.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = ui.dp(14).toFloat(); setStroke(ui.dp(3), Color.WHITE) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = ui.dp(14).toFloat(); setStroke(ui.dp(3), ACCENT) })
        }
        return card
    }

    /** Bloco colorido: temporadas anteriores. */
    private fun tile(title: String, sub: String, color: Int, onClick: () -> Unit): View {
        val card = cardBase(ui.dp(232), ui.dp(130), 14f, onClick)
        card.background = ui.round(14f, color)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), 0, ui.dp(12), ui.dp(14)) }
        col.addView(ui.text(20f, Color.WHITE, true).apply { text = title })
        col.addView(ui.text(12f, 0xCCFFFFFF.toInt(), true).apply { text = sub }, lp(top = 4))
        card.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return card
    }
}

/** Menu lateral (como o da Apple TV): abre com ◀ na borda esquerda, com o botão do canto ou com Menu. */
internal class SideMenu(private val act: MainActivity) {
    private val ui = Ui(act)
    val view = FrameLayout(act).apply {
        setBackgroundColor(0x99000000.toInt())
        visibility = View.GONE
        isClickable = true
        setOnClickListener { hide() }
    }
    private val clock = ui.text(17f, 0xFFB9C2CC.toInt())
    private val items = ArrayList<Pair<MainActivity.Screen?, View>>()
    val isOpen get() = view.visibility == View.VISIBLE

    init {
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(26f, 0xF24A4A4F.toInt())
            setPadding(ui.dp(12), ui.dp(20), ui.dp(12), ui.dp(16))
            elevation = ui.dp(16).toFloat()
            isClickable = true
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(18)) }
        head.addView(ui.text(19f, Color.WHITE, true).apply { text = "Animes TV" }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(clock)
        panel.addView(head)
        add(panel, "Buscar", R.drawable.ic_menu_search, MainActivity.Screen.SEARCH) { act.showScreen(MainActivity.Screen.SEARCH) }
        add(panel, "Início", R.drawable.ic_menu_home, MainActivity.Screen.HOME) { act.showScreen(MainActivity.Screen.HOME) }
        add(panel, "Semana", R.drawable.ic_menu_week, MainActivity.Screen.WEEK) { act.openWeekDay(LocalDate.now()) }
        add(panel, "Temporadas", R.drawable.ic_menu_seasons, MainActivity.Screen.SEASONS) { act.openSeasonTab(0) }
        add(panel, "Baixados", R.drawable.ic_menu_downloads, null) { act.showSaved() }
        add(panel, "Ajustes", R.drawable.ic_menu_settings, null) { act.openSettings() }
        view.addView(panel, FrameLayout.LayoutParams(ui.dp(250), -2).apply { leftMargin = ui.dp(20); topMargin = ui.dp(20) })
    }

    private fun add(panel: LinearLayout, label: String, icon: Int, screen: MainActivity.Screen?, action: () -> Unit) {
        val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(INK, Color.WHITE))
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ui.round(22f, 0xFFF2F3F5.toInt()))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            setOnClickListener { hide(); action() }
        }
        row.addView(ImageView(act).apply {
            setImageResource(icon)
            imageTintList = colors
            isDuplicateParentStateEnabled = true
        }, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
        row.addView(ui.text(18f, Color.WHITE, true).apply {
            text = label
            setTextColor(colors)
            isDuplicateParentStateEnabled = true
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(16) })
        panel.addView(row, LinearLayout.LayoutParams(-1, ui.dp(46)).apply { topMargin = ui.dp(4) })
        // ◀ ▶ não saem do menu; ▼ no último e ▲ no primeiro ficam onde estão.
        row.id = View.generateViewId()
        row.nextFocusLeftId = row.id
        row.nextFocusRightId = row.id
        items.lastOrNull()?.second?.let { prev -> prev.nextFocusDownId = row.id; row.nextFocusUpId = prev.id }
        row.nextFocusDownId = row.id
        if (items.isEmpty()) row.nextFocusUpId = row.id
        items += screen to row
    }

    fun show(current: MainActivity.Screen) {
        clock.text = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        // Enquanto o menu está aberto, nada atrás dele recebe o foco.
        act.blockBackground(true)
        view.visibility = View.VISIBLE
        view.bringToFront()
        (items.firstOrNull { it.first == current }?.second ?: items[1].second).requestFocus()
    }

    fun hide() {
        view.visibility = View.GONE
        act.blockBackground(false)
        act.restoreFocus()
    }
}

/** Busca por nome no AniList; ao escolher, abre os episódios como em qualquer outra tela. */
internal class SearchView(private val act: MainActivity) {
    private val ui = Ui(act)
    val view = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        setPadding(ui.dp(40), ui.dp(24), ui.dp(40), 0)
        visibility = View.GONE
    }
    val field = EditText(act).apply {
        hint = "Nome do anime (japonês ou inglês)"
        setHintTextColor(0xFF7D8794.toInt())
        setTextColor(Color.WHITE)
        textSize = ui.sp(20f)
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setPadding(ui.dp(22), 0, ui.dp(22), 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = ui.dp(26).toFloat(); setColor(0x1AFFFFFF); setStroke(ui.dp(3), Color.WHITE)
            })
            addState(intArrayOf(), ui.round(26f, 0x1AFFFFFF))
        }
    }
    private val status = ui.text(15f, MUTED, false, 2)
    private val adapter = AnimeAdapter(act.prefs) { act.onSeasonAnimeClick(it) }
    private var job: Job? = null
    private val main = Handler(Looper.getMainLooper())
    private val debounce = Runnable { run(field.text.toString()) }

    init {
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val menu = Ui(act).pill("☰", false).apply { setOnClickListener { act.openMenu() } }
        top.addView(menu)
        top.addView(field, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        view.addView(top)
        view.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14); bottomMargin = ui.dp(6) })
        val cols = if (Device.isTv(act)) 6 else 3
        val d = act.resources.displayMetrics.density
        adapter.posterHeight = (((act.resources.configuration.screenWidthDp - 80) * d / cols - 18 * d) * 1.42f).toInt()
        val grid = RecyclerView(act).apply {
            layoutManager = GridLayoutManager(act, cols)
            adapter = this@SearchView.adapter
            clipToPadding = false
            setPadding(0, 0, 0, ui.dp(30))
        }
        view.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        status.text = "Digite o nome e aperte Buscar no teclado (ou fale no microfone do controle)."
        field.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                main.removeCallbacks(debounce); run(field.text.toString()); true
            } else false
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                main.removeCallbacks(debounce)
                if ((s?.length ?: 0) >= 3) main.postDelayed(debounce, 900)
            }
        })
    }

    private fun run(q: String) {
        val text = q.trim()
        if (text.length < 2) return
        job?.cancel()
        status.text = "Buscando \"$text\" no AniList…"
        job = act.scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { AniList.search(text) }
                adapter.items = list.map { it.toAiring() }
                status.text = if (list.isEmpty()) "Nada encontrado para \"$text\"." else "${list.size} resultado(s) para \"$text\""
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Não consegui buscar (${e.message ?: e.javaClass.simpleName})."
            }
        }
    }

    fun focus() { field.requestFocus() }
}
