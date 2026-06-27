package com.ampk.engine

import kotlin.math.abs
import kotlin.math.ln

/** Levenshtein distance where fidel-homophone substitutions cost 0 — port of `ampk/autocorrect.py`. */
object EditDistance {
    private fun subCost(a: Char, b: Char): Int =
        if (a == b || Normalize.normalizeChar(a) == Normalize.normalizeChar(b)) 0 else 1

    fun distance(a: String, b: String, maxCost: Int? = null): Int {
        val la = a.length
        val lb = b.length
        val ceiling = maxCost ?: maxOf(la, lb)
        if (abs(la - lb) > ceiling) return ceiling + 1
        var prev = IntArray(lb + 1) { it }
        for (i in 1..la) {
            val cur = IntArray(lb + 1)
            cur[0] = i
            var rowBest = cur[0]
            val ai = a[i - 1]
            for (j in 1..lb) {
                val cost = subCost(ai, b[j - 1])
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowBest) rowBest = cur[j]
            }
            if (maxCost != null && rowBest > maxCost) return maxCost + 1
            prev = cur
        }
        return prev[lb]
    }
}

private data class AcCandidate(val word: String, val dist: Int, val score: Double, val freq: Int)

/** Conservative, fidel-aware autocorrect — port of `ampk/autocorrect.py`. */
class Autocorrector(
    val freqs: Map<String, Int>,
    val ngram: NGramModel? = null,
    val maxEdit: Int = 2,
    val freqWeight: Double = 1.0,
    val contextWeight: Double = 2.0,
    val margin: Double = 1.0,
    val maxTypedFreq: Int = 0,
) {
    private fun candidates(word: String): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        for ((cand, f) in freqs) {
            if (abs(cand.length - word.length) > maxEdit) continue
            val d = EditDistance.distance(word, cand, maxEdit)
            if (d <= maxEdit) out.add(Triple(cand, d, f))
        }
        return out
    }

    private fun ctxLogscore(context: List<String>, word: String): Double {
        val w1 = if (context.size >= 2) context[context.size - 2] else BOS
        val w2 = if (context.isNotEmpty()) context[context.size - 1] else BOS
        return ngram!!.logprobWord(w1, w2, word)
    }

    private fun scoreCand(freq: Int, cand: String, context: List<String>?): Double {
        var s = freqWeight * ln((freq + 1).toDouble())
        if (ngram != null && context != null) s += contextWeight * ctxLogscore(context, cand)
        return s
    }

    /** Returns (possibly-corrected word, decision map for the audit trail). */
    fun correct(word: String, context: List<String>? = null): Pair<String, MutableMap<String, Any?>> {
        val typedFreq = freqs[word] ?: 0
        val scored = candidates(word)
            .map { (cand, dist, f) -> AcCandidate(cand, dist, scoreCand(f, cand, context), f) }
            .sortedWith(
                compareBy<AcCandidate> { it.dist }
                    .thenByDescending { it.score }
                    .thenBy { it.word }
            )

        val decision: MutableMap<String, Any?> = linkedMapOf(
            "typed" to word,
            "typed_freq" to typedFreq,
            "candidates" to scored.take(5).map {
                mapOf("word" to it.word, "dist" to it.dist, "freq" to it.freq)
            },
            "applied" to false,
            "correction" to word,
            "reason" to "",
        )

        if (typedFreq > maxTypedFreq) {
            decision["reason"] = "typed word is in-vocabulary; left unchanged"
            return word to decision
        }
        val cands = scored.filter { it.word != word }
        if (cands.isEmpty()) {
            decision["reason"] = "no candidate within edit distance"
            return word to decision
        }

        val best = cands[0]
        val sameDist = cands.filter { it.dist == best.dist }
        val (apply, reason) = when {
            best.dist == 0 -> true to "exact fidel-normalized match"
            sameDist.size == 1 -> true to "unique nearest candidate"
            best.score - sameDist[1].score >= margin -> true to "nearest candidate beat its runner-up by margin"
            else -> false to "nearest candidates too close; left unchanged"
        }

        decision["reason"] = reason
        if (apply) {
            decision["applied"] = true
            decision["correction"] = best.word
            return best.word to decision
        }
        return word to decision
    }
}
