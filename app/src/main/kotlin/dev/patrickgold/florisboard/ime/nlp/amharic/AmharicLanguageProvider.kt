/*
 * Copyright (C) 2026 The am-predictive-kb Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.amharic

import android.content.Context
import com.ampk.engine.Engine
import com.ampk.engine.ModelBundle
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock

/**
 * Amharic next-word + word-completion suggestion provider, backed by the pure-Kotlin prediction
 * engine in `:lib:ampk` (the validated port of the am-predictive-kb reference engine). The model
 * is the offline pipeline's exported bundle (`assets/ime/ampk/{ngram,dictionary}.json`), loaded
 * once on [preload] via [ModelBundle].
 *
 * This bridges FlorisBoard's [SuggestionProvider] contract to the engine's own suggest/commit
 * API: [suggest] hands the text before the cursor to the engine (which splits it into context +
 * current-word prefix itself) and maps the ranked results into candidate items; accepted words
 * feed the engine's on-device personalization.
 */
class AmharicLanguageProvider(context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "com.ampk.nlp.providers.amharic"

        private const val NGRAM_ASSET = "ime/ampk/ngram.json"
        private const val DICTIONARY_ASSET = "ime/ampk/dictionary.json"
    }

    private val appContext by context.appContext()

    /** Holds the lazily-loaded engine; guarded so preload/suggest/commit never race on it. */
    private class EngineState {
        var engine: Engine? = null
    }

    private val state = guardedByLock { EngineState() }

    override val providerId = ProviderId

    override suspend fun create() {
        // Nothing language-independent to set up.
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        state.withLock { holder ->
            if (holder.engine == null) {
                try {
                    val ngramJson = appContext.assets.readText(NGRAM_ASSET)
                    val dictionaryJson = appContext.assets.readText(DICTIONARY_ASSET)
                    holder.engine = ModelBundle.load(ngramJson, dictionaryJson)
                } catch (e: Exception) {
                    flogError { "Failed to load Amharic model bundle: ${e.message}" }
                }
            }
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        return state.withLock { holder ->
            val engine = holder.engine ?: return@withLock emptyList()
            // The engine itself splits the text into prior-word context + current-word prefix,
            // so we feed it everything up to the cursor.
            val results = engine.suggest(content.textBeforeSelection)
            if (results.isEmpty()) return@withLock emptyList()
            // Engine scores are unbounded RRF weights; normalize to the [0,1] confidence the UI expects.
            val maxScore = results.maxOf { it.score }.takeIf { it > 0.0 } ?: 1.0
            results.take(maxCandidateCount).map { s ->
                WordSuggestionCandidate(
                    text = s.word,
                    secondaryText = null,
                    confidence = (s.score / maxScore).coerceIn(0.0, 1.0),
                    // Auto-commit (the autocorrect-on-space UX) is wired in a later step.
                    isEligibleForAutoCommit = false,
                    isEligibleForUserRemoval = false,
                    sourceProvider = this@AmharicLanguageProvider,
                )
            }
        }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // Let the engine's on-device personalization learn the accepted word (frequency/recency/
        // acceptance). Real prior-word context is wired in a later step alongside autocorrect.
        state.withLock { holder ->
            holder.engine?.commitWord(candidate.text.toString(), accepted = true)
        }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        // No-op for now.
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return state.withLock { it.engine?.ngram?.freqs()?.keys?.toList() ?: emptyList() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return state.withLock { holder ->
            val freq = holder.engine?.ngram?.freqs()?.get(word) ?: 0
            (freq / 255.0).coerceIn(0.0, 1.0)
        }
    }

    override suspend fun destroy() {
        state.withLock { it.engine = null }
    }
}
