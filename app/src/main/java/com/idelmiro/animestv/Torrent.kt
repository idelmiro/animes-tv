package com.idelmiro.animestv

import android.content.Context
import android.os.StatFs
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.TorrentAlert
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.Locale

/**
 * Pasta de downloads com limite de espaço, na memória interna ou num pendrive/cartão.
 * Cada episódio fica em uma subpasta própria; ".pronto" marca um download completo (e guarda
 * o caminho do vídeo) e ".info" guarda de qual anime/episódio ele é.
 */
object Downloads {
    private const val GB = 1024L * 1024 * 1024

    /** Um lugar onde dá para guardar downloads (memória interna ou pendrive/cartão). */
    data class Volume(val path: File, val name: String, val removable: Boolean, val free: Long, val total: Long)

    /** Memória interna e pendrives/cartões conectados que o app consegue usar. */
    fun volumes(ctx: Context): List<Volume> {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        return ctx.getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
            try {
                dir.mkdirs()
                val st = StatFs(dir.absolutePath)
                val removable = android.os.Environment.isExternalStorageRemovable(dir)
                val desc = sm.getStorageVolume(dir)?.getDescription(ctx)
                val name = if (!removable) "Memória interna" else (desc ?: "Pendrive/cartão")
                Volume(dir, name, removable, st.availableBytes, st.totalBytes)
            } catch (_: Throwable) { null }
        }
    }

    /** Limite total escolhido em Ajustes (padrão 1 GB). */
    fun cap(ctx: Context): Long = Prefs(ctx).downloadCapGb * GB

    fun capLabel(ctx: Context) = "${Prefs(ctx).downloadCapGb} GB"

    /** O local escolhido está acessível? (falso se o pendrive foi desconectado) */
    fun available(ctx: Context): Boolean {
        val p = Prefs(ctx).downloadPath ?: return true
        val f = File(p)
        return f.exists() && f.canWrite()
    }

    fun locationName(ctx: Context): String {
        val p = Prefs(ctx).downloadPath ?: return "Memória interna"
        return volumes(ctx).firstOrNull { it.path.absolutePath == p }?.name ?: "Pendrive (desconectado)"
    }

    fun root(ctx: Context): File {
        val base = Prefs(ctx).downloadPath?.let { File(it) } ?: (ctx.getExternalFilesDir(null) ?: ctx.filesDir)
        return File(base, "downloads").apply { mkdirs() }
    }

    fun dirFor(ctx: Context, hash: String, fileIndex: Int) = File(root(ctx), "$hash-$fileIndex")

    private fun marker(dir: File) = File(dir, ".pronto")
    private fun info(dir: File) = File(dir, ".info")

    /** Vídeo já baixado por completo, se existir. */
    fun completed(dir: File): File? {
        val m = marker(dir)
        if (!m.exists()) return null
        return File(m.readText().trim()).takeIf { it.exists() }
    }

    fun markDone(dir: File, video: File) {
        marker(dir).writeText(video.absolutePath)
        touch(dir)
    }

    /** Marca como usado agora (o mais antigo é o primeiro a ser apagado). */
    fun touch(dir: File) {
        val now = System.currentTimeMillis()
        marker(dir).takeIf { it.exists() }?.setLastModified(now)
        dir.setLastModified(now)
    }

    private fun lastUse(dir: File) = marker(dir).takeIf { it.exists() }?.lastModified() ?: dir.lastModified()

    /** Guarda de qual anime/episódio é o download, para achar depois sem buscar no Nyaa. */
    fun writeInfo(dir: File, animeId: Int, title: String, episode: Int, release: String, size: Long) {
        try {
            info(dir).writeText(org.json.JSONObject()
                .put("animeId", animeId).put("title", title).put("episode", episode)
                .put("release", release).put("size", size).toString())
        } catch (_: Throwable) {
        }
    }

    data class Saved(val dir: File, val video: File, val animeId: Int, val episode: Int, val release: String, val size: Long)

    /** Episódios completos já baixados (com a informação de anime/episódio). */
    fun saved(ctx: Context): List<Saved> {
        if (!available(ctx)) return emptyList()
        return (root(ctx).listFiles() ?: emptyArray()).mapNotNull { d ->
            val v = completed(d) ?: return@mapNotNull null
            try {
                val o = org.json.JSONObject(info(d).readText())
                Saved(d, v, o.getInt("animeId"), o.getInt("episode"), o.optString("release", v.name), v.length())
            } catch (_: Throwable) { null }
        }
    }

    fun savedFor(ctx: Context, animeId: Int, episode: Int) = saved(ctx).filter { it.animeId == animeId && it.episode == episode }

    fun size(f: File): Long = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { size(it) } else f.length()

    fun used(ctx: Context): Long = if (available(ctx)) size(root(ctx)) else 0L

    fun count(ctx: Context): Int = if (!available(ctx)) 0 else root(ctx).listFiles()?.count { it.isDirectory && completed(it) != null } ?: 0

    fun clearAll(ctx: Context) {
        root(ctx).listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Libera espaço para um arquivo de [need] bytes: primeiro apaga downloads incompletos,
     * depois os episódios usados há mais tempo, até caber no limite. Devolve quantos apagou.
     */
    fun makeRoom(ctx: Context, need: Long, keep: File): Int {
        var removed = 0
        val limit = cap(ctx)
        val dirs = (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory && it != keep }
        for (d in dirs.filter { completed(it) == null }) {
            d.deleteRecursively(); removed++
        }
        val done = dirs.filter { it.exists() }.sortedBy { lastUse(it) }
        for (d in done) {
            if (used(ctx) + need <= limit) break
            d.deleteRecursively(); removed++
        }
        return removed
    }

    fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale("pt", "BR"), "%.2f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format(Locale("pt", "BR"), "%.0f MB", bytes / 1048576.0)
        else -> String.format(Locale("pt", "BR"), "%.0f KB", bytes / 1024.0)
    }
}

