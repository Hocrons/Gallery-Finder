package com.galeriaia.app.ml

import kotlin.math.sqrt

/** Ajustes do texto de busca compartilhados entre fotos e vídeos. */
object QueryText {
    private val GLOSSARY = listOf(
        Regex("(?i)\\bprints\\b") to "capturas de tela",
        Regex("(?i)\\bprint\\b") to "captura de tela",
        Regex("(?i)\\bzap\\b") to "WhatsApp",
        Regex("(?i)\\brango\\b") to "comida",
    )

    fun glossary(text: String): String {
        var t = text
        for ((p, r) in GLOSSARY) t = t.replace(p, r)
        return t
    }

    /** Média de duas formulações do texto: melhora a precisão do CLIP. */
    fun embed(clip: ClipModel, english: String, prefix: String = "a photo of"): FloatArray {
        val a = clip.embedText(english)
        val b = clip.embedText("$prefix $english")
        val v = FloatArray(a.size) { a[it] + b[it] }
        val n = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        for (i in v.indices) v[i] /= n
        return v
    }
}
