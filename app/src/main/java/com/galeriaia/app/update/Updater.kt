package com.galeriaia.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.galeriaia.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização direto pelo app: consulta a última versão publicada na aba Releases do GitHub,
 * baixa o APK e abre o instalador do Android. Os dados (modelo de IA e índice) são mantidos,
 * porque todas as versões são assinadas com a mesma chave.
 */
object Updater {

    const val REPO = "Hocrons/Gallery-Finder"
    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    data class Release(val build: Int, val notes: String, val apkUrl: String, val sizeBytes: Long)

    /** Retorna a versão nova, ou null se o app já está atualizado. */
    fun checkLatest(): Release? {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            check(conn.responseCode == 200) { "GitHub respondeu HTTP ${conn.responseCode}" }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val build = json.getString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            if (build <= currentBuild) return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return Release(
                        build = build,
                        notes = json.optString("body").trim(),
                        apkUrl = a.getString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    fun download(ctx: Context, release: Release, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "GaleriaIA-${release.build}.apk")
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            check(conn.responseCode in 200..299) { "Falha no download: HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read.toFloat() / total)
                    }
                }
            }
            return target
        } finally {
            conn.disconnect()
        }
    }

    /** O Android exige liberar "instalar apps desconhecidos" para este app uma única vez. */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
