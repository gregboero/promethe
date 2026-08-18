package dev.promethe.evals

import dev.promethe.api.EvalSuite
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
}
