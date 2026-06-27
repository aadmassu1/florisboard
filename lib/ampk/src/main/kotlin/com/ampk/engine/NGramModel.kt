package com.ampk.engine

import kotlin.math.exp
import kotlin.math.ln

const val BOS = "<s>"
const val EOS = "</s>"

/** Trigram language model with stupid backoff — port of `ampk/ngram.py`. */
class NGramModel(val alpha: Double = 0.4) {
    // unigram is insertion-ordered so the cold-context fallback matches Python's Counter.most_common.
    val unigram = LinkedHashMap<String, Int>()
    val bigram = HashMap<String, MutableMap<String, Int>>()
    val trigram = HashMap<Pair<String, String>, MutableMap<String, Int>>()
    var total = 0
        private set

    fun trainSentence(sentenceWords: List<String>) {
        val seq = listOf(BOS, BOS) + sentenceWords + listOf(EOS)
        for (i in 2 until seq.size) {
            unigram[seq[i]] = (unigram[seq[i]] ?: 0) + 1
            total++
        }
        for (i in 0 until seq.size - 1) {
            val m = bigram.getOrPut(seq[i]) { LinkedHashMap() }
            m[seq[i + 1]] = (m[seq[i + 1]] ?: 0) + 1
        }
        for (i in 0 until seq.size - 2) {
            val m = trigram.getOrPut(seq[i] to seq[i + 1]) { LinkedHashMap() }
            m[seq[i + 2]] = (m[seq[i + 2]] ?: 0) + 1
        }
    }

    fun trainText(text: String): NGramModel {
        for (s in Tokenize.sentences(text)) trainSentence(Tokenize.words(s))
        return this
    }

    private fun lastTwo(context: List<String>): Pair<String, String> {
        val ctx = listOf(BOS, BOS) + context
        return ctx[ctx.size - 2] to ctx[ctx.size - 1]
    }

    fun stupidBackoff(w1: String, w2: String, w: String): Double {
        trigram[w1 to w2]?.let { tri -> tri[w]?.let { return it.toDouble() / tri.values.sum() } }
        bigram[w2]?.let { bi -> bi[w]?.let { return alpha * (it.toDouble() / bi.values.sum()) } }
        if (total > 0) unigram[w]?.let { return alpha * alpha * (it.toDouble() / total) }
        return 0.0
    }

    fun predictNext(context: List<String>, k: Int = 3,
                    exclude: Set<String> = setOf(BOS, EOS)): List<Pair<String, Double>> {
        val (w1, w2) = lastTwo(context)
        val cands = LinkedHashSet<String>()
        trigram[w1 to w2]?.keys?.let { cands.addAll(it) }
        bigram[w2]?.keys?.let { cands.addAll(it) }
        if (cands.isEmpty()) {
            unigram.entries.sortedByDescending { it.value }.take(50).forEach { cands.add(it.key) }
        }
        val scored = ArrayList<Pair<String, Double>>()
        for (w in cands) {
            if (w in exclude) continue
            val s = stupidBackoff(w1, w2, w)
            if (s > 0) scored.add(w to s)
        }
        return scored
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(k)
    }

    fun logprobWord(w1: String, w2: String, w: String): Double {
        val l3 = 0.6; val l2 = 0.3; val l1 = 0.1; val k = 1.0
        val vocabSize = maxOf(1, unigram.size)
        val pUni = ((unigram[w] ?: 0) + k) / (total + k * vocabSize)
        var pBi = 0.0
        bigram[w2]?.let { bi -> val denom = bi.values.sum(); if (denom > 0) pBi = (bi[w] ?: 0).toDouble() / denom }
        var pTri = 0.0
        trigram[w1 to w2]?.let { tri -> val denom = tri.values.sum(); if (denom > 0) pTri = (tri[w] ?: 0).toDouble() / denom }
        val p = l3 * pTri + l2 * pBi + l1 * pUni
        return ln(if (p > 0) p else 1e-12)
    }

    fun perplexity(sentences: List<List<String>>): Double {
        var totalLp = 0.0
        var n = 0
        for (ws in sentences) {
            val seq = listOf(BOS, BOS) + ws + listOf(EOS)
            for (i in 2 until seq.size) {
                totalLp += logprobWord(seq[i - 2], seq[i - 1], seq[i])
                n++
            }
        }
        return if (n == 0) Double.POSITIVE_INFINITY else exp(-totalLp / n)
    }

    fun freqs(): Map<String, Int> = unigram.filterKeys { it != BOS && it != EOS }

    fun vocab(): Set<String> = unigram.keys.filter { it != BOS && it != EOS }.toSet()
}
