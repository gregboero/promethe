package dev.promethe.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject

/** Optional bridge used by durable executions that can pause for structured client input. */
fun interface ToolInputRequestBridge {
    suspend fun request(inputRequests: JsonObject): JsonObject
}

class ToolInputRequestContext(
    val bridge: ToolInputRequestBridge,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolInputRequestContext>
}

suspend fun requestToolInput(inputRequests: JsonObject): JsonObject {
    val bridge = currentCoroutineContext()[ToolInputRequestContext]?.bridge
        ?: error("Structured client input is unavailable for this tool execution")
    return bridge.request(inputRequests)
}
