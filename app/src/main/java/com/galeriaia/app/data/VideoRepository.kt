package com.galeriaia.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore

data class MediaVideo(
    val id: Long,
    val uri: Uri,
    val dateTaken: Long,
    val durationMs: Long,
    val size: Long,
)

/** Vídeos da galeria (MediaStore.Video), separados das fotos. */
class VideoRepository(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    val collection: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun uriFor(id: Long): Uri = ContentUris.withAppendedId(collection, id)

    fun queryVideos(): List<MediaVideo> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
        )
        val list = ArrayList<MediaVideo>()
        resolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val taken = if (c.isNull(1)) 0L else c.getLong(1)
                val date = if (taken > 0) taken else c.getLong(2) * 1000
                list += MediaVideo(id, uriFor(id), date, if (c.isNull(3)) 0L else c.getLong(3), c.getLong(4))
            }
        }
        return list
    }

    /** Três quadros do vídeo (início, meio e fim) já reduzidos, para a IA. */
    fun frames(uri: Uri, durationMs: Long): List<Bitmap> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = if (durationMs > 0) durationMs
            else r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val times = if (dur <= 0) listOf(0L) else listOf(0.15, 0.5, 0.85).map { (dur * it).toLong() * 1000 }
            return times.mapNotNull { t ->
                runCatching {
                    r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                }.getOrNull()
            }
        } finally {
            runCatching { r.release() }
        }
    }

    fun trashRequest(uris: List<Uri>) = MediaStore.createTrashRequest(resolver, uris, true)
}
