package com.ampk.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies the asset loader: an [Engine] built from the pipeline's exported `ngram.json` +
 * `dictionary.json` (the form the keyboard ships) produces the expected suggestions for both
 * the completion path and the next-word path. This guards the JSON contract between the offline
 * pipeline and the on-device engine. Run: `./gradlew :lib:ampk:test`.
 */
class ModelBundleTest : FunSpec({

    fun resource(path: String): String =
        ModelBundleTest::class.java.getResource(path)!!.readText(Charsets.UTF_8)

    fun loadEngine(): Engine =
        ModelBundle.load(resource("/bundle/ngram.json"), resource("/bundle/dictionary.json"))

    test("completion path: prefix is completed from the bundled dictionary") {
        loadEngine().suggest("ሰላ").map { it.word } shouldBe listOf("ሰላም", "ሰላማዊ", "ሰላምታ")
    }

    test("next-word path: context drives the bundled n-gram prediction") {
        loadEngine().suggest("ነገ ወደ ").map { it.word } shouldBe listOf("ቤት", "ስራ", "ቢሮ")
    }
})
