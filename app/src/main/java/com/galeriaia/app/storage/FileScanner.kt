package com.galeriaia.app.storage

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

enum class FileCategory(val label: String) {
    VIDEO("Vídeos"),
    IMAGE("Fotos e imagens"),
    AUDIO("Áudios"),
    DOCUMENT("Documentos"),
    APK("Instaladores (APK)"),
    ARCHIVE("Compactados"),
    OTHER("Outros");

    companion object {
        private val map: Map<String, FileCategory> = buildMap {
            listOf("mp4", "mkv", "mov", "3gp", "webm", "avi", "m4v", "ts").forEach { put(it, VIDEO) }
            listOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng", "raw").forEach { put(it, IMAGE) }
            listOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr", "3ga").forEach { put(it, AUDIO) }
            listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "csv", "epub", "rtf")
                .forEach { put(it, DOCUMENT) }
            put("apk", APK); put("apks", APK); put("xapk", APK)
            listOf("zip", "rar", "7z", "tar", "gz", "bz2").forEach { put(it, ARCHIVE) }
        }

        fun of(name: String): FileCategory = map[name.substringAfterLast('.', "").lowercase()] ?: OTHER
    }
}

data class FileEntry(
    val path: String,
    val size: Long,
    val modified: Long,
    val category: FileCategory,
) {
    val name: String get() = path.substringAfterLast('/')
    val folder: String get() = path.substringBeforeLast('/')
}

class ScanResult(val files: List<FileEntry>, val emptyDirs: List<String>, val root: String)

/**
 * Lê todos os arquivos do armazenamento interno (precisa de "Acesso a todos os arquivos").
 * As pastas Android/data e Android/obb são de outros apps e o Android bloqueia; são puladas.
 * Não depende do Android (testável em JVM pura).
 */
object FileScanner {

    fun scan(root: File, skipDirs: Set<String> = emptySet(), onProgress: (Int) -> Unit = {}): ScanResult {
        val files = ArrayList<FileEntry>(100_000)
        val empty = ArrayList<String>()
        val rootPath = root.absolutePath.trimEnd('/')
        val blocked = setOf("$rootPath/Android/data", "$rootPath/Android/obb") + skipDirs
        // quantos filhos cada pasta tem (para achar pastas vazias)
        val childCount = ArrayDeque<Int>()

        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir.toString() in blocked) return FileVisitResult.SKIP_SUBTREE
                if (childCount.isNotEmpty()) childCount[childCount.lastIndex] = childCount.last() + 1
                childCount.addLast(0)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (childCount.isNotEmpty()) childCount[childCount.lastIndex] = childCount.last() + 1
                if (attrs.isRegularFile) {
                    val p = file.toString()
                    files += FileEntry(p, attrs.size(), attrs.lastModifiedTime().toMillis(), FileCategory.of(p))
                    if (files.size % 2000 == 0) onProgress(files.size)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                val n = childCount.removeLast()
                val p = dir.toString()
                if (n == 0 && p != rootPath && !p.startsWith("$rootPath/Android")) empty += p
                return FileVisitResult.CONTINUE
            }
        })
        onProgress(files.size)
        return ScanResult(files, empty, rootPath)
    }
}
