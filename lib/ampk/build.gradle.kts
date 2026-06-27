/*
 * Amharic prediction engine (ampk) — pure Kotlin/JVM module.
 * Port of the Python reference in the am-predictive-kb project; validated by GoldenVectorTest.
 */

import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotest)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks {
    compileKotlin {
        compilerOptions.jvmTarget = JvmTarget.JVM_11
    }
    compileTestKotlin {
        compilerOptions.jvmTarget = JvmTarget.JVM_11
    }
}

tasks.withType<Test> {
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
    useJUnitPlatform()
}

dependencies {
    // The engine itself is pure stdlib. serialization-json is only used by the parity test.
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
}
