package com.idelmiro.animestv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Toca o trailer do YouTube indicado no AniList dentro do app (o player de vídeo do app
 * não toca YouTube; aqui é a página de vídeo incorporado do YouTube). Se não der, abre
 * no app do YouTube.
 */
class TrailerActivity : Activity() {
    private var web: WebView? = null
    private var id = ""
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val w = try { WebView(this) } catch (e: Throwable) {
            // TV sem o componente de páginas da web: usa o app do YouTube.
            openInYouTube(); return
        }
        web = w
        w.setBackgroundColor(Color.BLACK)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webChromeClient = WebChromeClient()
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) openInYouTube()
            }
        }
        frame.addView(w, FrameLayout.LayoutParams(-1, -1))
        val hint = TextView(this).apply {
            text = "$title  ·  OK pausa/continua  ·  ▼ abre no app do YouTube  ·  Voltar sai"
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(0xB3000000.toInt())
            setPadding(28, 14, 28, 14)
        }
        frame.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 40 })
        ui.postDelayed({ hint.animate().alpha(0f).setDuration(600).start() }, 6000)
        setContentView(frame)
        // O YouTube exige saber de onde vem o vídeo incorporado (sem isso dá "erro 153").
        w.loadUrl("https://www.youtube.com/embed/$id?autoplay=1&rel=0&playsinline=1&fs=0&iv_load_policy=3",
            mapOf("Referer" to "https://com.idelmiro.animestv/"))
        w.requestFocus()
    }

    private fun openInYouTube() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")))
        } catch (_: Exception) {
            Toast.makeText(this, "Não consegui abrir o trailer.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down) web?.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');if(!v)return;" +
                        "if(v.paused){v.muted=false;v.play();}else{v.pause();}})()", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!down) openInYouTube()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
    }
}
