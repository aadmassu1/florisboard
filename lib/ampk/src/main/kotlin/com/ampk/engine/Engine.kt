package com.ampk.engine

/** Engine configuration — port of `ampk/engine.py` EngineConfig. */
data class EngineConfig(
    val maxSuggestions: Int = 3,
    val completionWeight: Double = 1.0,
    val ngramWeight: Double = 1.0,
    val personalWeight: Double = 2.0,
    val autocorrect: Boolean = true,
)

/**
 * Prediction engine — port of `ampk/engine.py`. The single source of truth the FlorisBoard shell
 * drives; validated against the Python reference via GoldenVectorTest.
 */
class Engine(
    val ngram: NGramModel,
    val dictionary: CompletionDictionary,
    val personal: PersonalModel = PersonalModel(),
    val autocorrector: Autocorrector? = null,
    val config: EngineConfig = EngineConfig(),
    private var audit: AuditSink = NullSink,
) : SuggestionProvider {

    var step = 0
        private set
    private var seq = 0

    override fun setAuditSink(sink: AuditSink) {
        audit = sink
    }

    private fun emit(type: String, data: Map<String, Any?>) {
        seq++
        audit.emit(AuditEvent(seq, step, type, data))
    }

    override fun suggest(text: String): List<Suggestion> {
        val (context, prefix) = Tokenize.splitContext(text)
        val k = config.maxSuggestions
        val pool = maxOf(k * 3, 10)

        var completionSrc: List<Pair<String, Double>> = emptyList()
        var ngramSrc: List<Pair<String, Double>> = emptyList()
        val personalSrc: List<Pair<String, Double>>

        if (prefix.isNotEmpty()) {
            completionSrc = dictionary.complete(prefix, pool).map { it.first to it.second.toDouble() }
            personalSrc = personal.completions(prefix, step, pool)
        } else {
            ngramSrc = ngram.predictNext(context, pool)
            val prev = if (context.isNotEmpty()) context.last() else BOS
            personalSrc = personal.nextWords(prev, step, pool)
        }

        val merged = merge(completionSrc, ngramSrc, personalSrc, k)
        emit("suggest", mapOf(
            "text" to text,
            "context" to context,
            "prefix" to prefix,
            "normalized_prefix" to Normalize.normalize(prefix),
            "mode" to if (prefix.isNotEmpty()) "completion" else "next_word",
            "shown" to merged.map { it.word },
        ))
        return merged
    }

    private fun merge(
        completionSrc: List<Pair<String, Double>>,
        ngramSrc: List<Pair<String, Double>>,
        personalSrc: List<Pair<String, Double>>,
        k: Int,
    ): List<Suggestion> {
        // Reciprocal-rank fusion — IEEE-754-deterministic, so it matches the Python reference exactly.
        val scores = LinkedHashMap<String, Double>()
        fun add(src: List<Pair<String, Double>>, weight: Double) {
            src.forEachIndexed { rank, (w, _) ->
                scores[w] = (scores[w] ?: 0.0) + weight * (1.0 / (rank + 1))
            }
        }
        add(completionSrc, config.completionWeight)
        add(ngramSrc, config.ngramWeight)
        add(personalSrc, config.personalWeight)
        return scores.entries.map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .take(k)
            .map { Suggestion(it.first, it.second) }
    }

    override fun commitWord(word: String, accepted: Boolean, context: List<String>): CommitResult {
        var committed = word
        if (autocorrector != null && config.autocorrect && !accepted) {
            val (corrected, decision) = autocorrector.correct(word, context)
            committed = corrected
            emit("autocorrect", decision)
        }
        val prev = if (context.isNotEmpty()) context.last() else null
        personal.recordCommit(committed, step, accepted, prev)
        emit("commit", mapOf(
            "typed" to word,
            "committed" to committed,
            "accepted" to accepted,
            "prev" to prev,
        ))
        step++
        return CommitResult(word, committed, accepted)
    }

    companion object {
        fun fromCorpus(text: String, audit: AuditSink = NullSink,
                       personal: PersonalModel = PersonalModel()): Engine {
            val ngram = NGramModel().trainText(text)
            val freqs = ngram.freqs()
            return Engine(
                ngram,
                CompletionDictionary.fromFreqs(freqs),
                personal,
                Autocorrector(freqs, ngram),
                EngineConfig(),
                audit,
            )
        }
    }
}
