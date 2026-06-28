package com.ampk.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads a prediction [Engine] from the offline pipeline's exported model assets — `ngram.json`
 * and `dictionary.json`, produced by am-predictive-kb `pipeline/build.py`. This is how the
 * keyboard ships a model: bundle the two JSON files, load them once at startup.
 *
 * All JSON parsing lives here so [NGramModel] and [CompletionDictionary] stay dependency-free;
 * the model is rebuilt through their counts-based factories. The result is behaviourally
 * identical to [Engine.fromCorpus] on the same corpus (same fidel normalization, n-gram counts,
 * completion trie, and conservative autocorrect) — the asset is just the pre-trained form.
 */
object ModelBundle {

    /** Parse the two asset strings and assemble an [Engine]. */
    fun load(
        ngramJson: String,
        dictionaryJson: String,
        audit: AuditSink = NullSink,
        personal: PersonalModel = PersonalModel(),
        config: EngineConfig = EngineConfig(),
    ): Engine {
        val ngram = parseNgram(ngramJson)
        val freqs = parseFreqs(dictionaryJson)
        return Engine(
            ngram = ngram,
            dictionary = CompletionDictionary.fromFreqs(freqs),
            personal = personal,
            autocorrector = Autocorrector(freqs, ngram),
            config = config,
            audit = audit,
        )
    }

    /** Rebuild an [NGramModel] from `ngram.json` (the shape emitted by `NGramModel.to_dict`). */
    private fun parseNgram(json: String): NGramModel {
        val root = Json.parseToJsonElement(json).jsonObject
        val alpha = root["alpha"]!!.jsonPrimitive.double
        val total = root["total"]!!.jsonPrimitive.int

        // JsonObject preserves key order, so the unigram keeps its training order — this matters
        // for the cold-context fallback's tie-breaking, which mirrors Counter.most_common.
        val unigram = LinkedHashMap<String, Int>()
        for ((w, c) in root["unigram"]!!.jsonObject) unigram[w] = c.jsonPrimitive.int

        val bigram = LinkedHashMap<String, Map<String, Int>>()
        for ((w1, inner) in root["bigram"]!!.jsonObject) {
            val m = LinkedHashMap<String, Int>()
            for ((w2, c) in inner.jsonObject) m[w2] = c.jsonPrimitive.int
            bigram[w1] = m
        }

        // Trigram keys are "w1\tw2" (tab-joined), matching the Python export.
        val trigram = LinkedHashMap<Pair<String, String>, Map<String, Int>>()
        for ((key, inner) in root["trigram"]!!.jsonObject) {
            val parts = key.split('\t', limit = 2)
            val pair = parts[0] to parts[1]
            val m = LinkedHashMap<String, Int>()
            for ((w3, c) in inner.jsonObject) m[w3] = c.jsonPrimitive.int
            trigram[pair] = m
        }

        return NGramModel.fromCounts(alpha, total, unigram, bigram, trigram)
    }

    /** Parse `dictionary.json` (a flat `surface -> frequency` map). */
    private fun parseFreqs(json: String): Map<String, Int> {
        val freqs = LinkedHashMap<String, Int>()
        for ((w, c) in Json.parseToJsonElement(json).jsonObject) freqs[w] = c.jsonPrimitive.int
        return freqs
    }
}
