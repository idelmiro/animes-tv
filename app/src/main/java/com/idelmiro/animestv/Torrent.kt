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
 * Pasta de downloads com limite de espaço. Cada episódio fica em uma subpasta própria;
 * o arquivo ".pronto" marca um download completo e guarda o caminho do vídeo.
 */
object Downloads {
    /** Limite total: 1 GB. */
    const val CAP = 1024L * 1024 * 1024

    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "downloads").apply { mkdirs() }

    fun dirFor(ctx: Context, hash: String, fileIndex: Int) = File(root(ctx), "$hash-$fileIndex")

    private fun marker(dir: File) = File(dir, ".pronto")

    /** Vídeo já baixado por completo, se existir. */
    fun completed(dir: File): File? {
        val m = marker(dir)
        if (!m.exists()) return null
        return File(m.readText().trim()).takeIf { it.exists() }
    }

    fun markDone(dir: File, video: File) {
        marker(dir).writeText(video.absolutePath)
        dir.setLastModified(System.currentTimeMillis())
    }

    fun size(f: File): Long = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { size(it) } else f.length()

    fun used(ctx: Context): Long = size(root(ctx))

    fun count(ctx: Context): Int = root(ctx).listFiles()?.count { it.isDirectory && completed(it) != null } ?: 0

    fun clearAll(ctx: Context) {
        root(ctx).listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Libera espaço para um arquivo de [need] bytes: primeiro apaga downloads incompletos,
     * depois os episódios mais antigos, até caber no limite. Devolve quantos apagou.
     */
    fun makeRoom(ctx: Context, need: Long, keep: File): Int {
        var removed = 0
        val dirs = (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory && it != keep }
        for (d in dirs.filter { completed(it) == null }) {
            d.deleteRecursively(); removed++
        }
        val done = dirs.filter { it.exists() }.sortedBy { it.lastModified() }
        for (d in done) {
            if (used(ctx) + need <= CAP) break
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
            val d = Downloads.dirFor(ctx, hash, idx)
            dir = d

            // Já baixado antes: toca direto do arquivo, sem rede.
            Downloads.completed(d)?.let { f ->
                complete = true
                video = f
                d.setLastModified(System.currentTimeMillis())
                status(Status(Phase.COMPLETE, "Episódio já estava baixado. Tocando sem usar a internet.", done = f.length(), total = f.length()))
                stopSession()
                onReady(f.absolutePath)
                return
            }

            status(Status(Phase.SPACE, "Arquivo de ${Downloads.human(fileSize)}. Conferindo o espaço…", total = fileSize))
            val removed: Int
            if (fileSize > Downloads.CAP) {
                // Acima do limite: só baixa se o usuário confirmar, e aí apaga todos os salvos.
                val others = (Downloads.root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory && it != d }
                val savedCount = others.count { Downloads.completed(it) != null }
                val savedBytes = others.sumOf { Downloads.size(it) }
                status(Status(Phase.SPACE, "O arquivo tem ${Downloads.human(fileSize)}, mais que o limite de 1 GB. Aguardando sua confirmação…", total = fileSize))
                val latch = java.util.concurrent.CountDownLatch(1)
                var ok = false
                onOversize(fileSize, savedCount, savedBytes) { yes -> ok = yes; latch.countDown() }
                while (!closed && !latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) { }
                if (closed) return
                if (!ok) {
                    fail("Download cancelado: o arquivo tem ${Downloads.human(fileSize)}, mais que o limite de 1 GB.")
                    return
                }
                others.forEach { it.deleteRecursively() }
                removed = savedCount
            } else {
                removed = Downloads.makeRoom(ctx, fileSize, d)
            }
            d.mkdirs()
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
                        if (type == AlertType.ADD_TORRENT) {
                            handle = h
                            h.unsetFlags(TorrentFlags.AUTO_MANAGED)
                            h.resume()
                        } else finished(h)
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
                val h = handle ?: continue
                if (!h.isValid) continue
                val st = h.status()
                val done = h.fileProgress().getOrElse(idx) { 0L }
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
            val h = handle
            if (h != null && h.isValid) {
                if (h.havePiece(piece)) {
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

    companion object {
        private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "webm", "m4v", "ts")
        private const val READAHEAD = 6
    }
}
