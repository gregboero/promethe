package dev.promethe.core

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Central logger factory for Prométhé core.
 * Usage: `private val logger = Log.create("ClassName")` or `private val logger = Log.create {}`
 */
object Log {
    fun create(name: String) = KotlinLogging.logger(name)

    fun create(func: () -> Unit) = KotlinLogging.logger(func)
}
