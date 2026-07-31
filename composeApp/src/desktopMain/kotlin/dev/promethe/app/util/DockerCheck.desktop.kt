package dev.promethe.app.util

import dev.promethe.core.execution.DockerHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun checkDockerAvailable(): Boolean =
    withContext(Dispatchers.IO) {
        DockerHealth.isAvailable()
    }
