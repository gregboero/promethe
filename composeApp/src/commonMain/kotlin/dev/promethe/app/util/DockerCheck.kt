package dev.promethe.app.util

/**
 * Check if Docker is available on the host.
 * Platform-specific: JVM calls DockerHealth, others return false.
 */
expect suspend fun checkDockerAvailable(): Boolean
