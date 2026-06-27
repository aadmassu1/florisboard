package com.ampk.engine

/** Frequency-ranked completion trie keyed on normalized fidels — port of `ampk/dictionary.py`. */
class CompletionDictionary {
    private class Node {
        val children = HashMap<Char, Node>()
        val words = LinkedHashMap<String, Int>() // surface -> freq (terminal)
    }

    private val root = Node()

    fun add(surface: String, freq: Int = 1) {
        var node = root
        for (ch in Normalize.normalize(surface)) node = node.children.getOrPut(ch) { Node() }
        node.words[surface] = (node.words[surface] ?: 0) + freq
    }

    fun complete(prefix: String, k: Int = 5): List<Pair<String, Int>> {
        if (prefix.isEmpty()) return emptyList()
        var node: Node = root
        for (ch in Normalize.normalize(prefix)) {
            node = node.children[ch] ?: return emptyList()
        }
        val collected = HashMap<String, Int>()
        val stack = ArrayDeque<Node>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val nd = stack.removeLast()
            for ((s, f) in nd.words) if (f > (collected[s] ?: 0)) collected[s] = f
            for (child in nd.children.values) stack.addLast(child)
        }
        return collected.entries.map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(k)
    }

    companion object {
        fun fromFreqs(freqs: Map<String, Int>): CompletionDictionary {
            val d = CompletionDictionary()
            for ((w, f) in freqs) d.add(w, f)
            return d
        }
    }
}
