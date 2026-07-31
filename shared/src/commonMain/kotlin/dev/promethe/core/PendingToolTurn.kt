package dev.promethe.core

import ai.koog.prompt.message.Message

data class PendingToolTurn(
    val assistantMessage: Message.Assistant,
    val toolCallId: String,
    val toolName: String,
    val result: String,
)
