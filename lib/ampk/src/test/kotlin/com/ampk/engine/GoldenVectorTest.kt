package com.ampk.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Replays the call script captured from the Python reference engine (parity.json, produced by
 * am-predictive-kb/scripts/export_golden.py) and asserts identical observable behaviour. This
 * proves the Kotlin port matches the reference. Run: `./gradlew :lib:ampk:test`.
 */
class GoldenVectorTest : FunSpec({
    test("Kotlin engine matches the Python reference golden vectors") {
        val raw = GoldenVectorTest::class.java
            .getResource("/golden/parity.json")!!
            .readText(Charsets.UTF_8)
        val root = Json.parseToJsonElement(raw).jsonObject
        val corpus = root["corpus_text"]!!.jsonPrimitive.content

        val sink = ListSink()
        val engine = Engine.fromCorpus(corpus, sink)

        for (callEl in root["calls"]!!.jsonArray) {
            val call = callEl.jsonObject
            when (call["op"]!!.jsonPrimitive.content) {
                "suggest" -> {
                    val text = call["text"]!!.jsonPrimitive.content
                    val expected = call["shown"]!!.jsonArray.map { it.jsonPrimitive.content }
                    engine.suggest(text).map { it.word } shouldBe expected
                }
                "commit" -> {
                    val word = call["word"]!!.jsonPrimitive.content
                    val accepted = call["accepted"]!!.jsonPrimitive.boolean
                    val context = call["context"]!!.jsonArray.map { it.jsonPrimitive.content }

                    val before = sink.events.size
                    val result = engine.commitWord(word, accepted, context)

                    var applied = false
                    var correction = word
                    for (i in before until sink.events.size) {
                        val ev = sink.events[i]
                        if (ev.type == "autocorrect") {
                            applied = ev.data["applied"] as Boolean
                            correction = ev.data["correction"] as String
                        }
                    }

                    result.committed shouldBe call["committed"]!!.jsonPrimitive.content
                    applied shouldBe call["autocorrect_applied"]!!.jsonPrimitive.boolean
                    correction shouldBe call["correction"]!!.jsonPrimitive.content
                }
            }
        }
    }
})
