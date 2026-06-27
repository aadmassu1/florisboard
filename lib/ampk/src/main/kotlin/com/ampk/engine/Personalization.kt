package com.ampk.engine

/** On-device personal learning layer — port of `ampk/personalization.py`. */
data class WordStat(var count: Int = 0, var lastUsed: Int = 0, var accepted: Int = 0)

class PersonalModel(val recencyWindow: Int = 50, val maxWords: Int = 5000) {
    val stats = LinkedHashMap<String, WordStat>()
    val phrases = LinkedHashMap<String, Int>() // "prev\tword" -> count

    fun recordCommit(word: String, now: Int, accepted: Boolean = false, prev: String? = null) {
        val st = stats.getOrPut(word) { WordStat() }
        st.count++
        st.lastUsed = now
        if (accepted) st.accepted++
        if (prev != null) {
            val key = "$prev\t$word"
            phrases[key] = (phrases[key] ?: 0) + 1
        }
        if (stats.size > maxWords) prune(now)
    }

    fun score(word: String, now: Int): Double {
        val st = stats[word] ?: return 0.0
        val recency = if (recencyWindow != 0) {
            maxOf(0.0, (recencyWindow - (now - st.lastUsed)).toDouble() / recencyWindow)
        } else 0.0
        return st.count + st.accepted + recency
    }

    fun completions(prefix: String, now: Int, k: Int = 5): List<Pair<String, Double>> {
        val key = Normalize.normalize(prefix)
        return stats.keys.filter { Normalize.normalize(it).startsWith(key) }
            .map { it to score(it, now) }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(k)
    }

    fun nextWords(prev: String, now: Int, k: Int = 5): List<Pair<String, Double>> {
        val pfx = "$prev\t"
        val out = ArrayList<Pair<String, Double>>()
        for ((keyStr, c) in phrases) {
            if (keyStr.startsWith(pfx)) {
                val w = keyStr.substring(pfx.length)
                out.add(w to (c + score(w, now)))
            }
        }
        return out
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(k)
    }

    private fun prune(now: Int) {
        val ranked = stats.entries.sortedBy { score(it.key, now) }
        val excess = stats.size - maxWords
        for (i in 0 until excess) stats.remove(ranked[i].key)
    }
}
