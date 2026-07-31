package dev.promethe.app.config

import dev.promethe.core.CredentialsStore

/**
 * Desktop (JVM) implementation — delegates to file-based CredentialsStore.
 * Persists to ~/.promethe/credentials.json
 */
actual object CredentialManager {
    actual fun load(): AppCredentials? {
        val creds = CredentialsStore.load() ?: return null
        return AppCredentials(
            apiKey = creds.apiKey,
            llmProvider = creds.llmProvider,
            llmApiKey = creds.llmApiKey,
            llmModel = creds.llmModel,
            llmApiKeys = creds.llmApiKeys,
            llmModels = creds.llmModels,
            gatewayUrl = creds.gatewayUrl,
            lastRemoteGatewayUrl = creds.lastRemoteGatewayUrl,
            ollamaUrl = creds.ollamaUrl,
            temperature = creds.temperature,
            maxTokens = creds.maxTokens,
            maxIterations = creds.maxIterations,
            executionBackend = creds.executionBackend,
            executionTimeoutMs = creds.executionTimeoutMs,
            maxOutputBytes = creds.maxOutputBytes,
            dockerImage = creds.dockerImage,
            sshHost = creds.sshHost,
            sshUser = creds.sshUser,
            sshKeyPath = creds.sshKeyPath,
            sshPort = creds.sshPort,
            maxContextTokens = creds.maxContextTokens,
            compressionThreshold = creds.compressionThreshold,
            memoryEnabled = creds.memoryEnabled,
            memoryProvider = creds.memoryProvider,
            honchoBaseUrl = creds.honchoBaseUrl,
            honchoApiKey = creds.honchoApiKey,
            tencentMemoryUrl = creds.tencentMemoryUrl,
            tencentMemoryServiceId = creds.tencentMemoryServiceId,
            tencentMemoryApiKey = creds.tencentMemoryApiKey,
            tracingBackend = creds.tracingBackend,
            langfusePublicKey = creds.langfusePublicKey,
            langfuseSecretKey = creds.langfuseSecretKey,
            langfuseHost = creds.langfuseHost,
            otlpEndpoint = creds.otlpEndpoint,
            approvalMode = creds.approvalMode,
            approvalTimeoutMs = creds.approvalTimeoutMs,
            gepaEnabled = creds.gepaEnabled,
            gepaIntervalMinutes = creds.gepaIntervalMinutes,
            gepaAutoApply = creds.gepaAutoApply,
            systemPrompt = creds.systemPrompt,
            theme = creds.theme,
            language = creds.language,
            remoteUser = creds.remoteUser,
        )
    }

    actual fun save(credentials: AppCredentials) {
        CredentialsStore.update { existing ->
            existing.copy(
                apiKey =
                    credentials.apiKey.ifBlank {
                        existing.apiKey.ifBlank(CredentialsStore::generateApiKey)
                    },
                llmProvider = credentials.llmProvider,
                llmApiKey = credentials.llmApiKey,
                llmModel = credentials.llmModel,
                llmApiKeys = credentials.llmApiKeys,
                llmModels = credentials.llmModels,
                gatewayUrl = credentials.gatewayUrl,
                lastRemoteGatewayUrl = credentials.lastRemoteGatewayUrl,
                ollamaUrl = credentials.ollamaUrl,
                temperature = credentials.temperature,
                maxTokens = credentials.maxTokens,
                maxIterations = credentials.maxIterations,
                executionBackend = credentials.executionBackend,
                executionTimeoutMs = credentials.executionTimeoutMs,
                maxOutputBytes = credentials.maxOutputBytes,
                dockerImage = credentials.dockerImage,
                sshHost = credentials.sshHost,
                sshUser = credentials.sshUser,
                sshKeyPath = credentials.sshKeyPath,
                sshPort = credentials.sshPort,
                maxContextTokens = credentials.maxContextTokens,
                compressionThreshold = credentials.compressionThreshold,
                memoryEnabled = credentials.memoryEnabled,
                memoryProvider = credentials.memoryProvider,
                honchoBaseUrl = credentials.honchoBaseUrl,
                honchoApiKey = credentials.honchoApiKey,
                tencentMemoryUrl = credentials.tencentMemoryUrl,
                tencentMemoryServiceId = credentials.tencentMemoryServiceId,
                tencentMemoryApiKey = credentials.tencentMemoryApiKey,
                tracingBackend = credentials.tracingBackend,
                langfusePublicKey = credentials.langfusePublicKey,
                langfuseSecretKey = credentials.langfuseSecretKey,
                langfuseHost = credentials.langfuseHost,
                otlpEndpoint = credentials.otlpEndpoint,
                approvalMode = credentials.approvalMode,
                approvalTimeoutMs = credentials.approvalTimeoutMs,
                gepaEnabled = credentials.gepaEnabled,
                gepaIntervalMinutes = credentials.gepaIntervalMinutes,
                gepaAutoApply = credentials.gepaAutoApply,
                systemPrompt = credentials.systemPrompt,
                theme = credentials.theme,
                language = credentials.language,
            )
        }
    }

    actual fun isConfigured(): Boolean = CredentialsStore.isConfigured()

    actual fun clearRemoteSessionHint() = Unit
}
