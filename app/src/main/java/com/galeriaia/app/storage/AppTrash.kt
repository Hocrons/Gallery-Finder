package com.galeriaia.app.storage

import java.io.File

data class TrashItem(
    val trashName: String,
    val originalPath: String,
    val deletedAt: Long,
    val size: Long,
) {
    val name: String get() = originalPath.substringAfterLast('/')
}

/**
 * Lixeira própria do app para arquivos: move para uma pasta oculta (instantâneo) e apaga de vez
 * depois de 30 dias. O espaço só é liberado quando a lixeira é esvaziada.
 * [onPathsChanged] avisa o Android para atualizar a galeria (MediaScanner).
 */
class AppTrash(root: File, private val onPathsChanged: (List<String>) -> Unit = {}) {

    companion object {
        const val DIR_NAME = ".GaleriaIA_Lixeira"
        const val KEEP_DAYS = 30
        private const val DAY = 24L * 60 * 60 * 1000
    }

    val dir = File(root, DIR_NAME)
    private val indexFile = File(dir, "lixeira.tsv")

    @Synchronized
    fun list(): List<TrashItem> {
        if (!indexFile.exists()) return emptyList()
        // uma linha por arquivo: nomeNaLixeira \t dataApagado \t tamanho \t caminhoOriginal
        return indexFile.readLines().mapNotNull { line ->
            val p = line.split('\t', limit = 4)
            if (p.size < 4) null
            else TrashItem(p[0], p[3], p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L)
        }.filter { File(dir, it.trashName).exists() }
    }

    private fun save(items: List<TrashItem>) {
        ensureDir()
        indexFile.writeText(items.joinToString("\n") { "${it.trashName}\t${it.deletedAt}\t${it.size}\t${it.originalPath}" })
    }

    private fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
        File(dir, ".nomedia").apply { if (!exists()) createNewFile() } // galeria ignora esta pasta
    }

    /** Move para a lixeira. Retorna os caminhos que foram movidos. */
    @Synchronized
    fun moveIn(files: List<FileEntry>, now: Long = System.currentTimeMillis()): List<String> {
        ensureDir()
        val items = list().toMutableList()
        val moved = ArrayList<String>()
        for ((i, f) in files.withIndex()) {
            val src = File(f.path)
            if (!src.exists()) continue
            val trashName = "${now}_${i}_${src.name}"
            val dst = File(dir, trashName)
            val ok = src.renameTo(dst) || runCatching { src.copyTo(dst); src.delete() }.getOrDefault(false)
            if (ok) {
                items += TrashItem(trashName, f.path, now, f.size)
                moved += f.path
            }
        }
        save(items)
        if (moved.isNotEmpty()) onPathsChanged(moved)
        return moved
    }

    /** Apaga pastas vazias (não ocupam espaço, não precisam de lixeira). */
    fun deleteEmptyDirs(dirs: List<String>): Int =
        dirs.sortedByDescending { it.length }.count { File(it).let { d -> d.isDirectory && (d.list()?.isEmpty() == true) && d.delete() } }

    @Synchronized
    fun restore(selected: List<TrashItem>): Int {
        val items = list().toMutableList()
        var n = 0
        val restored = ArrayList<String>()
        for (t in selected) {
            val src = File(dir, t.trashName)
            var dst = File(t.originalPath)
            if (dst.exists()) dst = File(dst.parentFile, "restaurado_" + dst.name)
            dst.parentFile?.mkdirs()
            val ok = src.renameTo(dst) || runCatching { src.copyTo(dst); src.delete() }.getOrDefault(false)
            if (ok) {
                items.removeAll { it.trashName == t.trashName }
                restored += dst.path
                n++
            }
        }
        save(items)
        if (restored.isNotEmpty()) onPathsChanged(restored)
        return n
    }

    /** Apaga de vez. Sem argumento = esvazia tudo. Retorna bytes liberados. */
    @Synchronized
    fun purge(selected: List<TrashItem>? = null): Long {
        val items = list()
        val toDelete = selected ?: items
        var freed = 0L
        for (t in toDelete) {
            val f = File(dir, t.trashName)
            val size = f.length()
            if (f.delete()) freed += size
        }
        val names = toDelete.map { it.trashName }.toSet()
        save(items.filter { it.trashName !in names })
        return freed
    }

    /** Apaga o que está na lixeira há mais de 30 dias. */
    fun purgeExpired(now: Long = System.currentTimeMillis()): Long =
        purge(list().filter { now - it.deletedAt > KEEP_DAYS * DAY })
}
