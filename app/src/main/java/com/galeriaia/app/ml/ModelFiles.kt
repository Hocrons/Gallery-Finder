package com.galeriaia.app.ml

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Arquivos do modelo, baixados do Hugging Face na primeira execução (não vão dentro do APK).
 *
 * Quer um download menor/indexação mais rápida? Troque o VISION por "onnx/vision_model_quantized.onnx"
 * (~89 MB). A precisão costuma cair um pouco. Se trocar, apague os dados do app para reindexar.
 */
enum class ModelFiles(val remotePath: String, val fileName: String, val approxBytes: Long) {
    VISION("onnx/vision_model.onnx", "vision_model.onnx", 352_000_000),
    TEXT("onnx/text_model_quantized.onnx", "text_model.onnx", 64_500_000),
    MERGES("merges.txt", "merges.txt", 525_000);

    val url: String get() = "$BASE_URL/$remotePath"

    companion object {
        const val BASE_URL = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main"
        val totalBytes: Long get() = entries.sumOf { it.approxBytes }

        fun dir(filesDir: File) = File(filesDir, "models").apply { mkdirs() }

        fun allPresent(filesDir: File): Boolean =
            entries.all { File(dir(filesDir), it.fileName).let { f -> f.exists() && f.length() > 0 } }
    }
}

object ModelDownloader {

    /**
     * Baixa o que estiver faltando. [onProgress] recebe (bytesBaixados, totalAproximado, nomeDoArquivo).
     * Cada arquivo é baixado para ".part" e só renomeado no fim, então um download interrompido
     * nunca deixa um modelo corrompido.
     */
    fun downloadMissing(filesDir: File, onProgress: (Long, Long, String) -> Unit) {
        val dir = ModelFiles.dir(filesDir)
        val total = ModelFiles.totalBytes
        var done = 0L
        for (mf in ModelFiles.entries) {
            val target = File(dir, mf.fileName)
            if (target.exists() && target.length() > 0) {
                done += mf.approxBytes
                onProgress(done, total, mf.fileName)
                continue
            }
            val part = File(dir, mf.fileName + ".part")
            val conn = (URL(mf.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                check(code in 200..299) { "Falha ao baixar ${mf.fileName}: HTTP $code" }
                val base = done
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var fileBytes = 0L
                        var lastReport = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            fileBytes += n
                            if (fileBytes - lastReport > 2_000_000) {
                                lastReport = fileBytes
                                onProgress(base + fileBytes, total, mf.fileName)
                            }
                        }
                    }
                }
                check(part.renameTo(target)) { "Não consegui salvar ${mf.fileName}" }
                done += mf.approxBytes
                onProgress(done, total, mf.fileName)
            } finally {
                conn.disconnect()
            }
        }
    }
}
