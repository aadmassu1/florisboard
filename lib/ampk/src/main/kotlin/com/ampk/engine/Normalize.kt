package com.ampk.engine

/**
 * Fidel (Ethiopic) homophone normalization — port of `ampk/normalize.py`.
 *
 * Normalizes for matching only; never mutate committed text, and render suggestions in their
 * surface form. Ethiopic characters are all in the BMP, so each fits a single Kotlin [Char].
 */
object Normalize {
    // (redundantStart, canonicalStart); each base consonant has 7 vowel orders.
    private val series = listOf(
        0x1210 to 0x1200, // ሐ -> ሀ
        0x1280 to 0x1200, // ኀ -> ሀ
        0x1220 to 0x1230, // ሠ -> ሰ
        0x12D0 to 0x12A0, // ዐ -> አ
        0x1340 to 0x1338, // ፀ -> ጸ
    )
    private const val ORDERS = 7

    private val charMap: Map<Char, Char> = buildMap {
        for ((redundant, canonical) in series) {
            for (i in 0 until ORDERS) put((redundant + i).toChar(), (canonical + i).toChar())
        }
    }

    fun normalizeChar(ch: Char): Char = charMap[ch] ?: ch

    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        return buildString(text.length) { for (ch in text) append(charMap[ch] ?: ch) }
    }

    fun areConfusable(a: Char, b: Char): Boolean = normalizeChar(a) == normalizeChar(b)
}
