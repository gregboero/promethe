package dev.promethe.api.a2ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A2UI — Agent-to-UI protocol models (inspired by Flutter Remote Widgets).
 *
 * The agent sends a UiNode tree via ChatEvent(type="ui_layout").
 * Data updates are sent via ChatEvent(type="ui_data") without resending the layout.
 * User actions are sent back via ChatEvent(type="ui_action").
 *
 * UiNode uses an open String type (not sealed class) so the client-side
 * A2UIRegistry can be extended with custom renderers without modifying
 * this schema — exactly like Flutter's LocalWidgetLibrary pattern.
 */
@Serializable
data class UiNode(
    /** Widget type: "text", "button", "card", "form", "row", "column", "table", "chart", "image" */
    val type: String,
    /** Optional unique ID for targeting updates */
    val id: String = "",
    /** Static properties (label, style, color, etc.) */
    val props: Map<String, JsonElement> = emptyMap(),
    /** Data bindings: prop name → data path (e.g. "text" → "user.name") */
    val bindings: Map<String, String> = emptyMap(),
    /** Event handlers: event name → action definition (e.g. "onTap" → ActionDef) */
    val handlers: Map<String, ActionDef> = emptyMap(),
    /** Child nodes for recursive composition */
    val children: List<UiNode> = emptyList(),
)

/**
 * Defines an action to execute when an event handler fires.
 * The action string is sent back to the agent via ChatEvent(type="ui_action").
 */
@Serializable
data class ActionDef(
    /** Action identifier sent to the agent (e.g. "approve", "submit_form", "navigate") */
    val action: String,
    /** Additional parameters (e.g. "id" → "123") */
    val params: Map<String, String> = emptyMap(),
)
