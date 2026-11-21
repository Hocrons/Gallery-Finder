package com.galeriaia.app.search

import java.util.Random

/**
 * Encontra fotos parecidas/idênticas comparando os vetores do CLIP.
 *
 * Comparar todas com todas (27 mil fotos = 370 milhões de pares) seria lento demais no celular.
 * Por isso usamos LSH: cada foto ganha "assinaturas" curtas e só comparamos de verdade as fotos
 * que caem no mesmo balde. Fotos muito parecidas quase sempre caem juntas em pelo menos um balde.
 * Não depende do Android (testável em JVM pura).
 */
class DuplicateFinder(
    private val dim: Int,
    private val tables: Int = 16,
    private val bits: Int = 14,
    seed: Long = 42L,
) {
    /** Par de fotos (posições no índice) e a semelhança entre elas (0 a 1). */
    class Pairs(val a: IntArray, val b: IntArray, val sim: FloatArray) {
        val size get() = a.size
    }

    private val planes: Array<FloatArray> = Random(seed).let { rnd ->
        Array(tables * bits) { FloatArray(dim) { rnd.nextGaussian().toFloat() } }
    }

    /**
     * Retorna todos os pares com semelhança >= [minSim].
     * [vectors] é a matriz n x dim já normalizada (linha i = foto i).
     */
    fun findPairs(
        vectors: FloatArray, n: Int, minSim: Float,
        maxBucket: Int = 1500, maxPairs: Int = 1_500_000,
    ): Pairs {
        val valid = BooleanArray(n) { i ->
            var s = 0f
            val off = i * dim
            for (k in 0 until dim) s += vectors[off + k] * vectors[off + k]
            s > 0.5f // vetores zerados = fotos que não abriram
        }
        val seen = HashSet<Long>()
        val pa = IntArrayList(); val pb = IntArrayList(); val ps = FloatArrayList()

        for (t in 0 until tables) {
            val buckets = HashMap<Int, IntArrayList>()
            for (i in 0 until n) {
                if (!valid[i]) continue
                var key = 0
                val off = i * dim
                for (b in 0 until bits) {
                    val plane = planes[t * bits + b]
                    var dot = 0f
                    for (k in 0 until dim) dot += vectors[off + k] * plane[k]
                    if (dot > 0) key = key or (1 shl b)
                }
                buckets.getOrPut(key) { IntArrayList() }.add(i)
            }
            for (bucket in buckets.values) {
                val m = bucket.size
                if (m < 2 || m > maxBucket) continue
                for (x in 0 until m) {
                    val i = bucket[x]
                    val oi = i * dim
                    for (y in x + 1 until m) {
                        val j = bucket[y]
                        val key = if (i < j) i.toLong() * n + j else j.toLong() * n + i
                        val oj = j * dim
                        var dot = 0f
                        for (k in 0 until dim) dot += vectors[oi + k] * vectors[oj + k]
                        if (dot >= minSim && seen.add(key)) {
                            pa.add(minOf(i, j)); pb.add(maxOf(i, j)); ps.add(dot)
                            if (pa.size >= maxPairs) return Pairs(pa.toArray(), pb.toArray(), ps.toArray())
                        }
                    }
                }
            }
        }
        return Pairs(pa.toArray(), pb.toArray(), ps.toArray())
    }

    companion object {
        /** Agrupa (union-find) os pares com semelhança >= [threshold]. Grupos com 2+ fotos, maiores primeiro. */
        fun groups(pairs: Pairs, n: Int, threshold: Float): List<IntArray> {
            val parent = IntArray(n) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                var c = x
                while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
                return r
            }
            for (p in 0 until pairs.size) {
                if (pairs.sim[p] < threshold) continue
                val ra = find(pairs.a[p]); val rb = find(pairs.b[p])
                if (ra != rb) parent[ra] = rb
            }
            val touched = HashSet<Int>()
            for (p in 0 until pairs.size) if (pairs.sim[p] >= threshold) { touched += pairs.a[p]; touched += pairs.b[p] }
            return touched.groupBy { find(it) }.values
                .filter { it.size >= 2 }
                .map { it.sorted().toIntArray() }
                .sortedByDescending { it.size }
        }
    }
}

private class IntArrayList {
    private var data = IntArray(16)
    var size = 0; private set
    fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    operator fun get(i: Int) = data[i]
    fun toArray() = data.copyOf(size)
}

private class FloatArrayList {
    private var data = FloatArray(16)
    var size = 0; private set
    fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    fun toArray() = data.copyOf(size)
}
