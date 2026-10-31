package com.idelmiro.animestv

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Lê o AndroidManifest.xml (formato binário) de outro app instalado e lista
 * quais links (esquemas, endereços, caminhos) cada tela dele aceita.
 * Serve para descobrir como abrir o Crunchyroll da TV direto num anime/episódio.
 */
object ManifestReader {

    class Filter(val actions: MutableList<String> = mutableListOf(), val data: MutableList<String> = mutableListOf())
    class Component(val tag: String, val name: String, val exported: String?, val filters: MutableList<Filter> = mutableListOf())

    private val attrIds = mapOf(
        0x01010003 to "name", 0x01010010 to "exported", 0x01010027 to "scheme", 0x01010028 to "host",
        0x01010029 to "port", 0x0101002a to "path", 0x0101002b to "pathPrefix", 0x0101002c to "pathPattern",
        0x01010026 to "mimeType", 0x010103a7 to "targetActivity"
    )

    fun report(ctx: Context, pkg: String): String {
        val comps = try {
            read(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir)
        } catch (e: Exception) {
            return "  não consegui ler $pkg: ${e.message}\n"
        }
        val sb = StringBuilder()
        val withLinks = comps.filter { c -> c.filters.any { it.data.isNotEmpty() } }
        sb.append("  ${comps.size} telas; ${withLinks.size} aceitam links:\n")
        for (c in withLinks) {
            sb.append("  • ${c.name.substringAfterLast('.')}")
            if (c.exported == "false") sb.append(" (privada)")
            sb.append('\n')
            for (f in c.filters.filter { it.data.isNotEmpty() }) {
                sb.append("     ${f.data.distinct().joinToString("  ")}\n")
            }
        }
        val launcher = comps.filter { c -> c.filters.any { f -> f.actions.any { it.endsWith("MAIN") } } }
        sb.append("  Telas de entrada: ${launcher.joinToString { it.name.substringAfterLast('.') }}\n")
        return sb.toString()
    }

    /** Todos os esquemas/hosts/caminhos encontrados, para testes automáticos. */
    fun linkPatterns(ctx: Context, pkg: String): List<String> = try {
        read(ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir)
            .flatMap { c -> c.filters.flatMap { it.data } }.distinct()
    } catch (_: Exception) {
        emptyList()
    }

    fun read(apkPath: String): List<Component> {
        val bytes = ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: throw Exception("manifesto não encontrado")
            zip.getInputStream(entry).use { it.readBytes() }
        }
        return parse(bytes)
    }

    private fun parse(bytes: ByteArray): List<Component> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var strings = arrayOf<String>()
        var resIds = IntArray(0)
        val comps = mutableListOf<Component>()
        var current: Component? = null
        var filter: Filter? = null
        var pendingData = mutableMapOf<String, MutableList<String>>()

        var pos = buf.getShort(2).toInt() and 0xFFFF // pula o cabeçalho do arquivo
        while (pos + 8 <= bytes.size) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            if (size <= 0) break
            when (type) {
                0x0001 -> strings = readStrings(buf, pos)
                0x0180 -> {
                    val n = (size - headerSize) / 4
                    resIds = IntArray(n) { buf.getInt(pos + headerSize + it * 4) }
                }
                0x0102 -> {
                    val ext = pos + headerSize
                    val tag = str(strings, buf.getInt(ext + 4))
                    val attrStart = buf.getShort(ext + 8).toInt() and 0xFFFF
                    val attrSize = buf.getShort(ext + 10).toInt() and 0xFFFF
                    val attrCount = buf.getShort(ext + 12).toInt() and 0xFFFF
                    val attrs = HashMap<String, String>()
                    for (i in 0 until attrCount) {
                        val a = ext + attrStart + i * attrSize
                        val nameIdx = buf.getInt(a + 4)
                        var name = str(strings, nameIdx)
                        if (nameIdx in resIds.indices) attrIds[resIds[nameIdx]]?.let { name = it }
                        val raw = buf.getInt(a + 8)
                        val dataType = buf.get(a + 15).toInt() and 0xFF
                        val data = buf.getInt(a + 16)
                        attrs[name] = when {
                            raw != -1 -> str(strings, raw)
                            dataType == 0x03 -> str(strings, data)
                            dataType == 0x12 -> (data != 0).toString()
                            else -> data.toString()
                        }
                    }
                    when (tag) {
                        "activity", "activity-alias" -> {
                            current = Component(tag, attrs["name"] ?: "?", attrs["exported"]).also { comps.add(it) }
                        }
                        "intent-filter" -> {
                            filter = Filter().also { current?.filters?.add(it) }
                            pendingData = mutableMapOf()
                        }
                        "action" -> filter?.actions?.add(attrs["name"] ?: "")
                        "data" -> {
                            // Em um mesmo filtro, scheme/host/path podem vir em <data> separados.
                            for (k in listOf("scheme", "host", "path", "pathPrefix", "pathPattern", "mimeType")) {
                                attrs[k]?.let { pendingData.getOrPut(k) { mutableListOf() }.add(it) }
                            }
                        }
                    }
                }
                0x0103 -> {
                    val tag = str(strings, buf.getInt(pos + headerSize + 4))
                    if (tag == "intent-filter") {
                        if (pendingData.isNotEmpty()) {
                            val schemes = pendingData["scheme"] ?: listOf("*")
                            val hosts = pendingData["host"] ?: listOf("")
                            val paths = (pendingData["path"] ?: emptyList()) +
                                (pendingData["pathPrefix"] ?: emptyList()).map { "$it*" } +
                                (pendingData["pathPattern"] ?: emptyList())
                            val mime = pendingData["mimeType"]?.let { " [${it.joinToString()}]" } ?: ""
                            for (sc in schemes) for (h in hosts) for (pa in paths.ifEmpty { listOf("") }) {
                                if ((filter?.data?.size ?: 99) < 15) filter?.data?.add("$sc://$h$pa$mime")
                            }
                        }
                        filter = null
                    }
                    if (tag == "activity" || tag == "activity-alias") current = null
                }
            }
            pos += size
        }
        return comps
    }

    private fun str(strings: Array<String>, idx: Int) = if (idx in strings.indices) strings[idx] else ""

    private fun readStrings(buf: ByteBuffer, pos: Int): Array<String> {
        val count = buf.getInt(pos + 8)
        val flags = buf.getInt(pos + 16)
        val stringsStart = buf.getInt(pos + 20)
        val utf8 = flags and 0x100 != 0
        val headerSize = buf.getShort(pos + 2).toInt() and 0xFFFF
        return Array(count) { i ->
            try {
                val off = pos + stringsStart + buf.getInt(pos + headerSize + i * 4)
                if (utf8) {
                    var p = off
                    p += if (buf.get(p).toInt() and 0x80 != 0) 2 else 1 // tamanho em UTF-16
                    var len = buf.get(p).toInt() and 0xFF
                    if (len and 0x80 != 0) {
                        len = ((len and 0x7F) shl 8) or (buf.get(p + 1).toInt() and 0xFF)
                        p += 2
                    } else p += 1
                    String(buf.array(), p, len, Charsets.UTF_8)
                } else {
                    var p = off
                    var len = buf.getShort(p).toInt() and 0xFFFF
                    if (len and 0x8000 != 0) {
                        len = ((len and 0x7FFF) shl 16) or (buf.getShort(p + 2).toInt() and 0xFFFF)
                        p += 4
                    } else p += 2
                    val chars = CharArray(len) { buf.getChar(p + it * 2) }
                    String(chars)
                }
            } catch (_: Exception) {
                ""
            }
        }
    }
}
