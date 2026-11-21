package com.galeriaia.app.ml

/**
 * Tokenizador BPE do CLIP (mesmo algoritmo do tokenizer oficial da OpenAI).
 * O vocabulário é reconstruído a partir do merges.txt, então só esse arquivo é necessário.
 * Não depende de nada do Android, por isso pode ser testado em JVM pura.
 */
class ClipTokenizer(mergesLines: List<String>) {

    companion object {
        const val SOT = 49406 // <|startoftext|>
        const val EOT = 49407 // <|endoftext|>
        const val CONTEXT_LENGTH = 77
        private const val NUM_MERGES = 49152 - 256 - 2
    }

    private val byteEncoder = CharArray(256)
    private val encoder: Map<String, Int>
    private val bpeRanks: Map<String, Int> // chave: "a b"
    private val cache = HashMap<String, List<String>>()

    private val pattern = Regex(
        """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
        RegexOption.IGNORE_CASE
    )
    private val whitespace = Regex("""\s+""")

    init {
        // bytes_to_unicode() do GPT-2/CLIP, preservando a ordem de inserção
        val bs = ArrayList<Int>()
        bs += '!'.code..'~'.code
        bs += '¡'.code..'¬'.code
        bs += '®'.code..'ÿ'.code
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0 until 256) {
            if (b !in bs) {
                bs += b
                cs += 256 + n
                n++
            }
        }
        val base = ArrayList<String>(256)
        for (i in bs.indices) {
            byteEncoder[bs[i]] = cs[i].toChar()
            base += cs[i].toChar().toString()
        }

        val vocab = ArrayList<String>(49408)
        vocab += base
        base.mapTo(vocab) { "$it</w>" }

        // A primeira linha é o cabeçalho de versão
        val merges = mergesLines.asSequence()
            .drop(1)
            .map { it.trimEnd('\r', '\n') }
            .filter { it.isNotEmpty() }
            .take(NUM_MERGES)
            .toList()
        val ranks = HashMap<String, Int>(merges.size * 2)
        merges.forEachIndexed { i, line ->
            val parts = line.split(' ')
            require(parts.size == 2) { "merges.txt inválido na linha ${i + 2}: '$line'" }
            ranks[line] = i
            vocab += parts[0] + parts[1]
        }
        bpeRanks = ranks
        vocab += "<|startoftext|>"
        vocab += "<|endoftext|>"

        encoder = HashMap<String, Int>(vocab.size * 2).also { map ->
            vocab.forEachIndexed { i, s -> map[s] = i }
        }
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        var word: MutableList<String> = ArrayList(token.length)
        for (i in 0 until token.length - 1) word += token[i].toString()
        word += token.last() + "</w>"

        if (word.size == 1) return word.also { cache[token] = it }

        while (true) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val r = bpeRanks[word[i] + " " + word[i + 1]] ?: continue
                if (r < bestRank) {
                    bestRank = r
                    best = word[i] to word[i + 1]
                }
            }
            if (best == null) break
            val (first, second) = best
            val newWord = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord += first + second
                    i += 2
                } else {
                    newWord += word[i]
                    i += 1
                }
            }
            word = newWord
            if (word.size == 1) break
        }
        cache[token] = word
        return word
    }

    /** Retorna os ids com <|startoftext|> e <|endoftext|>, no máximo 77 tokens, sem padding. */
    fun encode(text: String): IntArray {
        val clean = text.replace(whitespace, " ").trim().lowercase()
        val ids = ArrayList<Int>()
        for (m in pattern.findAll(clean)) {
            val bytes = m.value.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder(bytes.size)
            for (b in bytes) sb.append(byteEncoder[b.toInt() and 0xFF])
            for (piece in bpe(sb.toString())) {
                ids += encoder[piece] ?: continue
            }
        }
        val body = ids.take(CONTEXT_LENGTH - 2)
        return (listOf(SOT) + body + EOT).toIntArray()
    }
}
