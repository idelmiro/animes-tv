package com.idelmiro.animestv

import android.app.Activity
import android.graphics.Color
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
class PlayerActivity : Activity(), MPVLib.EventObserver, SurfaceHolder.Callback {

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
    private var episode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        episode = intent.getIntExtra(EXTRA_EPISODE, 0)
        val magnet = intent.getStringExtra(EXTRA_MAGNET)
        if (magnet == null) {
            finish()
            return
        }
        buildViews()
        startMpv()
        torrent = TorrentStream(applicationContext, magnet, episode,
            onStatus = { s -> ui.post { onTorrent(s) } },
            onReady = { url -> ui.post { load(url) } }
        ).also { it.start() }
        updatePanel()
    }

    // ---------- tela ----------

    private fun buildViews() {
        val d = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
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
            }
        }
        if (mpvReady && started && duration > 0) sb.append("\n\n${time(position)} / ${time(duration)}")
        panelText.text = sb
    }

    private fun speed(bps: Int) = if (bps >= 1024 * 1024)
        String.format(Locale("pt", "BR"), "%.1f MB/s", bps / 1048576.0)
    else "${bps / 1024} KB/s"

    private fun time(s: Long) = if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format("%d:%02d", s / 60, s % 60)

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
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("cache-pause-initial", "yes")
        MPVLib.setOptionString("cache-pause-wait", "3")
        MPVLib.setOptionString("demuxer-max-bytes", "64MiB")
        MPVLib.setOptionString("demuxer-max-back-bytes", "16MiB")
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

    private fun load(url: String) {
        if (hasSurface) MPVLib.command(arrayOf("loadfile", url)) else pendingUrl = url
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        hasSurface = true
        if (!mpvReady) return
        MPVLib.attachSurface(holder.surface)
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setOptionString("vo", "gpu")
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
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Double) {}

    override fun eventProperty(property: String, value: Long) {
        ui.post {
            when (property) {
                "time-pos" -> position = value
                "duration" -> duration = value
            }
            if (showDetails || buffering) updatePanel()
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        ui.post {
            when (property) {
                "pause" -> paused = value
                "paused-for-cache" -> { buffering = value; updatePanel() }
                "eof-reached" -> if (value) showOsd("Fim do episódio. Aperte Voltar para sair.")
            }
        }
    }

    override fun event(eventId: Int) {
        ui.post {
            when (eventId) {
                MPVLib.MPV_EVENT_PLAYBACK_RESTART -> { started = true; buffering = false; updatePanel() }
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
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                MPVLib.command(arrayOf("cycle", "pause"))
                paused = !paused
                showOsd()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> seek(-10)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seek(10)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-30)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(30)
            KeyEvent.KEYCODE_DPAD_UP -> {
                MPVLib.command(arrayOf("cycle", "sub"))
                ui.postDelayed({
                    val sub = MPVLib.getPropertyString("current-tracks/sub/title")
                        ?: MPVLib.getPropertyString("current-tracks/sub/lang")
                    showOsd("Legenda: ${sub ?: "desligada"}")
                }, 300)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showDetails = !showDetails
                updatePanel()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
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
            MPVLib.removeObserver(this)
            MPVLib.destroy()
            mpvReady = false
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MAGNET = "magnet"
        const val EXTRA_TITLE = "title"
        const val EXTRA_EPISODE = "episode"
    }
}
