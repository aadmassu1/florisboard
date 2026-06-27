package com.ampk.engine

/** Whitespace/Ethiopic-punctuation tokenizer — port of `ampk/tokenize.py`. */
object Tokenize {
    private const val WORDSEP = "፡።" // ፡ ።
    private val PUNCT =
        ("፠፡።፣፤፥፦፧፨" + ".,!?;:\"'()[]{}«»…—–-/\\|").toSet()

    private val splitRe = Regex("[\\s$WORDSEP]+")
    private val prefixRe = Regex("[^\\s$WORDSEP]*$")
    private val sentRe = Regex("[።.!?]+")

    fun words(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (tok in splitRe.split(text)) {
            val t = tok.trim { it in PUNCT }
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    fun sentences(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return sentRe.split(text).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Split typed text into (completed context words, in-progress prefix). */
    fun splitContext(text: String): Pair<List<String>, String> {
        if (text.isEmpty()) return Pair(emptyList(), "")
        val raw = prefixRe.find(text)?.value ?: ""
        val prefix = raw.trimStart { it in PUNCT }
        val contextText = text.substring(0, text.length - raw.length)
        return Pair(words(contextText), prefix)
    }
}
