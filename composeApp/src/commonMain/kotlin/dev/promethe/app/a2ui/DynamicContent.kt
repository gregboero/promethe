package dev.promethe.app.a2ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.serialization.json.*

/**
 * DynamicContent — manages the observable state for A2UI widgets.
 *
 * Inspired by Flutter rfw's DynamicContent: the agent sends data updates
 * via ChatEvent(type="ui_data"), and bindings in UiNode resolve against
 * this state. Form inputs are stored locally (zero round-trip) and sent
 * to the agent only on explicit submit.
 *
 * Usage:
 * ```
 * val dc = DynamicContent()
 * dc.update(serverData)          // merge server data
 * dc.setLocal("form.email", "x") // local form state
 * val value = dc.resolve("user.name")
 * val snapshot = dc.snapshot()    // send to agent on submit
 * ```
 */
class DynamicContent {
    /** Observable state map — Compose recomposes when values change */
    private val state: SnapshotStateMap<String, JsonElement> = mutableStateMapOf()

    /**
     * Merge server data into the state.
     * Supports flat keys ("user.name") and nested JsonObject.
     */
    fun update(data: JsonObject) {
        for ((key, value) in data) {
            if (value is JsonObject) {
                // Flatten nested objects: "user" → {"name": "Alice"}
                // becomes "user.name" → "Alice"
                flattenObject(key, value)
            } else {
                state[key] = value
            }
        }
    }

    /**
     * Set a local value (form input, toggle, etc.).
     * Does NOT trigger a server round-trip.
     */
    fun setLocal(
        path: String,
        value: String,
    ) {
        state[path] = JsonPrimitive(value)
    }

    /**
     * Set a local boolean value.
     */
    fun setLocalBool(
        path: String,
        value: Boolean,
    ) {
        state[path] = JsonPrimitive(value)
    }

    /**
     * Resolve a data binding path to its current value.
     * Returns the raw JsonElement, or null if the path is unset.
     */
    fun resolve(path: String): JsonElement? = state[path]

    /**
     * Resolve a binding path to a display string.
     */
    fun resolveString(path: String): String =
        when (val element = state[path]) {
            is JsonPrimitive -> element.content
            is JsonNull -> ""
            null -> ""
            else -> element.toString()
        }

    /**
     * Snapshot the current state for sending to the agent.
     * Converts the flat key-value map back to a nested JsonObject.
     */
    fun snapshot(): JsonObject =
        buildJsonObject {
            for ((key, value) in state) {
                put(key, value)
            }
        }

    /**
     * Clear all state.
     */
    fun clear() {
        state.clear()
    }

    /**
     * Remove a specific key.
     */
    fun remove(path: String) {
        state.remove(path)
    }

    /**
     * Check if a path exists in the state.
     */
    fun has(path: String): Boolean = state.containsKey(path)

    // ── Internal ──

    private fun flattenObject(
        prefix: String,
        obj: JsonObject,
    ) {
        for ((key, value) in obj) {
            val fullKey = "$prefix.$key"
            if (value is JsonObject) {
                flattenObject(fullKey, value)
            } else {
                state[fullKey] = value
            }
        }
    }
}
