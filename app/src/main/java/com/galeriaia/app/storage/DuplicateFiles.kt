package com.galeriaia.app.storage

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Encontra arquivos com conteúdo idêntico (mesmo com nomes diferentes), em 3 etapas rápidas:
 * 1) mesmo tamanho  2) mesmo começo/fim  3) mesma "impressão digital" (SHA-256).
 * Arquivos até 64 MB são comparados por inteiro; maiores, por 3 trechos de 4 MB + tamanho.
 */
object DuplicateFiles {

    private const val FULL_HASH_LIMIT = 64L * 1024 * 1024
    private const val SAMPLE = 4 * 1024 * 1024
    private const val QUICK = 16 * 1024

    fun find(
        entries: List<FileEntry>,
        minSize: Long = 50 * 1024,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<List<FileEntry>> {
        val bySize = entries.filter { it.size >= minSize }.groupBy { it.size }.values.filter { it.size > 1 }
        val total = bySize.sumOf { it.size }
        var done = 0
        val result = ArrayList<List<FileEntry>>()
        for (sameSize in bySize) {
            if (isCancelled()) break
            val byQuick = sameSize.groupBy { runCatching { quickHash(File(it.path)) }.getOrNull() }
                .filterKeys { it != null }.values.filter { it.size > 1 }
            for (cand in byQuick) {
                val byFull = cand.groupBy { runCatching { fullHash(File(it.path)) }.getOrNull() }
                    .filterKeys { it != null }.values.filter { it.size > 1 }
                byFull.forEach { result += sortBest(it) }
            }
            done += sameSize.size
            onProgress(done, total)
        }
        // maior desperdício primeiro
        return result.sortedByDescending { g -> g.first().size * (g.size - 1) }
    }

    /** Primeiro = o que sugerimos manter (original mais provável). */
    fun sortBest(group: List<FileEntry>): List<FileEntry> =
        group.sortedWith(compareBy<FileEntry> { penalty(it.path) }.thenBy { it.modified }.thenBy { it.path.length })

    private fun penalty(path: String): Int {
        val p = path.lowercase()
        var score = 0
        if ("/sent/" in p) score += 5            // cópia do que você enviou no WhatsApp
        if ("/download" in p) score += 3
        if ("whatsapp" in p) score += 2
        if ("/." in p) score += 4                // pastas ocultas / lixeiras
        if ("/dcim/camera/" in p) score -= 3     // original da câmera
        if (Regex("""\(\d+\)\.\w+$""").containsMatchIn(p)) score += 2  // "foto (1).jpg"
        return score
    }

    private fun quickHash(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val buf = ByteArray(minOf(QUICK.toLong(), len).toInt())
            raf.readFully(buf); md.update(buf)
            if (len > QUICK) {
                raf.seek(len - buf.size); raf.readFully(buf); md.update(buf)
            }
        }
        return hex(md.digest())
    }

    private fun fullHash(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val len = f.length()
        if (len <= FULL_HASH_LIMIT) {
            f.inputStream().use { input ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
        } else {
            RandomAccessFile(f, "r").use { raf ->
                val buf = ByteArray(SAMPLE)
                for (pos in longArrayOf(0, len / 2 - SAMPLE / 2, len - SAMPLE)) {
                    raf.seek(pos); raf.readFully(buf); md.update(buf)
                }
            }
            md.update(len.toString().toByteArray())
        }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
