package dev.promethe.core

import ai.koog.prompt.message.Message

data class PendingToolTurn(
    val assistantMessage: Message.Assistant,
    val toolCallId: String,
    val toolName: String,
    val result: String,
    val history: List<PositionedToolTurn> = emptyList(),
)

/** Index in the accompanying, already filtered and compressed conversation. */
data class PositionedToolTurn(
    val messageIndex: Int,
    val turn: PendingToolTurn,
)