/**
 * Baixa um episódio de um link magnético em ordem (para tocar enquanto baixa) e o entrega
 * ao player por um endereço local (http://127.0.0.1). Assim que o download termina, o
 * torrent é removido da sessão: o compartilhamento (envio para outras pessoas) para na hora.
 */
class TorrentStream(
    private val ctx: Context,
    private val magnet: String,
    private val episode: Int,
    private val animeId: Int,
    private val animeTitle: String,
    private val release: String,
    private val onStatus: (Status) -> Unit,
    private val onReady: (String) -> Unit,
    /**
     * Arquivo maior que o limite: pergunta se baixa mesmo assim, apagando os episódios salvos.
     * Recebe o tamanho, quantos episódios estão salvos e quanto ocupam; chame answer(true/false).
     */
    private val onOversize: (size: Long, savedCount: Int, savedBytes: Long, answer: (Boolean) -> Unit) -> Unit,
) {
    enum class Phase { METADATA, SPACE, CONNECTING, DOWNLOADING, COMPLETE, ERROR }

    data class Status(
        val phase: Phase,
        val message: String,
        val peers: Int = 0,
        val seeds: Int = 0,
        val downRate: Int = 0,
        val upRate: Int = 0,
        val done: Long = 0,
        val total: Long = 0,
        /** O player está esperando um pedaço do arquivo que ainda não chegou. */
        val waiting: Boolean = false
    )

    @Volatile private var closed = false
    @Volatile private var complete = false
    @Volatile private var waitingPiece = -1
    @Volatile private var handle: TorrentHandle? = null
    private var session: SessionManager? = null
    private var server: ServerSocket? = null
    private var dir: File? = null
    private var video: File? = null
    private var fileOffset = 0L
    private var fileSize = 0L
    private var pieceLength = 1
    private var lastPiece = 0
    private var infoHash: org.libtorrent4j.Sha1Hash? = null
    private var deadlineFrom = -1

    fun start() {
        Thread({ run() }, "torrent").start()
    }

    private var lastLogged = ""

    private fun status(s: Status) {
        if (s.message != lastLogged) {
            lastLogged = s.message
            PlayerLog.add("torrent: ${s.phase} ${s.message}")
        }
        if (!closed) onStatus(s)
    }

    private fun fail(msg: String) {
        status(Status(Phase.ERROR, msg))
    }

    private fun run() {
        try {
            val s = SessionManager()
            session = s
            // Gravação simples em disco (sem mapear o arquivo na memória): mais leve para a TV.
            val params = SessionParams()
            params.setPosixDiskIO()
            s.start(params)
            PlayerLog.add("torrent: sessão iniciada")
            status(Status(Phase.METADATA, "Obtendo as informações do torrent (procurando quem tem o arquivo)…"))
            val tmp = File(ctx.cacheDir, "magnet").apply { mkdirs() }
            val data = s.fetchMagnet(magnet, 90, tmp)
            if (closed) return
            if (data == null) {
                fail("Ninguém respondeu com as informações do torrent em 90 segundos. Escolha outro lançamento (com mais seeds).")
                return
            }
            val ti = TorrentInfo.bdecode(data)
            val fs = ti.files()
            val idx = chooseFile(ti)
            PlayerLog.add("torrent: ${ti.numFiles()} arquivo(s), escolhido #$idx ${fs.fileName(idx)} (${fs.fileSize(idx)} bytes), pedaço ${ti.pieceLength()} bytes")
            fileOffset = fs.fileOffset(idx)
            fileSize = fs.fileSize(idx)
            pieceLength = ti.pieceLength()
            lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
            val hash = ti.infoHash().toHex()
            infoHash = ti.infoHash()
            if (!Downloads.available(ctx)) {
                fail("O pendrive escolhido para os downloads não foi encontrado. Conecte-o de novo ou mude o local em Ajustes → Downloads.")
                return
            }
            val d = Downloads.dirFor(ctx, hash, idx)
            dir = d

            // Já baixado antes: toca direto do arquivo, sem rede.
            Downloads.completed(d)?.let { f ->
                complete = true
                video = f
                Downloads.touch(d)
                status(Status(Phase.COMPLETE, "Episódio já estava baixado. Tocando sem usar a internet.", done = f.length(), total = f.length()))
                stopSession()
                onReady(f.absolutePath)
                return
            }

            status(Status(Phase.SPACE, "Arquivo de ${Downloads.human(fileSize)}. Conferindo o espaço…", total = fileSize))
            val removed: Int
            val cap = Downloads.cap(ctx)
            val capLabel = Downloads.capLabel(ctx)
            if (fileSize > cap) {
                // Acima do limite: só baixa se o usuário confirmar, e aí apaga todos os salvos.
                val others = (Downloads.root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory && it != d }
                val savedCount = others.count { Downloads.completed(it) != null }
                val savedBytes = others.sumOf { Downloads.size(it) }
                status(Status(Phase.SPACE, "O arquivo tem ${Downloads.human(fileSize)}, mais que o limite de $capLabel. Aguardando sua confirmação…", total = fileSize))
                val latch = java.util.concurrent.CountDownLatch(1)
                var ok = false
                onOversize(fileSize, savedCount, savedBytes) { yes -> ok = yes; latch.countDown() }
                while (!closed && !latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) { }
                if (closed) return
                if (!ok) {
                    fail("Download cancelado: o arquivo tem ${Downloads.human(fileSize)}, mais que o limite de $capLabel.")
                    return
                }
                others.forEach { it.deleteRecursively() }
                removed = savedCount
            } else {
                removed = Downloads.makeRoom(ctx, fileSize, d)
            }
            d.mkdirs()
            Downloads.writeInfo(d, animeId, animeTitle, episode, release, fileSize)
            val free = StatFs(d.absolutePath).availableBytes
            if (free < fileSize + 50L * 1024 * 1024) {
                fail("A TV não tem espaço livre suficiente (${Downloads.human(free)} livres, o episódio tem ${Downloads.human(fileSize)}).")
                return
            }
            if (removed > 0) status(Status(Phase.SPACE, "Apaguei $removed episódio(s) antigo(s) para liberar espaço.", total = fileSize))

            video = File(fs.filePath(idx, d.absolutePath))
            s.addListener(object : AlertListener {
                override fun types(): IntArray? = null
                override fun alert(alert: Alert<*>) {
                    try {
                        val type = alert.type()
                        if (type != AlertType.ADD_TORRENT && type != AlertType.TORRENT_FINISHED) return
                        val h = (alert as? TorrentAlert<*>)?.handle() ?: return
                        if (!h.isValid || h.infoHash().toHex() != hash) return
                        PlayerLog.add("torrent: alerta $type")
                        // O handle que vem no alerta deixa de valer quando o alerta é descartado
                        // (usá-lo depois derruba o app). Pega uma cópia própria pela sessão.
                        val own = s.find(ti.infoHash()) ?: return
                        if (type == AlertType.ADD_TORRENT) {
                            handle = own
                            own.unsetFlags(TorrentFlags.AUTO_MANAGED)
                            own.resume()
                        } else finished(own)
                    } catch (e: Throwable) {
                        PlayerLog.add("torrent: erro no alerta: $e")
                    }
                }
            })
            val prio = Priority.array(Priority.IGNORE, ti.numFiles())
            prio[idx] = Priority.TOP_PRIORITY
            status(Status(Phase.CONNECTING, "Conectando às pessoas que têm o episódio…", total = fileSize))
            s.download(ti, d, null, prio, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            PlayerLog.add("torrent: download pedido em ${d.absolutePath}")

            val srv = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = srv
            Thread({ serve(srv) }, "torrent-http").start()
            val name = URLEncoder.encode(video!!.name, "UTF-8").replace("+", "%20")
            PlayerLog.add("http: servidor na porta ${srv.localPort}")
            onReady("http://127.0.0.1:${srv.localPort}/$name")

            // Atualiza o andamento a cada segundo, enquanto baixa.
            while (!closed && !complete) {
                Thread.sleep(1000)
                val h = handle ?: refind() ?: continue
                val st = try { h.status() } catch (e: RuntimeException) { handle = null; continue }
                val done = try { h.fileProgress().getOrElse(idx) { 0L } } catch (e: RuntimeException) { 0L }
                val phase = if (st.numPeers() == 0) Phase.CONNECTING else Phase.DOWNLOADING
                val msg = if (phase == Phase.CONNECTING) "Conectando às pessoas que têm o episódio…" else "Baixando o episódio…"
                status(Status(phase, msg, st.numPeers(), st.numSeeds(), st.downloadRate(), st.uploadRate(), done, fileSize, waitingPiece >= 0))
            }
        } catch (e: InterruptedException) {
        } catch (e: Throwable) {
            PlayerLog.add("torrent: exceção $e")
            if (!closed) fail("Erro no download: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Arquivo do episódio dentro do torrent (pacotes trazem vários). */
    private fun chooseFile(ti: TorrentInfo): Int {
        val fs = ti.files()
        val all = (0 until fs.numFiles()).toList()
        val videos = all.filter { fs.fileName(it).substringAfterLast('.').lowercase() in VIDEO_EXT }
        val match = videos.filter { Nyaa.matchEpisode(fs.fileName(it), episode) == false }
        return match.ifEmpty { videos }.ifEmpty { all }.maxBy { fs.fileSize(it) }
    }

    /** Download terminou: para de compartilhar imediatamente e guarda o episódio. */
    private fun finished(h: TorrentHandle) {
        if (complete) return
        complete = true
        try {
            h.pause()
            session?.remove(h)
        } catch (_: Throwable) {
        }
        val d = dir
        val v = video
        if (d != null && v != null) Downloads.markDone(d, v)
        status(Status(Phase.COMPLETE, "Download concluído. Compartilhamento encerrado.", done = fileSize, total = fileSize))
        Thread { stopSession() }.start()
    }

    /** Fecha tudo. Se o download não terminou, apaga o que foi baixado. */
    fun stop() {
        if (closed) return
        closed = true
        try { server?.close() } catch (_: Throwable) {}
        Thread {
            stopSession()
            if (!complete) dir?.deleteRecursively()
        }.start()
    }

    @Synchronized
    private fun stopSession() {
        val s = session ?: return
        session = null
        try {
            handle?.let { if (it.isValid) s.remove(it) }
            s.stop()
        } catch (_: Throwable) {
        }
    }

    // ---------- servidor local para o player ----------

    private fun serve(srv: ServerSocket) {
        while (!closed) {
            val c = try { srv.accept() } catch (_: Throwable) { return }
            Thread({ try { handleClient(c) } catch (e: Throwable) { PlayerLog.add("http: conexão encerrada ($e)") } finally { try { c.close() } catch (_: Throwable) {} } }, "torrent-cli").start()
        }
    }

    private fun handleClient(c: Socket) {
        val reader = BufferedReader(InputStreamReader(c.getInputStream()))
        val request = reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val head = request.startsWith("HEAD")
        PlayerLog.add("http: ${request.substringBefore(" HTTP")} ${range ?: "(sem Range)"}")
        var start = 0L
        var end = fileSize - 1
        val m = range?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
        if (m != null) {
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if (a.isEmpty() && b.isNotEmpty()) start = fileSize - b.toLong()
            else {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = minOf(b.toLong(), fileSize - 1)
            }
        }
        val out = c.getOutputStream()
        if (start >= fileSize || start > end) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$fileSize\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val len = end - start + 1
        val type = if (video?.name?.endsWith(".mp4", true) == true) "video/mp4" else "video/x-matroska"
        val headers = buildString {
            append(if (m != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: $type\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $len\r\n")
            if (m != null) append("Content-Range: bytes $start-$end/$fileSize\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())
        if (head) return

        val buf = ByteArray(64 * 1024)
        var pos = start
        var raf: RandomAccessFile? = null
        try {
            while (pos <= end && !closed) {
                val piece = ((fileOffset + pos) / pieceLength).toInt()
                waitForPiece(piece)
                if (raf == null) raf = RandomAccessFile(video!!, "r")
                val pieceEnd = (piece + 1).toLong() * pieceLength - fileOffset // primeiro byte do próximo pedaço
                val chunkEnd = minOf(end + 1, pieceEnd)
                raf.seek(pos)
                while (pos < chunkEnd && !closed) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), chunkEnd - pos).toInt())
                    if (n <= 0) { Thread.sleep(50); continue }
                    out.write(buf, 0, n)
                    pos += n
                }
            }
            out.flush()
        } finally {
            raf?.close()
        }
    }

    /** Espera o pedaço chegar, pedindo prioridade máxima para ele e os próximos. */
    private fun waitForPiece(piece: Int) {
        if (complete) return
        while (!closed && !complete) {
            val h = handle ?: refind()
            if (h != null && h.isValid) {
                val have = try { h.havePiece(piece) } catch (e: RuntimeException) {
                    PlayerLog.add("http: handle inválido, buscando de novo ($e)")
                    handle = null
                    Thread.sleep(100)
                    continue
                }
                if (have) {
                    if (waitingPiece == piece) waitingPiece = -1
                    return
                }
                if (deadlineFrom != piece) {
                    deadlineFrom = piece
                    PlayerLog.add("http: esperando o pedaço $piece")
                    for (i in 0 until READAHEAD) {
                        val p = piece + i
                        if (p > lastPiece) break
                        h.setPieceDeadline(p, i * 300)
                    }
                }
            }
            waitingPiece = piece
            Thread.sleep(100)
        }
        if (closed) throw InterruptedException()
        waitingPiece = -1
    }

    /** Cópia própria do handle, pedida à sessão (nunca a do alerta). */
    private fun refind(): TorrentHandle? {
        val ih = infoHash ?: return null
        return try { session?.find(ih)?.also { handle = it } } catch (_: Throwable) { null }
    }

    companion object {
        private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "webm", "m4v", "ts")
        private const val READAHEAD = 6
    }
}
