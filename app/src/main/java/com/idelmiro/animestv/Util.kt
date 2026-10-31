package com.idelmiro.animestv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.LruCache
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Carregador de imagens simples, com cache em memória. */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String?, view: ImageView) {
        view.tag = url
        if (url == null) {
            view.setImageDrawable(null)
            return
        }
        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            try {
                val bmp = URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    cache.put(url, bmp)
                    main.post { if (view.tag == url) view.setImageBitmap(bmp) }
                }
            } catch (_: Exception) {
            }
        }
    }
}

/** Atualiza o app baixando a versão mais nova publicada no GitHub. */
object Updater {
    private const val API = "https://api.github.com/repos/idelmiro/animes-tv/releases/latest"

    data class Release(val version: Int, val apkUrl: String)

    fun fetchLatest(): Release {
        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code == 404) throw Exception("nenhuma versão encontrada (o repositório no GitHub precisa ser público)")
            if (code !in 200..299) throw Exception("GitHub respondeu com erro $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").trimStart('v').toInt()
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) return Release(version, a.getString("browser_download_url"))
            }
            throw Exception("a versão $version não tem arquivo APK")
        } finally {
            conn.disconnect()
        }
    }

    fun download(ctx: Context, url: String): File {
        val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
        val file = File(dir, "animes-tv.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        return file
    }

    fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Permita que o Animes TV instale apps e tente de novo.", Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                )
            } catch (_: Exception) {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}

/** Recebe o clique vindo da tela inicial da TV e abre o Crunchyroll. */
class DeepLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.data?.getQueryParameter("url")?.takeIf { it.isNotBlank() }
        val msg = CrunchyrollLauncher.open(this, url)
        if (msg != null) Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
