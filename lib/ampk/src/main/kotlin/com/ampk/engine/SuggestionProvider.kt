package com.ampk.engine

/**
 * Contract the FlorisBoard shell talks to — the canonical interface for the prediction backend.
 * `Engine` implements it. See android/INTEGRATION-CONTRACT.md.
 */

/** A single suggestion shown in the suggestion bar. */
data class Suggestion(val word: String, val score: Double)

/** Result of finalizing a word. */
data class CommitResult(val typed: String, val committed: String, val accepted: Boolean)

interface SuggestionProvider {
    /** Top-k suggestions for the text typed so far (completion mid-word, else next-word). */
    fun suggest(text: String): List<Suggestion>

    /**
     * Finalize a word (space/punctuation). Runs conservative autocorrect unless accepted from
     * the bar, then updates on-device personalization.
     */
    fun commitWord(word: String, accepted: Boolean = false, context: List<String> = emptyList()): CommitResult

    /** Attach an audit sink (default no-op) — the on-device testing/debugging trail. */
    fun setAuditSink(sink: AuditSink)
}
