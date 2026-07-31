package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
enum class XaiApiMode {
    RESPONSES,
    CHAT_COMPLETIONS,
}
