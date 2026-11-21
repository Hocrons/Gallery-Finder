package com.galeriaia.app.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * CLIP ViT-B/32 rodando no celular via ONNX Runtime.
 * Transforma imagens e textos em vetores de 512 números; quanto mais parecidos os vetores,
 * mais a foto combina com a descrição.
 */
class ClipModel(private val modelDir: File) : Closeable {

    companion object {
        const val IMAGE_SIZE = 224
        const val EMBED_DIM = 512
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    private val env = OrtEnvironment.getEnvironment()

    private fun options() = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
    }

    private val vision: OrtSession by lazy {
        env.createSession(File(modelDir, ModelFiles.VISION.fileName).absolutePath, options())
    }
    private val text: OrtSession by lazy {
        env.createSession(File(modelDir, ModelFiles.TEXT.fileName).absolutePath, options())
    }
    val tokenizer: ClipTokenizer by lazy {
        ClipTokenizer(File(modelDir, ModelFiles.MERGES.fileName).readLines())
    }

    /** Redimensiona (lado menor = 224), corta o centro e normaliza no formato [1,3,224,224]. */
    fun preprocess(src: Bitmap): FloatArray {
        val bmp = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
        val scale = IMAGE_SIZE.toFloat() / minOf(bmp.width, bmp.height)
        val w = maxOf(IMAGE_SIZE, Math.round(bmp.width * scale))
        val h = maxOf(IMAGE_SIZE, Math.round(bmp.height * scale))
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val x0 = (w - IMAGE_SIZE) / 2
        val y0 = (h - IMAGE_SIZE) / 2
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        scaled.getPixels(pixels, 0, IMAGE_SIZE, x0, y0, IMAGE_SIZE, IMAGE_SIZE)
        if (scaled !== bmp) scaled.recycle()
        if (bmp !== src) bmp.recycle()

        val plane = IMAGE_SIZE * IMAGE_SIZE
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = (((p shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            out[plane + i] = (((p shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            out[2 * plane + i] = ((p and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        return out
    }

    /** Vetor normalizado da imagem (já pré-processada). */
    fun embedImage(chw: FloatArray): FloatArray {
        val inputName = vision.inputNames.first()
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(chw), longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
        ).use { tensor ->
            vision.run(mapOf(inputName to tensor)).use { result ->
                return normalize(readOutput(result, vision.outputNames))
            }
        }
    }

    /** Vetor normalizado de um texto (em inglês). */
    fun embedText(query: String): FloatArray {
        val ids = tokenizer.encode(query)
        val shape = longArrayOf(1, ids.size.toLong())
        val tensors = HashMap<String, OnnxTensor>()
        try {
            for (name in text.inputNames) {
                val data = when (name) {
                    "input_ids" -> LongArray(ids.size) { ids[it].toLong() }
                    "attention_mask" -> LongArray(ids.size) { 1L }
                    "position_ids" -> LongArray(ids.size) { it.toLong() }
                    else -> error("Entrada inesperada no modelo de texto: $name")
                }
                tensors[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
            }
            text.run(tensors).use { result ->
                return normalize(readOutput(result, text.outputNames))
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    private fun readOutput(result: OrtSession.Result, names: Set<String>): FloatArray {
        val name = names.firstOrNull { it.endsWith("embeds") } ?: names.first()
        val tensor = result.get(name).get() as OnnxTensor
        val buf = tensor.floatBuffer
        val arr = FloatArray(buf.remaining())
        buf.get(arr)
        check(arr.size == EMBED_DIM) { "Saída '$name' com tamanho ${arr.size}, esperado $EMBED_DIM" }
        return arr
    }

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s).coerceAtLeast(1e-12f)
        for (i in v.indices) v[i] /= n
        return v
    }

    override fun close() {
        runCatching { vision.close() }
        runCatching { text.close() }
    }
}
