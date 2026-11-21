package com.galeriaia.app.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

data class MediaImage(
    val id: Long,
    val uri: Uri,
    /** Data em que a foto foi tirada (ms). Se não existir, usa a data em que entrou no aparelho. */
    val dateTaken: Long,
    val relativePath: String?,
)

data class Album(val relativePath: String, val name: String, val count: Int)

/**
 * Acesso à galeria pelo MediaStore. Por enquanto só FOTOS; vídeos ficarão num repositório
 * separado (mesma ideia, usando MediaStore.Video) quando formos fazer essa parte.
 */
class MediaRepository(context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun uriFor(id: Long): Uri = ContentUris.withAppendedId(collection, id)

    fun queryImages(): List<MediaImage> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val list = ArrayList<MediaImage>()
        resolver.query(
            collection, projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val taken = if (c.isNull(takenCol)) 0L else c.getLong(takenCol)
                val date = if (taken > 0) taken else c.getLong(addedCol) * 1000
                list += MediaImage(id, uriFor(id), date, c.getString(pathCol))
            }
        }
        return list
    }

    /** Álbuns existentes onde o Android permite colocar fotos (dentro de DCIM/ ou Pictures/). */
    fun albums(images: List<MediaImage>): List<Album> =
        images.mapNotNull { it.relativePath }
            .filter { it.startsWith("DCIM/") || it.startsWith("Pictures/") }
            .groupingBy { it }.eachCount()
            .map { (path, count) -> Album(path, path.trimEnd('/').substringAfterLast('/'), count) }
            .sortedByDescending { it.count }

    data class Detail(val pixels: Long, val bytes: Long)

    /** Resolução e tamanho do arquivo, usados para escolher qual foto manter entre duplicadas. */
    fun details(ids: Collection<Long>): Map<Long, Detail> {
        val out = HashMap<Long, Detail>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
        )
        ids.chunked(500).forEach { chunk ->
            resolver.query(
                collection, projection,
                "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    out[c.getLong(0)] = Detail(c.getLong(1) * c.getLong(2), c.getLong(3))
                }
            }
        }
        return out
    }

    fun loadThumbnail(uri: Uri, size: Int = 320): Bitmap =
        resolver.loadThumbnail(uri, Size(size, size), null)

    /** Pede ao Android para mandar as fotos para a Lixeira (o sistema mostra a confirmação). */
    fun trashRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createTrashRequest(resolver, uris, true)

    /** Pede permissão de edição para poder mover as fotos (o sistema mostra a confirmação). */
    fun writeRequest(uris: List<Uri>): PendingIntent =
        MediaStore.createWriteRequest(resolver, uris)

    /** Move as fotos alterando a pasta delas. Retorna quantas foram movidas com sucesso. */
    fun moveTo(uris: List<Uri>, relativePath: String): Int {
        val path = relativePath.trim('/') + "/"
        val cv = ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, path) }
        var ok = 0
        for (uri in uris) {
            val updated = runCatching { resolver.update(uri, cv, null, null) }.getOrDefault(0)
            if (updated > 0) ok++
        }
        return ok
    }
}
