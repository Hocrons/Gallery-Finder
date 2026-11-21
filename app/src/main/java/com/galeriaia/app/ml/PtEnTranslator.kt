package com.galeriaia.app.ml

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/** Tradução português -> inglês offline (Google ML Kit). O pacote de idioma (~30 MB) é baixado uma vez. */
class PtEnTranslator {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.PORTUGUESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    suspend fun isReady(): Boolean = runCatching {
        RemoteModelManager.getInstance()
            .isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.PORTUGUESE).build())
            .await()
    }.getOrDefault(false)

    suspend fun download() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    suspend fun translate(text: String): String = translator.translate(text).await()

    fun close() = translator.close()
}
