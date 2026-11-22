package com.galeriaia.app.storage

/** Um grupo de sugestão de limpeza (ex.: "APKs já instalados"). */
data class CleanupGroup(
    val key: String,
    val title: String,
    val description: String,
    val items: List<FileEntry>,
    /** Pastas (só para "pastas vazias"). */
    val dirs: List<String> = emptyList(),
) {
    val totalSize: Long get() = items.sumOf { it.size }
}

/**
 * Sugestões de limpeza a partir da varredura. Não depende do Android:
 * [isApkInstalled] diz se o app daquele APK já está instalado.
 */
object Cleanup {

    private const val DAY = 24L * 60 * 60 * 1000

    fun suggest(
        scan: ScanResult,
        now: Long,
        isApkInstalled: (FileEntry) -> Boolean,
    ): List<CleanupGroup> {
        val root = scan.root
        val groups = ArrayList<CleanupGroup>()

        val apks = scan.files.filter { it.category == FileCategory.APK }
        val (installed, notInstalled) = apks.partition(isApkInstalled)
        if (installed.isNotEmpty()) groups += CleanupGroup(
            "apk_installed", "Instaladores de apps já instalados",
            "Arquivos .apk de apps que já estão no seu celular. Não servem mais.",
            installed.sortedByDescending { it.size },
        )
        if (notInstalled.isNotEmpty()) groups += CleanupGroup(
            "apk_other", "Outros instaladores (APK)",
            "Instaladores de apps que não estão instalados. Confira antes de apagar.",
            notInstalled.sortedByDescending { it.size },
        )

        val oldDownloads = scan.files.filter {
            it.path.startsWith("$root/Download/") && now - it.modified > 90 * DAY
        }
        if (oldDownloads.isNotEmpty()) groups += CleanupGroup(
            "downloads_old", "Downloads com mais de 3 meses",
            "Arquivos baixados há muito tempo, geralmente esquecidos.",
            oldDownloads.sortedByDescending { it.size },
        )

        val sent = scan.files.filter {
            val p = it.path
            ("/WhatsApp/Media/" in p || "/WhatsApp Business/Media/" in p) && "/Sent/" in p
        }
        if (sent.isNotEmpty()) groups += CleanupGroup(
            "whatsapp_sent", "Mídias que você enviou no WhatsApp",
            "Cópias guardadas do que você mandou. O original continua na sua galeria/câmera.",
            sent.sortedByDescending { it.size },
        )

        val thumbs = scan.files.filter { "/.thumbnails/" in it.path || "/.Thumbnails/" in it.path }
        if (thumbs.isNotEmpty()) groups += CleanupGroup(
            "thumbnails", "Miniaturas antigas",
            "Cache de miniaturas. O Android recria se precisar.",
            thumbs.sortedByDescending { it.size },
        )

        if (scan.emptyDirs.isNotEmpty()) groups += CleanupGroup(
            "empty_dirs", "Pastas vazias",
            "Não ocupam espaço, mas deixam o armazenamento bagunçado.",
            emptyList(),
            dirs = scan.emptyDirs.sorted(),
        )
        return groups
    }
}
