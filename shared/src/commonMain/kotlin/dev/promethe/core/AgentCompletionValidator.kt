package dev.promethe.core

/** Optional trusted task contract. Reject before final success and skill synthesis; never retries. */
fun interface AgentCompletionValidator {
    fun validate(response: String)
}
