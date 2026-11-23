package com.idelmiro.animestv

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro do que o player fez na última vez que foi aberto. Cada linha é gravada no disco
 * na hora, para sobreviver mesmo se o app fechar de repente (inclusive por erro em código
 * nativo, que não dá para capturar). Na próxima abertura do Animes TV, se a sessão não
 * terminou normalmente, o app mostra as últimas etapas.
 */
object PlayerLog {
    private const val END = "== saída normal =="
    private const val MAX_LINES = 400
    private var file: File? = null
    private var lines = 0
    private var handlerInstalled = false
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun f(ctx: Context) = File(ctx.filesDir, "player-log.txt")

    @Synchronized
    fun begin(ctx: Context, what: String) {
        val file = f(ctx)
        this.file = file
        lines = 0
        file.writeText("")
        add("== início: $what ==")
        add("Android ${android.os.Build.VERSION.RELEASE} • ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} • " +
            android.os.Build.SUPPORTED_ABIS.joinToString(","))
        if (handlerInstalled) return
        handlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            add("ERRO (thread ${t.name}): $sw")
            previous?.uncaughtException(t, e)
        }
    }

    @Synchronized
    fun add(msg: String) {
        val file = file ?: return
        if (lines++ > MAX_LINES) return
        try {
            FileWriter(file, true).use { it.write("${fmt.format(Date())} $msg\n") }
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun end() {
        add(END)
        file = null
    }

    /** Conteúdo da última sessão, se ela não terminou normalmente. */
    fun crashed(ctx: Context): String? {
        val file = f(ctx)
        if (!file.exists()) return null
        val text = file.readText()
        if (text.isBlank() || text.contains(END)) return null
        return text
    }

    fun last(ctx: Context): String? = f(ctx).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    /** Marca o registro como já mostrado, para não avisar de novo. */
    fun dismiss(ctx: Context) {
        val file = f(ctx)
        if (file.exists()) FileWriter(file, true).use { it.write("$END (aviso já mostrado)\n") }
    }
}
