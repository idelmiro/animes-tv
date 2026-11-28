package com.idelmiro.animestv

import android.content.Context
import android.os.StatFs
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

    // ---------- memória interna temporária (quando os downloads vão para pendrive/HD) ----------
    //
    // A USB da TV não dá conta de gravar o download e ler o vídeo ao mesmo tempo. Então o
    // episódio baixa e toca na memória interna e, quando o download termina e o player fecha,
    // é copiado para o pendrive/HD em segundo plano.

    /** Downloads em andamento na memória interna (quando o local escolhido é pendrive/HD). */
    fun stagingRoot(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "downloads-temp").apply { mkdirs() }

    fun stagingFor(ctx: Context, hash: String, fileIndex: Int) = File(stagingRoot(ctx), "$hash-$fileIndex")

    /** O local escolhido é um pendrive/HD (e não a memória interna)? */
    fun usesExternal(ctx: Context) = Prefs(ctx).downloadPath != null

    fun isStaging(ctx: Context, dir: File) = dir.parentFile?.absolutePath == stagingRoot(ctx).absolutePath

    /** Pastas que o player está usando agora (não podem ser movidas nem apagadas). */
    val inUse: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun stagedDirs(ctx: Context) = (stagingRoot(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory }

    /**
     * Deixa na memória interna no máximo um outro download pela metade (o mais recente), para
     * não lotar a TV com episódios abandonados.
     */
    fun trimStaging(ctx: Context, keep: File) {
        stagedDirs(ctx).filter { it != keep && completed(it) == null && it.absolutePath !in inUse }
            .sortedByDescending { it.lastModified() }.drop(1).forEach { it.deleteRecursively() }
    }

    /** Copia uma pasta inteira (blocos grandes: bem mais rápido na USB). */
    fun copyTree(from: File, to: File, skip: Set<String> = emptySet(), progress: ((Long) -> Unit)? = null): Boolean {
        val buf = ByteArray(1024 * 1024)
        var copied = 0L
        fun walk(src: File, dst: File) {
            if (src.isDirectory) {
                dst.mkdirs()
                for (c in src.listFiles() ?: emptyArray()) if (c.name !in skip) walk(c, File(dst, c.name))
            } else {
                src.inputStream().use { inp ->
                    dst.outputStream().use { out ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            copied += n
                            progress?.invoke(copied)
                        }
                    }
                }
            }
        }
        return try { walk(from, to); true } catch (e: Throwable) {
            PlayerLog.add("downloads: erro ao copiar ${from.name}: $e"); false
        }
    }

    /**
     * Leva um episódio completo da memória interna para o pendrive/HD. Devolve true se moveu
     * (ou se já estava lá).
     */
    @Synchronized
    fun moveToFinal(ctx: Context, stage: File): Boolean {
        val video = completed(stage) ?: return false
        if (stage.absolutePath in inUse || !available(ctx) || !usesExternal(ctx)) return false
        val final = File(root(ctx), stage.name)
        if (completed(final) != null) {
            stage.deleteRecursively()
            return true
        }
        final.deleteRecursively()
        val size = size(stage)
        makeRoom(ctx, size, final)
        if (StatFs(root(ctx).absolutePath).availableBytes < size + 50L * 1024 * 1024) return false
        // O vídeo primeiro; .info e .pronto por último (sem eles a pasta não conta como episódio).
        if (!copyTree(stage, final, setOf(".pronto", ".info", ".resume", ".info.tmp"))) {
            final.deleteRecursively()
            return false
        }
        try { info(stage).takeIf { it.exists() }?.copyTo(info(final), true) } catch (_: Throwable) {}
        markDone(final, File(final.absolutePath + video.absolutePath.removePrefix(stage.absolutePath)))
        if (stage.absolutePath !in inUse) stage.deleteRecursively()
        return true
    }

    private val moving = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Em segundo plano: move para o pendrive/HD os episódios completos que estão na memória interna. */
    fun finishMoves(ctx: Context) {
        if (!usesExternal(ctx) || !moving.compareAndSet(false, true)) return
        Thread({
            try {
                for (d in stagedDirs(ctx)) {
                    if (completed(d) != null) moveToFinal(ctx, d)
                    else if (readInfo(d) == null && d.absolutePath !in inUse &&
                        System.currentTimeMillis() - d.lastModified() > 6 * 3600_000L) d.deleteRecursively()
                }
            } catch (_: Throwable) {
            } finally {
                moving.set(false)
            }
        }, "downloads-move").start()
    }

    /** Informações do torrent (para continuar sem esperar alguém mandá-las de novo). */
    fun metaFile(dir: File) = File(dir, ".torrent")
    /** Mapa do que já foi baixado (para continuar sem reler o arquivo inteiro). */
    fun resumeFile(dir: File) = File(dir, ".resume")

    /** Pasta de um download anterior do mesmo torrent que guardou as informações dele. */
    fun findMeta(ctx: Context, hash: String): File? {
        val dirs = stagedDirs(ctx) + if (available(ctx)) (root(ctx).listFiles() ?: emptyArray()).toList() else emptyList()
        return dirs.firstOrNull {
            it.isDirectory && it.name.startsWith("$hash-", ignoreCase = true) && metaFile(it).length() > 0
        }?.let { metaFile(it) }
    }

    /** Grava sem deixar o arquivo pela metade se o app for fechado no meio. */
    fun writeAtomic(f: File, data: ByteArray) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Throwable) {
        }
    }

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

    private fun readInfo(dir: File): org.json.JSONObject? =
        try { org.json.JSONObject(info(dir).readText()) } catch (_: Throwable) { null }

    /** Atualiza o .info do download (mantém os campos que já existiam). */
    fun updateInfo(dir: File, vararg fields: Pair<String, Any>) {
        try {
            val o = readInfo(dir) ?: org.json.JSONObject()
            for ((k, v) in fields) o.put(k, v)
            info(dir).writeText(o.toString())
        } catch (_: Throwable) {
        }
    }

    /** Guarda de qual anime/episódio é o download, para achar depois sem buscar no Nyaa. */
    fun writeInfo(dir: File, animeId: Int, title: String, episode: Int, release: String, size: Long, magnet: String) =
        updateInfo(dir, "animeId" to animeId, "title" to title, "episode" to episode,
            "release" to release, "size" to size, "magnet" to magnet)

    /** Ponto em que parou de assistir (segundos; 0 = do começo). */
    fun savePosition(dir: File, seconds: Long) = updateInfo(dir, "pos" to seconds)

    data class Saved(
        val dir: File,
        /** Vídeo completo; null se o download não terminou. */
        val video: File?,
        val animeId: Int,
        val episode: Int,
        val release: String,
        val size: Long,
        val magnet: String?,
        /** Onde parou de assistir, em segundos. */
        val pos: Long,
        /** Porcentagem baixada (só faz sentido se incompleto). */
        val percent: Int
    ) {
        val complete get() = video != null
    }

    /** Episódios guardados, completos ou em andamento (com a informação de anime/episódio). */
    fun saved(ctx: Context): List<Saved> {
        val main = if (available(ctx)) (root(ctx).listFiles() ?: emptyArray()).toList() else emptyList()
        val staged = if (usesExternal(ctx)) stagedDirs(ctx) else emptyList()
        // Se o mesmo episódio está nos dois lugares, vale o da memória interna (é o mais novo).
        val names = staged.map { it.name }.toSet()
        return (staged + main.filter { it.name !in names }).mapNotNull { d ->
            val o = readInfo(d) ?: return@mapNotNull null
            val v = completed(d)
            val magnet = o.optString("magnet").takeIf { it.isNotBlank() }
            if (v == null && magnet == null) return@mapNotNull null
            try {
                Saved(d, v, o.getInt("animeId"), o.getInt("episode"), o.optString("release", v?.name ?: ""),
                    o.optLong("size", v?.length() ?: 0L), magnet, o.optLong("pos", 0L),
                    if (v != null) 100 else o.optInt("percent", 0))
            } catch (_: Throwable) { null }
        }
    }

    fun savedFor(ctx: Context, animeId: Int, episode: Int) = saved(ctx).filter { it.animeId == animeId && it.episode == episode }

    fun size(f: File): Long = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { size(it) } else f.length()

    fun used(ctx: Context): Long = if (available(ctx)) size(root(ctx)) else 0L

    fun count(ctx: Context): Int = (if (!available(ctx)) 0 else root(ctx).listFiles()?.count { it.isDirectory && completed(it) != null } ?: 0) +
        (if (usesExternal(ctx)) stagedDirs(ctx).count { completed(it) != null } else 0)

    fun clearAll(ctx: Context) {
        root(ctx).listFiles()?.forEach { it.deleteRecursively() }
        stagedDirs(ctx).filter { it.absolutePath !in inUse }.forEach { it.deleteRecursively() }
    }

    /**
     * Libera espaço para um arquivo de [need] bytes: primeiro apaga downloads incompletos,
     * depois os episódios usados há mais tempo, até caber no limite. Devolve quantos apagou.
     */
    fun makeRoom(ctx: Context, need: Long, keep: File): Int {
        var removed = 0
        val limit = cap(ctx)
        val dirs = (root(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory && it != keep }
        // Incompletos sem como continuar (sem link guardado) não servem para nada.
        for (d in dirs.filter { completed(it) == null && readInfo(it)?.optString("magnet").isNullOrBlank() }) {
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
        val waiting: Boolean = false,
        /** Bytes já baixados sem buracos a partir do ponto que o player está lendo. */
        val ahead: Long = 0
    )

    @Volatile private var closed = false
    @Volatile private var complete = false
    @Volatile private var waitingPiece = -1
    /** Onde a reprodução está lendo (posição da conexão mais recente do player). */
    @Volatile private var readPos = 0L
    @Volatile private var lastDone = 0L
    /**
     * Posição de cada conexão aberta pelo player. Ele costuma abrir mais de uma ao mesmo tempo
     * (por exemplo, o começo do vídeo e o índice que fica no final do arquivo); todas precisam
     * dos seus pedaços com urgência, senão a reprodução não começa.
     */
    private val readers = ConcurrentHashMap<Long, Long>()
    private val readerSeq = AtomicLong()
    private var windowKey = ""
    private var fileIndex = 0
    @Volatile private var resumeLatch: CountDownLatch? = null

    /** Pasta do episódio (para guardar o ponto em que parou). */
    val folder: File? get() = dir
    @Volatile private var handle: TorrentHandle? = null
    private var session: SessionManager? = null
    private var server: ServerSocket? = null
    private var dir: File? = null
    private var video: File? = null
    private var fileOffset = 0L
    private var fileSize = 0L
    private var pieceLength = 1
    private var lastPiece = 0
    private var firstPiece = 0
    private var infoHash: org.libtorrent4j.Sha1Hash? = null

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
            // Se o player anterior ainda está encerrando o download dele, espera terminar
            // (dois downloads mexendo no mesmo arquivo travam um ao outro).
            var waited = 0
            while (stopping.get() > 0 && waited < 200 && !closed) {
                if (waited == 0) status(Status(Phase.METADATA, "Encerrando o download anterior…"))
                Thread.sleep(100); waited++
            }
            if (closed) return
            val s = SessionManager()
            session = s
            // Gravação simples em disco (sem mapear o arquivo na memória): mais leve para a TV.
            val params = SessionParams()
            params.setPosixDiskIO()
            s.start(params)
            PlayerLog.add("torrent: sessão iniciada")
            // Torrent já começado antes: as informações estão guardadas na pasta dele.
            val known = magnetHash(magnet)?.let { Downloads.findMeta(ctx, it) }
            var data = known?.let { try { it.readBytes() } catch (_: Throwable) { null } }
            if (data != null) PlayerLog.add("torrent: informações do torrent lidas de ${known!!.parentFile?.name}")
            else {
                status(Status(Phase.METADATA, "Obtendo as informações do torrent (procurando quem tem o arquivo)…"))
                val tmp = File(ctx.cacheDir, "magnet").apply { mkdirs() }
                data = s.fetchMagnet(magnet, 90, tmp)
            }
            if (closed) return
            if (data == null) {
                fail("Ninguém respondeu com as informações do torrent em 90 segundos. Escolha outro lançamento (com mais seeds).")
                return
            }
            val ti = TorrentInfo.bdecode(data)
            val fs = ti.files()
            val idx = chooseFile(ti)
            PlayerLog.add("torrent: ${ti.numFiles()} arquivo(s), escolhido #$idx ${fs.fileName(idx)} (${fs.fileSize(idx)} bytes), pedaço ${ti.pieceLength()} bytes")
            fileIndex = idx
            fileOffset = fs.fileOffset(idx)
            fileSize = fs.fileSize(idx)
            pieceLength = ti.pieceLength()
            lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
            firstPiece = (fileOffset / pieceLength).toInt()
            val hash = ti.infoHash().toHex()
            infoHash = ti.infoHash()
            if (!Downloads.available(ctx)) {
                fail("O pendrive escolhido para os downloads não foi encontrado. Conecte-o de novo ou mude o local em Ajustes → Downloads.")
                return
            }
            val final = Downloads.dirFor(ctx, hash, idx)
            val stage = Downloads.stagingFor(ctx, hash, idx)
            val d = final

            // Já baixado antes: toca direto do arquivo, sem rede.
            for (x in listOf(stage, final)) Downloads.completed(x)?.let { f ->
                dir = x
                Downloads.inUse.add(x.absolutePath)
                complete = true
                video = f
                Downloads.touch(x)
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
                // Ao continuar um download, o que já está na pasta não conta de novo.
                removed = Downloads.makeRoom(ctx, maxOf(0L, fileSize - Downloads.size(d)), d)
            }
            if (removed > 0) status(Status(Phase.SPACE, "Apaguei $removed episódio(s) antigo(s) para liberar espaço.", total = fileSize))
            workDir(final, stage).let { dd -> return runIn(s, ti, idx, hash, dd, data) }
        } catch (e: InterruptedException) {
        } catch (e: Throwable) {
            PlayerLog.add("torrent: exceção $e")
            if (!closed) fail("Erro no download: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Onde baixar: com pendrive/HD escolhido, baixa na memória interna (se couber) e move
     * depois. Um download pela metade que já estava no pendrive/HD é trazido para cá antes.
     */
    private fun workDir(final: File, stage: File): File {
        if (!Downloads.usesExternal(ctx)) return final
        if (Downloads.size(stage) > 0) return stage
        if (Prefs(ctx).downloadDirect) {
            PlayerLog.add("torrent: baixando direto no disco externo")
            return final
        }
        Downloads.trimStaging(ctx, stage)
        val free = try { StatFs(Downloads.stagingRoot(ctx).absolutePath).availableBytes } catch (_: Throwable) { 0L }
        if (free < fileSize + 300L * 1024 * 1024) {
            PlayerLog.add("torrent: memória interna sem espaço (${Downloads.human(free)} livres); baixando direto no pendrive/HD")
            return final
        }
        val partial = Downloads.size(final)
        if (partial > 0 && Downloads.completed(final) == null) {
            val msg = "Trazendo para a memória interna o que já foi baixado"
            var last = -1
            val ok = Downloads.copyTree(final, stage) { n ->
                val pct = (n * 100 / partial).toInt()
                if (pct != last) { last = pct; status(Status(Phase.SPACE, "$msg… $pct%", total = fileSize)) }
                if (closed) throw InterruptedException()
            }
            if (!ok) {
                stage.deleteRecursively()
                return final
            }
            final.deleteRecursively()
        }
        PlayerLog.add("torrent: baixando na memória interna (vai para o pendrive/HD quando terminar)")
        return stage
    }

    private fun runIn(s: SessionManager, ti: TorrentInfo, idx: Int, hash: String, d: File, data: ByteArray) {
        try {
            if (closed) return
            dir = d
            Downloads.inUse.add(d.absolutePath)
            val fs = ti.files()
            d.mkdirs()
            Downloads.writeInfo(d, animeId, animeTitle, episode, release, fileSize, magnet)
            val meta = Downloads.metaFile(d)
            if (meta.length() == 0L) Downloads.writeAtomic(meta, data)
            val resume = Downloads.resumeFile(d).takeIf { it.length() > 0 }
            val free = StatFs(d.absolutePath).availableBytes
            if (free < fileSize - Downloads.size(d) + 50L * 1024 * 1024) {
                fail("A TV não tem espaço livre suficiente (${Downloads.human(free)} livres, o episódio tem ${Downloads.human(fileSize)}).")
                return
            }
            video = File(fs.filePath(idx, d.absolutePath))
            s.addListener(object : AlertListener {
                override fun types(): IntArray? = null
                override fun alert(alert: Alert<*>) {
                    try {
                        val type = alert.type()
                        if (type != AlertType.ADD_TORRENT && type != AlertType.TORRENT_FINISHED &&
                            type != AlertType.SAVE_RESUME_DATA && type != AlertType.SAVE_RESUME_DATA_FAILED) return
                        val h = (alert as? TorrentAlert<*>)?.handle() ?: return
                        if (!h.isValid || h.infoHash().toHex() != hash) return
                        if (type == AlertType.SAVE_RESUME_DATA) {
                            if (!complete) Downloads.writeAtomic(Downloads.resumeFile(d),
                                AddTorrentParams.writeResumeDataBuf((alert as SaveResumeDataAlert).params()))
                            resumeLatch?.countDown()
                            return
                        }
                        if (type == AlertType.SAVE_RESUME_DATA_FAILED) {
                            PlayerLog.add("torrent: não consegui guardar o andamento")
                            resumeLatch?.countDown()
                            return
                        }
                        PlayerLog.add("torrent: alerta $type")
                        // O handle que vem no alerta deixa de valer quando o alerta é descartado
                        // (usá-lo depois derruba o app). Pega uma cópia própria pela sessão.
                        val own = s.find(ti.infoHash()) ?: return
                        if (type == AlertType.ADD_TORRENT) {
                            handle = own
                            own.unsetFlags(TorrentFlags.AUTO_MANAGED)
                            own.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
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
            s.download(ti, d, resume, prio, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            PlayerLog.add("torrent: download pedido em ${d.absolutePath}" + if (resume != null) " (continuando de onde parou)" else "")

            val srv = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = srv
            Thread({ serve(srv) }, "torrent-http").start()
            val name = URLEncoder.encode(video!!.name, "UTF-8").replace("+", "%20")
            PlayerLog.add("http: servidor na porta ${srv.localPort}")
            onReady("http://127.0.0.1:${srv.localPort}/$name")

            // Atualiza o andamento a cada segundo, enquanto baixa.
            var tick = 0
            while (!closed && !complete) {
                Thread.sleep(1000)
                tick++
                val h = handle ?: refind() ?: continue
                val st = try { h.status() } catch (e: RuntimeException) { handle = null; continue }
                val state = st.state().name
                val checking = state.contains("CHECKING")
                val done = if (checking) lastDone else try { h.fileProgress().getOrElse(idx) { 0L } } catch (e: RuntimeException) { lastDone }
                lastDone = done
                readers.maxByOrNull { it.key }?.let { readPos = it.value }
                val ahead = try { keepWindow(h); aheadOf(h) } catch (e: RuntimeException) { 0L }
                if (tick % 15 == 0) PlayerLog.add("torrent: $state, ${st.numPeers()} pessoa(s), ${st.downloadRate() / 1024} KB/s, ${if (fileSize > 0) done * 100 / fileSize else 0}%, lendo ${readers.values.joinToString { Downloads.human(it) }}")
                // Guarda o andamento de tempos em tempos (se a TV desligar, continua daqui).
                if (tick % 30 == 0 && !checking) {
                    try { h.saveResumeData() } catch (_: RuntimeException) {}
                    if (fileSize > 0) Downloads.updateInfo(d, "percent" to (done * 100 / fileSize).toInt())
                }
                val phase = if (st.numPeers() == 0 || checking) Phase.CONNECTING else Phase.DOWNLOADING
                val msg = when {
                    checking -> "Conferindo o que já tinha sido baixado… ${(st.progress() * 100).toInt()}%"
                    phase == Phase.CONNECTING -> "Conectando às pessoas que têm o episódio…"
                    else -> "Baixando o episódio…"
                }
                status(Status(phase, msg, st.numPeers(), st.numSeeds(), st.downloadRate(), st.uploadRate(), done, fileSize, waitingPiece >= 0, ahead))
            }
        } catch (e: InterruptedException) {
        } catch (e: Throwable) {
            PlayerLog.add("torrent: exceção $e")
            if (!closed) fail("Erro no download: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun pieceOf(pos: Long) = ((fileOffset + pos) / pieceLength).toInt().coerceIn(firstPiece, lastPiece)

    /**
     * Mantém o download concentrado onde o player está lendo: os próximos ~48 MB da conexão
     * mais recente e alguns pedaços de cada outra conexão aberta (por exemplo, o índice no
     * final do arquivo) ficam com prioridade máxima e prazo curto; o resto do episódio baixa
     * depois, com prioridade baixa.
     */
    @Synchronized
    private fun keepWindow(h: TorrentHandle) {
        val curs = readers.entries.sortedByDescending { it.key }.map { pieceOf(it.value) }.distinct()
            .ifEmpty { listOf(pieceOf(readPos)) }
        val key = curs.joinToString(",")
        if (key == windowKey) return
        windowKey = key
        val size = (WINDOW_BYTES / pieceLength).toInt().coerceIn(8, 400)
        val total = h.torrentFile()?.numPieces() ?: (lastPiece + 1)
        val prio = Array(total) { Priority.IGNORE }
        for (p in firstPiece..lastPiece) prio[p] = Priority.LOW
        val ranges = curs.mapIndexed { i, cur -> cur..minOf(lastPiece, cur + if (i == 0) size else READAHEAD * 2) }
        for (r in ranges) for (p in r) prio[p] = Priority.TOP_PRIORITY
        h.prioritizePieces(prio)
        h.clearPieceDeadlines()
        // Os primeiros pedaços de cada conexão recebem os prazos mais curtos.
        for (r in ranges) {
            var k = 0
            for (p in r) if (!h.havePiece(p)) h.setPieceDeadline(p, 150 * k++)
        }
    }

    /** Quantos bytes à frente do ponto de reprodução já estão baixados sem buracos. */
    private fun aheadOf(h: TorrentHandle): Long {
        val pos = readPos
        var p = pieceOf(pos)
        var n = 0
        while (p <= lastPiece && h.havePiece(p) && n++ < 2000) p++
        val aheadEnd = minOf(fileSize, p.toLong() * pieceLength - fileOffset)
        return maxOf(0L, aheadEnd - pos)
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
        if (d != null && v != null) {
            Downloads.markDone(d, v)
            Downloads.resumeFile(d).delete()
        }
        status(Status(Phase.COMPLETE, "Download concluído. Compartilhamento encerrado.", done = fileSize, total = fileSize))
        Thread { stopSession() }.start()
    }

    /**
     * Fecha tudo e para de compartilhar. Se o download não terminou, o que já foi baixado
     * fica guardado para continuar depois (o próximo download conferirá e aproveitará).
     */
    fun stop() {
        if (closed) return
        closed = true
        try { server?.close() } catch (_: Throwable) {}
        val d = dir
        if (!complete && d != null && fileSize > 0 && lastDone > 0) Downloads.updateInfo(d, "percent" to (lastDone * 100 / fileSize).toInt())
        stopping.incrementAndGet()
        Thread {
            try { stopSession() } finally { stopping.decrementAndGet() }
            if (d != null) {
                Downloads.inUse.remove(d.absolutePath)
                // Terminou na memória interna: leva para o pendrive/HD.
                if (complete && Downloads.isStaging(ctx, d)) Downloads.moveToFinal(ctx, d)
            }
        }.start()
    }

    @Synchronized
    private fun stopSession() {
        val s = session ?: return
        session = null
        try {
            val h = handle?.takeIf { it.isValid }
            if (h != null && !complete) {
                // Guarda o mapa do que já foi baixado antes de fechar.
                val latch = CountDownLatch(1)
                resumeLatch = latch
                try {
                    h.saveResumeData(TorrentHandle.FLUSH_DISK_CACHE)
                    if (!latch.await(5, TimeUnit.SECONDS)) PlayerLog.add("torrent: demorou para guardar o andamento")
                } catch (_: Throwable) {
                }
            }
            h?.let { s.remove(it) }
            s.stop()
        } catch (_: Throwable) {
        }
        PlayerLog.add("torrent: sessão encerrada")
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
        val id = readerSeq.incrementAndGet()
        readers[id] = start
        try {
            while (pos <= end && !closed) {
                val piece = ((fileOffset + pos) / pieceLength).toInt()
                readers[id] = pos
                if (!waitForPiece(piece, c)) {
                    PlayerLog.add("http: o player desistiu da conexão em ${Downloads.human(pos)}")
                    return
                }
                if (raf == null) raf = RandomAccessFile(video!!, "r")
                val pieceEnd = (piece + 1).toLong() * pieceLength - fileOffset // primeiro byte do próximo pedaço
                val chunkEnd = minOf(end + 1, pieceEnd)
                raf.seek(pos)
                while (pos < chunkEnd && !closed) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), chunkEnd - pos).toInt())
                    if (n <= 0) { Thread.sleep(50); continue }
                    out.write(buf, 0, n)
                    pos += n
                    readers[id] = pos
                }
            }
            out.flush()
        } finally {
            readers.remove(id)
            raf?.close()
        }
    }

    /**
     * Espera o pedaço chegar, com prioridade máxima para ele e os próximos.
     * Devolve false se o player fechou a conexão enquanto esperava.
     */
    private fun waitForPiece(piece: Int, c: Socket): Boolean {
        if (complete) return true
        var logged = false
        var n = 0
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
                    return true
                }
                // Garante que este pedaço está entre os urgentes (refaz se a janela mudou).
                try { keepWindow(h) } catch (_: RuntimeException) {}
                if (!logged) {
                    logged = true
                    PlayerLog.add("http: esperando o pedaço $piece")
                }
            }
            waitingPiece = piece
            Thread.sleep(100)
            if (++n % 10 == 0 && clientGone(c)) {
                if (waitingPiece == piece) waitingPiece = -1
                return false
            }
        }
        if (closed) throw InterruptedException()
        waitingPiece = -1
        return true
    }

    /** O player fechou a conexão? (lê sem esperar: -1 quer dizer que o outro lado fechou) */
    private fun clientGone(c: Socket): Boolean = try {
        c.soTimeout = 1
        c.getInputStream().read() == -1
    } catch (_: java.net.SocketTimeoutException) {
        false
    } catch (_: Throwable) {
        true
    }

    /** Cópia própria do handle, pedida à sessão (nunca a do alerta). */
    private fun refind(): TorrentHandle? {
        val ih = infoHash ?: return null
        return try { session?.find(ih)?.also { handle = it } } catch (_: Throwable) { null }
    }

    companion object {
        /** Downloads que ainda estão sendo encerrados (de um player que acabou de fechar). */
        private val stopping = AtomicInteger()

        /** Identificador (info hash) do link magnético, em hexadecimal minúsculo. */
        fun magnetHash(magnet: String): String? {
            val v = Regex("""xt=urn:btih:([0-9A-Za-z]+)""").find(magnet)?.groupValues?.get(1) ?: return null
            if (v.length == 40) return v.lowercase()
            if (v.length != 32) return null
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val out = StringBuilder()
            var buffer = 0L
            var bits = 0
            for (ch in v.uppercase()) {
                val x = alphabet.indexOf(ch)
                if (x < 0) return null
                buffer = (buffer shl 5) or x.toLong()
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    out.append(String.format("%02x", (buffer shr bits) and 0xff))
                }
            }
            return out.toString()
        }

        private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "webm", "m4v", "ts")
        private const val READAHEAD = 6
        private const val WINDOW_BYTES = 48L * 1024 * 1024
    }
}
