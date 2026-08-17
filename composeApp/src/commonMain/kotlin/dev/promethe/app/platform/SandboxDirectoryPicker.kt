package dev.promethe.app.platform

/** Opens the platform folder picker and returns one selected directory. */
expect suspend fun pickSandboxDirectory(): String?
