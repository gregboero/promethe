package dev.promethe.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Default IO dispatcher for the Prométhé project.
 *
 * This is the shared IO dispatcher used across the codebase for blocking I/O operations.
 * Individual classes (like [AIAgent]) accept a `CoroutineDispatcher` constructor parameter
 * for testability, defaulting to this value.
 */
val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
