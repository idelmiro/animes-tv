package com.idelmiro.animestv

import android.app.Activity
import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.util.Locale

/**
 * Player do Animes TV: baixa o episódio por torrent (TorrentStream) e toca com o mpv,
 * que desenha as legendas ASS dos fansubs com o libass.
 *
 * Controle remoto: OK = pausar/continuar; ◀ ▶ = voltar/avançar 10 s;
 * ▲ = trocar legenda; ▼ = mostrar/esconder detalhes do download; Voltar = sair.
 */
class PlayerActivity : Activity(), MPVLib.EventObserver, MPVLib.LogObserver, SurfaceHolder.Callback {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var surface: SurfaceView
    private lateinit var panel: LinearLayout
    private lateinit var panelText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var osd: TextView
    private var torrent: TorrentStream? = null
    private var mpvReady = false
    private var hasSurface = false
    private var pendingUrl: String? = null
    private var started = false        // o vídeo já começou a tocar
    private var buffering = false      // mpv parado esperando dados
    private var paused = false
    private var position = 0L
    private var duration = 0L
    private var showDetails = false
    private var last: TorrentStream.Status? = null
    private var title = ""
    private var mode = 0
    private var vo = "gpu"
    private lateinit var subView: TextView
    private var lastDrops = 0
    private var suggested = false
    private var episode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)
        val magnet = intent.getStringExtra(EXTRA_MAGNET)
        val local = intent.getStringExtra(EXTRA_LOCAL_FILE)
        if (magnet == null && local == null) {
            finish()
            return
        }
        PlayerLog.begin(this, "$title — Ep. $episode")
        buildViews()
        PlayerLog.add("mpv: iniciando")
        startMpv()
        PlayerLog.add("mpv: pronto")
        if (local != null) {
            // Episódio já baixado: toca direto do arquivo, sem Nyaa e sem torrent.
            val f = File(local)
            f.parentFile?.let { Downloads.touch(it) }
            PlayerLog.add("player: tocando arquivo salvo ${f.absolutePath}")
            onTorrent(TorrentStream.Status(TorrentStream.Phase.COMPLETE, "Tocando o episódio salvo (sem usar a internet).",
                done = f.length(), total = f.length()))
            load(f.absolutePath)
        } else torrent = TorrentStream(applicationContext, magnet!!, episode,
            intent.getIntExtra(EXTRA_ANIME_ID, 0), title, intent.getStringExtra(EXTRA_RELEASE) ?: "",
            onStatus = { s -> ui.post { onTorrent(s) } },
            onReady = { url -> ui.post { load(url) } },
            onOversize = { size, count, bytes, answer -> ui.post { askOversize(size, count, bytes, answer) } }
        ).also { it.start() }
        updatePanel()
    }

    /** Tela cheia de verdade no celular (some a barra de navegação). */
    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------- tela ----------

    private fun buildViews() {
        val d = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        // Em TV 4K, desenhar o vídeo na resolução da tela pesa demais para a placa de vídeo.
        // O player desenha em no máximo 1920x1080 e a própria TV amplia a imagem.
        val dm = resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels)
        val h = minOf(dm.widthPixels, dm.heightPixels)
        if (w > 1920) surface.holder.setFixedSize(1920, h * 1920 / w)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding((20 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
        }
        spinner = ProgressBar(this).apply { isIndeterminate = true }
        panel.addView(spinner)
        panelText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding((16 * d).toInt(), 0, 0, 0)
        }
        panel.addView(panelText)
        root.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            leftMargin = (48 * d).toInt(); rightMargin = (48 * d).toInt()
        })

        subView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            paint.isFakeBoldText = true
            visibility = View.GONE
        }
        root.addView(subView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = (64 * d).toInt(); rightMargin = (64 * d).toInt(); bottomMargin = (40 * d).toInt()
        })

        osd = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(0xB3000000.toInt())
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            visibility = View.GONE
        }
        root.addView(osd, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = (32 * d).toInt(); bottomMargin = (32 * d).toInt()
        })
        buildTouchControls(root)
        setContentView(root)
        surface.holder.addCallback(this)
    }

    private fun onTorrent(s: TorrentStream.Status) {
        last = s
        if (s.phase == TorrentStream.Phase.ERROR) {
            spinner.visibility = View.GONE
            panel.visibility = View.VISIBLE
            panelText.text = "Não foi possível tocar.\n\n${s.message}\n\nAperte Voltar para sair."
            return
        }
        updatePanel()
    }

    /** Mostra a janela de carregamento com o que está acontecendo de verdade. */
    private fun updatePanel() {
        val s = last
        if (s?.phase == TorrentStream.Phase.ERROR) return
        val waiting = !started || buffering
        val show = waiting || showDetails
        panel.visibility = if (show) View.VISIBLE else View.GONE
        spinner.visibility = if (waiting) View.VISIBLE else View.GONE
        if (!show) return
        val sb = StringBuilder()
        sb.append("$title — Ep. $episode\n\n")
        when {
            s == null -> sb.append("Iniciando…")
            !started && s.phase == TorrentStream.Phase.DOWNLOADING -> sb.append("Carregando o começo do episódio…")
            buffering && started -> sb.append("Esperando chegar o próximo trecho do episódio…")
            else -> sb.append(s.message)
        }
        if (s != null && s.total > 0) {
            val pct = (s.done * 100 / s.total).toInt()
            sb.append("\n\nBaixado: $pct% de ${Downloads.human(s.total)}")
            if (s.phase == TorrentStream.Phase.DOWNLOADING || s.phase == TorrentStream.Phase.CONNECTING) {
                sb.append("\nConectado a ${s.peers} pessoa(s) (${s.seeds} com o arquivo completo)")
                sb.append("\nVelocidade: ${speed(s.downRate)}")
                if (s.upRate > 0) sb.append(" • enviando ${speed(s.upRate)}")
                if (duration > 0 && s.total > 0) {
                    val secs = s.ahead * duration / s.total
                    sb.append("\nJá baixado à frente: ~${time(secs)} de vídeo")
                }
            }
        }
        if (mpvReady && started && duration > 0) sb.append("\n\n${time(position)} / ${time(duration)}")
        if (mpvReady && started && showDetails) sb.append("\n").append(playbackStats())
        panelText.text = sb
    }

    /** Arquivo acima de 1 GB: pergunta antes, avisando o que será apagado. */
    private fun askOversize(size: Long, count: Int, bytes: Long, answer: (Boolean) -> Unit) {
        if (isFinishing) { answer(false); return }
        val saved = if (count > 0)
            "Para caber, os $count episódio(s) salvos (${Downloads.human(bytes)}) serão apagados."
        else "Não há outros episódios salvos para apagar."
        var answered = false
        val reply = { yes: Boolean -> if (!answered) { answered = true; answer(yes) } }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Arquivo maior que ${Downloads.capLabel(this)}")
            .setMessage("Este episódio tem ${Downloads.human(size)}, acima do limite de ${Downloads.capLabel(this)}.\n\n$saved\n\nBaixar mesmo assim?")
            .setPositiveButton("Baixar mesmo assim") { _, _ -> reply(true) }
            .setNegativeButton("Cancelar") { _, _ -> reply(false) }
            .setOnCancelListener { reply(false) }
            .show()
    }

    /** Números do player para entender travadas: decodificador, quadros perdidos, buffer. */
    private fun playbackStats(): String {
        fun str(p: String) = try { MPVLib.getPropertyString(p) } catch (_: Throwable) { null }
        fun int(p: String) = try { MPVLib.getPropertyInt(p) } catch (_: Throwable) { null }
        fun dbl(p: String) = try { MPVLib.getPropertyDouble(p) } catch (_: Throwable) { null }
        val hw = str("hwdec-current")?.takeIf { it.isNotBlank() && it != "no" } ?: "software"
        val vw = int("width"); val vh = int("height")
        val fps = dbl("estimated-vf-fps")?.let { String.format(Locale.US, "%.1f", it) } ?: "?"
        val cont = dbl("container-fps")?.let { String.format(Locale.US, "%.0f", it) } ?: "?"
        val drop = (int("frame-drop-count") ?: 0) + (int("decoder-frame-drop-count") ?: 0)
        val cache = dbl("demuxer-cache-duration")?.let { "${it.toInt()} s" } ?: "?"
        val osd = str("osd-dimensions/w")?.let { w -> "${w}x${str("osd-dimensions/h")}" } ?: "?"
        return "Vídeo ${vw}x$vh • decodificação: $hw • $fps de $cont quadros/s\n" +
            "Quadros perdidos: $drop • buffer: $cache • tela de desenho: $osd"
    }

    private val statsLogger = object : Runnable {
        override fun run() {
            if (mpvReady && started) {
                PlayerLog.add("stats: " + playbackStats().replace("\n", " | "))
                val drops = try { (MPVLib.getPropertyInt("frame-drop-count") ?: 0) + (MPVLib.getPropertyInt("decoder-frame-drop-count") ?: 0) } catch (_: Throwable) { 0 }
                // Mais de 10% dos quadros perdidos em 15 s (24 × 15 = 360): sugere outro modo.
                if (!paused && drops - lastDrops > 36 && mode != 3 && !suggested) suggestLighterMode(drops - lastDrops)
                lastDrops = drops
            }
            ui.postDelayed(this, 15000)
        }
    }

    /** O vídeo está engasgando: oferece o modo leve e reabre no mesmo ponto. */
    private fun suggestLighterMode(lost: Int) {
        suggested = true
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("O vídeo está travando")
            .setMessage("Nos últimos 15 segundos a TV perdeu $lost de 360 quadros.\n\n" +
                "O modo leve manda o vídeo direto do decodificador para a tela, sem passar pela placa de vídeo. " +
                "Costuma ficar fluido, mas a legenda aparece como texto simples (sem as cores e posições do fansub). " +
                "Dá para voltar em Ajustes → Modo de vídeo do player.")
            .setPositiveButton("Usar o modo leve") { _, _ ->
                Prefs(this).videoMode = 3
                PlayerLog.add("player: usuário escolheu o modo leve")
                switchToLightMode()
            }
            .setNegativeButton("Continuar assim", null)
            .show()
    }

    /** Troca para o modo leve sem fechar o player (o mpv reinicia só a saída de vídeo). */
    private fun switchToLightMode() {
        mode = 3
        vo = "mediacodec_embed"
        try {
            MPVLib.observeProperty("sub-text", MPVLib.MPV_FORMAT_STRING)
            subView.visibility = View.VISIBLE
            MPVLib.setPropertyString("hwdec", "mediacodec")
            MPVLib.setPropertyString("vo", vo)
            lastDrops = (MPVLib.getPropertyInt("frame-drop-count") ?: 0) + (MPVLib.getPropertyInt("decoder-frame-drop-count") ?: 0)
            showOsd("Modo leve ativado")
        } catch (e: Throwable) {
            PlayerLog.add("player: falha ao trocar de modo: $e")
            showOsd("Modo leve vale a partir do próximo episódio")
        }
    }

    private fun speed(bps: Int) = if (bps >= 1024 * 1024)
        String.format(Locale("pt", "BR"), "%.1f MB/s", bps / 1048576.0)
    else "${bps / 1024} KB/s"

    private fun time(s: Long) = if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format("%d:%02d", s / 60, s % 60)

    private val detailsTicker = object : Runnable {
        override fun run() {
            if (!showDetails) return
            updatePanel()
            ui.postDelayed(this, 1000)
        }
    }

    private val hideOsd = Runnable { osd.visibility = View.GONE }

    private fun showOsd(text: String? = null) {
        val state = if (paused) "⏸" else "▶"
        osd.text = text ?: "$state  ${time(position)} / ${time(duration)}"
        osd.visibility = View.VISIBLE
        ui.removeCallbacks(hideOsd)
        ui.postDelayed(hideOsd, 4000)
    }

    // ---------- mpv ----------

    private fun startMpv() {
        val config = File(filesDir, "mpv").apply { mkdirs() }
        val cache = File(cacheDir, "mpv").apply { mkdirs() }
        writeFontsConf(config)
        MPVLib.create(applicationContext)
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", config.path)
        MPVLib.setOptionString("gpu-shader-cache-dir", cache.path)
        MPVLib.setOptionString("icc-cache-dir", cache.path)
        MPVLib.setOptionString("profile", "fast")
        mode = Prefs(this).videoMode
        vo = if (mode == 3) "mediacodec_embed" else "gpu"
        MPVLib.setOptionString("vo", vo)
        if (mode != 3) {
            // Atalhos para placas de vídeo fracas (TVs): desenho direto, sem etapas extras.
            MPVLib.setOptionString("gpu-dumb-mode", "yes")
            MPVLib.setOptionString("opengl-pbo", "yes")
            MPVLib.setOptionString("dither", "no")
        }
        MPVLib.setOptionString("video-sync", "audio")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        PlayerLog.add("mpv: modo de vídeo $mode (vo=$vo)")
        MPVLib.setOptionString("hwdec", when (mode) { 0 -> "mediacodec,mediacodec-copy"; 1 -> "mediacodec-copy"; 2 -> "no"; else -> "mediacodec" })
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("cache-pause-initial", "yes")
        MPVLib.setOptionString("cache-pause-wait", "10")
        MPVLib.setOptionString("demuxer-max-bytes", "32MiB")
        MPVLib.setOptionString("demuxer-max-back-bytes", "8MiB")
        MPVLib.setOptionString("network-timeout", "0")
        MPVLib.setOptionString("sub-scale-with-window", "yes")
        MPVLib.setOptionString("sub-use-margins", "no")
        MPVLib.setOptionString("sub-fonts-dir", "/system/fonts")
        MPVLib.setOptionString("slang", "pt-BR,pt,por,pob")
        MPVLib.setOptionString("alang", "ja,jpn")
        MPVLib.setOptionString("keep-open", "yes")
        MPVLib.setOptionString("ytdl", "no")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.init()
        MPVLib.addObserver(this)
        MPVLib.addLogObserver(this)
        if (mode == 3) {
            subView.visibility = View.VISIBLE
            MPVLib.observeProperty("sub-text", MPVLib.MPV_FORMAT_STRING)
        }
        intent.getLongExtra(EXTRA_START, 0L).takeIf { it > 0 }?.let { MPVLib.setOptionString("start", it.toString()) }
        MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG)
        mpvReady = true
    }

    /** Fontes do sistema para legendas que não trazem as próprias. */
    private fun writeFontsConf(dir: File) {
        val f = File(dir, "fonts.conf")
        if (f.exists()) return
        val cache = File(cacheDir, "fontconfig").apply { mkdirs() }
        f.writeText(
            """
            <fontconfig>
                <dir>/system/fonts/</dir>
                <dir>/product/fonts/</dir>
                <cachedir>${cache.path}</cachedir>
                <alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>
            </fontconfig>
            """.trimIndent()
        )
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        // Só avisos e erros (e as linhas principais do player), para o registro não crescer demais.
        if (level <= MPVLib.MPV_LOG_LEVEL_WARN || (prefix == "cplayer" && level <= MPVLib.MPV_LOG_LEVEL_INFO))
            PlayerLog.add("mpv[$prefix]: ${text.trim()}")
    }

    private fun load(url: String) {
        PlayerLog.add("mpv: abrir $url")
        if (hasSurface) MPVLib.command(arrayOf("loadfile", url)) else pendingUrl = url
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        hasSurface = true
        PlayerLog.add("tela: superfície criada")
        if (!mpvReady) return
        MPVLib.attachSurface(holder.surface)
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setOptionString("vo", vo)
        pendingUrl?.let { MPVLib.command(arrayOf("loadfile", it)) }
        pendingUrl = null
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (mpvReady) MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        hasSurface = false
        if (!mpvReady) return
        MPVLib.setOptionString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
    }

    // Eventos do mpv chegam em outra thread.
    override fun eventProperty(property: String) {}
    /**
     * Legenda simples: placas e karaokê (abertura/encerramento) vêm como muitas linhas
     * curtas, às vezes uma letra por linha. Mostra só as falas (linhas com texto de verdade).
     */
    private fun cleanSub(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val real = lines.filter { it.length >= 4 || it.any { c -> c == '?' || c == '!' || c == '.' } && it.length >= 2 }
        return if (real.size > 3) real.takeLast(2).joinToString("\n") else real.joinToString("\n")
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "sub-text") ui.post { subView.text = cleanSub(value) }
    }
    override fun eventProperty(property: String, value: Double) {}

    override fun eventProperty(property: String, value: Long) {
        ui.post {
            when (property) {
                "time-pos" -> position = value
                "duration" -> duration = value
            }
            if (showDetails || buffering) updatePanel()
            if (::controls.isInitialized && controls.visibility == View.VISIBLE) updateControls()
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        ui.post {
            when (property) {
                "pause" -> { paused = value; updateControls() }
                "paused-for-cache" -> { buffering = value; updatePanel() }
                "eof-reached" -> if (value) showOsd("Fim do episódio. Aperte Voltar para sair.")
            }
        }
    }

    override fun event(eventId: Int) {
        PlayerLog.add("mpv: evento $eventId")
        ui.post {
            when (eventId) {
                MPVLib.MPV_EVENT_PLAYBACK_RESTART -> {
                    if (!started) ui.postDelayed(statsLogger, 5000)
                    started = true; buffering = false; updatePanel()
                }
                MPVLib.MPV_EVENT_END_FILE -> if (!started) {
                    panelText.text = "O player não conseguiu abrir o vídeo.\n\nAperte Voltar para sair."
                    spinner.visibility = View.GONE
                }
            }
        }
    }

    // ---------- controle remoto ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!mpvReady) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> togglePause()
            KeyEvent.KEYCODE_DPAD_LEFT -> seek(-10)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seek(10)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-30)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(30)
            KeyEvent.KEYCODE_DPAD_UP -> cycleSub()
            KeyEvent.KEYCODE_DPAD_DOWN -> toggleDetails()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun togglePause() {
        MPVLib.command(arrayOf("cycle", "pause"))
        paused = !paused
        showOsd()
        updateControls()
    }

    private fun cycleSub() {
        MPVLib.command(arrayOf("cycle", "sub"))
        ui.postDelayed({
            val sub = MPVLib.getPropertyString("current-tracks/sub/title")
                ?: MPVLib.getPropertyString("current-tracks/sub/lang")
            showOsd("Legenda: ${sub ?: "desligada"}")
        }, 300)
    }

    private fun toggleDetails() {
        showDetails = !showDetails
        updatePanel()
        if (showDetails) ui.post(detailsTicker)
    }

    // ---------- toque (celular) ----------

    private lateinit var controls: LinearLayout
    private lateinit var seekBar: android.widget.SeekBar
    private lateinit var timeText: TextView
    private lateinit var playBtn: android.widget.Button
    private var userSeeking = false
    private val hideControls = Runnable { if (!paused) controls.visibility = View.GONE }

    /** Barra com botões e linha do tempo, que aparece ao tocar na tela. */
    private fun buildTouchControls(root: FrameLayout) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(12))
            visibility = View.GONE
            isClickable = true // toques na barra não chegam ao vídeo
        }
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        seekBar = android.widget.SeekBar(this)
        timeText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(8), 0, 0, 0) }
        line.addView(seekBar, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(timeText)
        controls.addView(line)
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun btn(label: String, action: () -> Unit) = android.widget.Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { action(); showControls() }
        }.also { buttons.addView(it) }
        btn("−10 s") { seek(-10) }
        playBtn = btn("⏸") { togglePause() }
        btn("+10 s") { seek(10) }
        btn("Legenda") { cycleSub() }
        btn("Detalhes") { toggleDetails() }
        controls.addView(buttons)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) timeText.text = "${time(p.toLong())} / ${time(duration)}"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) { userSeeking = true; ui.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                userSeeking = false
                MPVLib.command(arrayOf("seek", sb.progress.toString(), "absolute"))
                position = sb.progress.toLong()
                showControls()
            }
        })

        // Toque simples: mostra/esconde a barra. Toque duplo nas laterais: volta/avança 10 s.
        val gestures = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE else showControls()
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                val third = root.width / 3f
                when {
                    e.x < third -> seek(-10)
                    e.x > 2 * third -> seek(10)
                    else -> togglePause()
                }
                return true
            }
        })
        root.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
    }

    private fun showControls() {
        if (!::controls.isInitialized) return
        updateControls()
        controls.visibility = View.VISIBLE
        ui.removeCallbacks(hideControls)
        ui.postDelayed(hideControls, 5000)
    }

    private fun updateControls() {
        if (!::controls.isInitialized) return
        playBtn.text = if (paused) "▶" else "⏸"
        if (!userSeeking) {
            seekBar.max = maxOf(duration, 1L).toInt()
            seekBar.progress = position.toInt()
            timeText.text = "${time(position)} / ${time(duration)}"
        }
    }

    private fun seek(seconds: Int) {
        MPVLib.command(arrayOf("seek", seconds.toString(), "relative"))
        position = (position + seconds).coerceIn(0, maxOf(duration, 0))
        showOsd()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        torrent?.stop()
        if (mpvReady) {
            MPVLib.removeLogObserver(this)
            MPVLib.removeObserver(this)
            MPVLib.destroy()
            mpvReady = false
        }
        PlayerLog.end()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MAGNET = "magnet"
        const val EXTRA_TITLE = "title"
        const val EXTRA_EPISODE = "episode"
        const val EXTRA_START = "start"
        const val EXTRA_LOCAL_FILE = "localFile"
        const val EXTRA_ANIME_ID = "animeId"
        const val EXTRA_RELEASE = "release"
    }
}
