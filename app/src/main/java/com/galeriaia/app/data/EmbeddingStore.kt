package com.galeriaia.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.galeriaia.app.ml.ClipModel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Índice em memória: ids + datas + matriz de vetores (n x 512) para busca rápida. */
class EmbeddingIndex(
    val ids: LongArray,
    val dates: LongArray,
    val vectors: FloatArray,
) {
    val size: Int get() = ids.size

    /** Similaridade de cosseno de cada foto com o vetor de busca (vetores já normalizados). */
    fun scores(query: FloatArray): FloatArray {
        val d = ClipModel.EMBED_DIM
        val out = FloatArray(size)
        for (i in 0 until size) {
            var s = 0f
            val off = i * d
            for (k in 0 until d) s += vectors[off + k] * query[k]
            out[i] = s
        }
        return out
    }

    companion object {
        val EMPTY = EmbeddingIndex(LongArray(0), LongArray(0), FloatArray(0))
    }
}

class EmbeddingStore(context: Context) :
    SQLiteOpenHelper(context, "embeddings.db", null, 1) {

    data class Entry(val id: Long, val dateTaken: Long, val vector: FloatArray)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE images (id INTEGER PRIMARY KEY, date_taken INTEGER NOT NULL, emb BLOB NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS images")
        onCreate(db)
    }

    fun allIds(): HashSet<Long> {
        val set = HashSet<Long>()
        readableDatabase.rawQuery("SELECT id FROM images", null).use { c ->
            while (c.moveToNext()) set += c.getLong(0)
        }
        return set
    }

    fun insert(entries: List<Entry>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues()
            for (e in entries) {
                cv.clear()
                cv.put("id", e.id)
                cv.put("date_taken", e.dateTaken)
                cv.put("emb", toBytes(e.vector))
                db.insertWithOnConflict("images", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.chunked(500).forEach { chunk ->
                db.execSQL("DELETE FROM images WHERE id IN (${chunk.joinToString(",")})")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM images")
    }

    fun loadIndex(): EmbeddingIndex {
        val d = ClipModel.EMBED_DIM
        readableDatabase.rawQuery("SELECT id, date_taken, emb FROM images", null).use { c ->
            val n = c.count
            val ids = LongArray(n)
            val dates = LongArray(n)
            val vectors = FloatArray(n * d)
            var i = 0
            while (c.moveToNext()) {
                ids[i] = c.getLong(0)
                dates[i] = c.getLong(1)
                ByteBuffer.wrap(c.getBlob(2)).order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer().get(vectors, i * d, d)
                i++
            }
            return EmbeddingIndex(ids, dates, vectors)
        }
    }

    private fun toBytes(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(v)
        return bb.array()
    }
}
