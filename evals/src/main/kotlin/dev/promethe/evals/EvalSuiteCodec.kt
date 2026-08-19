package dev.promethe.evals

import dev.promethe.api.EvalSuite
import dev.promethe.api.EvalRun
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object EvalSuiteCodec {
    private val json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            prettyPrint = true
        }

    fun decode(content: String): EvalSuite = json.decodeFromString(content)

    fun encode(suite: EvalSuite): String = json.encodeToString(suite)

    fun encode(run: EvalRun): String = json.encodeToString(run)

    fun encode(report: AdversarialEvalReport): String = json.encodeToString(report)
}
