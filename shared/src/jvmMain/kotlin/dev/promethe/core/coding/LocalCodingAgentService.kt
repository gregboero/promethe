package dev.promethe.core.coding

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.api.CapabilityAuthentication
import dev.promethe.core.ApprovalGate
import dev.promethe.core.PrometheJson
import dev.promethe.core.ToolRegistry
import dev.promethe.core.config.ConfigProvider
import dev.promethe.core.currentToolInvocation
import dev.promethe.db.PrometheDatabaseApi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LocalCodingAgentService internal constructor(
    private val workspace: Path,
    private val database: PrometheDatabaseApi,
    private val approvalGate: ApprovalGate,
    private val processFactory: TrustedLocalAgentProcessFactory = JvmTrustedLocalAgentProcessFactory,
) {
    private val adapters =
        listOf(
            CodexLocalAdapter(workspace, approvalGate, processFactory),
            ClaudeCodeLocalAdapter(workspace, approvalGate, processFactory),
        ).associateBy(LocalCodingAgentAdapter::kind)
    private val writeLock = Mutex()

    @Volatile
    private var currentStatuses: Map<LocalCodingAgentKind, LocalCodingAgentStatus> = emptyMap()

    suspend fun refresh(): List<LocalCodingAgentStatus> {
        val statuses = LocalCodingAgentKind.entries.associateWith { kind -> detect(kind) }
        currentStatuses = statuses
        LocalCodingAgentKind.entries.forEach { kind -> ToolRegistry.unregister(kind.toolName) }
        statuses.values
            .filter { status ->
                status.available && status.authentication == CapabilityAuthentication.AUTHENTICATED
            }.forEach { status ->
                ToolRegistry.register(LocalCodingAgentTool(status.kind, this))
            }
        return statuses.values.toList()
    }

    fun statuses(): List<LocalCodingAgentStatus> =
        LocalCodingAgentKind.entries.map { kind ->
            currentStatuses[kind]
                ?: LocalCodingAgentStatus(
                    kind = kind,
                    available = false,
                    limitations = listOf("Detection has not completed"),
                )
        }

    suspend fun execute(
        kind: LocalCodingAgentKind,
        request: LocalCodingAgentRequest,
    ): LocalCodingAgentResult {
        require(request.task.isNotBlank()) { "A coding task is required" }
        require(request.task.length <= MAX_TASK_LENGTH) { "Coding task exceeds $MAX_TASK_LENGTH characters" }
        val status = currentStatuses[kind] ?: detect(kind).also { currentStatuses = currentStatuses + (kind to it) }
        require(
            status.available &&
                status.authentication == CapabilityAuthentication.AUTHENTICATED &&
                status.executable != null &&
                status.sha256 != null,
        ) {
            status.limitations.firstOrNull() ?: "${kind.displayName} is unavailable"
        }
        val persistedSession = request.externalSessionId ?: loadExternalSession(request.prometheSessionId, kind)
        val effectiveRequest = request.copy(externalSessionId = persistedSession)
        val effectiveWorkspace = resolveRequestWorkspace(request.workspaceRelativePath)
        val action: suspend () -> LocalCodingAgentResult = {
            adapterFor(kind, effectiveWorkspace).execute(
                TrustedExecutable(status.executable, status.sha256),
                effectiveRequest,
            )
        }
        val result =
            if (request.accessMode == LocalCodingAccessMode.WORKSPACE_WRITE) {
                writeLock.withLock { action() }
            } else {
                action()
            }
        result.externalSessionId?.let { persistExternalSession(request.prometheSessionId, kind, it) }
        return result
    }

    private fun resolveRequestWorkspace(relativePath: String?): Path {
        val relative = relativePath?.trim()?.takeIf(String::isNotBlank) ?: return workspace
        require(!Path.of(relative).isAbsolute) { "Project workspace must be relative to the Promethe workspace" }
        val resolved = workspace.resolve(relative).normalize()
        require(resolved.startsWith(workspace)) { "Project workspace escaped the Promethe workspace" }
        require(Files.isDirectory(resolved)) { "Project workspace does not exist" }
        return resolved.toRealPath()
    }

    private fun adapterFor(
        kind: LocalCodingAgentKind,
        effectiveWorkspace: Path,
    ): LocalCodingAgentAdapter =
        when (kind) {
            LocalCodingAgentKind.CODEX -> CodexLocalAdapter(effectiveWorkspace, approvalGate, processFactory)
            LocalCodingAgentKind.CLAUDE_CODE -> ClaudeCodeLocalAdapter(effectiveWorkspace, approvalGate, processFactory)
        }

    private suspend fun detect(kind: LocalCodingAgentKind): LocalCodingAgentStatus {
        val executablePath = resolveExecutable(kind)
            ?: return LocalCodingAgentStatus(
                kind = kind,
                available = false,
                limitations = listOf("${kind.executableName} was not found as a native executable on PATH"),
            )
        return runCatching {
            val executable = trustedExecutable(executablePath)
            val version = probe(executable, listOf("--version")).firstLine
            require(version.isNotBlank()) { "Version probe returned no output" }
            val authentication =
                when (kind) {
                    LocalCodingAgentKind.CODEX -> {
                        (adapters.getValue(kind) as CodexLocalAdapter).authenticationStatus(executable)
                    }

                    LocalCodingAgentKind.CLAUDE_CODE -> {
                        if (probe(executable, listOf("auth", "status")).exitCode == 0) {
                            CapabilityAuthentication.AUTHENTICATED
                        } else {
                            CapabilityAuthentication.SIGNED_OUT
                        }
                    }
                }
            LocalCodingAgentStatus(
                kind = kind,
                available = true,
                version = version.take(MAX_VERSION_LENGTH),
                authentication = authentication,
                limitations =
                    buildList {
                        add("Desktop Windows, macOS and Linux only")
                        add("Cloud execution is not included")
                        if (kind == LocalCodingAgentKind.CODEX) {
                            add("Trusted user-level Codex hooks remain inside the local CLI trust boundary")
                        }
                        if (authentication == CapabilityAuthentication.SIGNED_OUT) {
                            add("Sign in with the CLI before delegation")
                        }
                    },
                executable = executable.path,
                sha256 = executable.sha256,
            )
        }.getOrElse { error ->
            LocalCodingAgentStatus(
                kind = kind,
                available = false,
                limitations = listOf(error.message?.take(300) ?: "Executable validation failed"),
            )
        }
    }

    private suspend fun probe(
        executable: TrustedExecutable,
        arguments: List<String>,
    ): ProbeResult {
        val process = processFactory.start(executable, arguments, workspace, emptyMap())
        return try {
            val line = process.readLine(PROBE_TIMEOUT_MILLIS).orEmpty().trim()
            val exitCode = process.awaitExit(PROBE_TIMEOUT_MILLIS) ?: -1
            ProbeResult(exitCode, line.ifBlank { process.stderr().lineSequence().firstOrNull().orEmpty() })
        } finally {
            process.cancel()
        }
    }

    private fun resolveExecutable(kind: LocalCodingAgentKind): Path? {
        ConfigProvider.get().get(kind.configuredPathKey, "").trim().takeIf(String::isNotBlank)?.let { configured ->
            return Path.of(configured).takeIf { Files.isRegularFile(it) }
        }
        val windows = System.getProperty("os.name").contains("win", ignoreCase = true)
        val names = if (windows) listOf("${kind.executableName}.exe") else listOf(kind.executableName)
        return System.getenv("PATH")
            .orEmpty()
            .split(System.getProperty("path.separator"))
            .asSequence()
            .filter(String::isNotBlank)
            .flatMap { directory -> names.asSequence().map { name -> Path.of(directory, name) } }
            .firstOrNull { Files.isRegularFile(it) }
    }

    private suspend fun loadExternalSession(
        prometheSessionId: String,
        kind: LocalCodingAgentKind,
    ): String? {
        val metadata = database.getAllSessions().firstOrNull { it.id == prometheSessionId }?.metadata ?: return null
        return parseMetadata(metadata)[SESSION_METADATA_KEY]
            ?.jsonObject
            ?.get(kind.id)
            ?.jsonPrimitive
            ?.contentOrNull
    }

    private suspend fun persistExternalSession(
        prometheSessionId: String,
        kind: LocalCodingAgentKind,
        externalSessionId: String,
    ) {
        database.insertSessionOrIgnore(prometheSessionId, Clock.System.now().toEpochMilliseconds(), "{}")
        val existing = database.getAllSessions().firstOrNull { it.id == prometheSessionId }?.metadata.orEmpty()
        val root = parseMetadata(existing)
        val sessions = root[SESSION_METADATA_KEY]?.jsonObject ?: JsonObject(emptyMap())
        val updated =
            JsonObject(
                root.toMutableMap().apply {
                    put(
                        SESSION_METADATA_KEY,
                        JsonObject(sessions.toMutableMap().apply { put(kind.id, kotlinx.serialization.json.JsonPrimitive(externalSessionId)) }),
                    )
                },
            )
        database.updateSessionMetadata(prometheSessionId, updated.toString())
    }

    private fun parseMetadata(
        value: String,
    ): JsonObject =
        runCatching { PrometheJson.parseToJsonElement(value).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))

    private data class ProbeResult(
        val exitCode: Int,
        val firstLine: String,
    )
}

@Serializable
data class LocalCodingAgentToolArgs(
    @property:LLMDescription("Concrete coding, review, debugging, or repository task to delegate.")
    val task: String,
    @property:LLMDescription("Access mode: READ_ONLY or WORKSPACE_WRITE. Use READ_ONLY unless edits are required.")
    val accessMode: LocalCodingAccessMode = LocalCodingAccessMode.READ_ONLY,
    @property:LLMDescription("Optional provider session id returned by a previous delegation.")
    val externalSessionId: String? = null,
)

private class LocalCodingAgentTool(
    private val kind: LocalCodingAgentKind,
    private val service: LocalCodingAgentService,
) : SimpleTool<LocalCodingAgentToolArgs>(
        argsType = typeToken<LocalCodingAgentToolArgs>(),
        name = kind.toolName,
        description =
            "Delegate a repository task to ${kind.displayName}. " +
                "Runs inside the current Promethe agent loop and always requires local approval.",
    ) {
    override suspend fun execute(args: LocalCodingAgentToolArgs): String {
        val invocation = currentToolInvocation()
            ?: return "[ERROR] Local coding delegation requires a Promethe tool invocation context"
        val result =
            service.execute(
                kind,
                LocalCodingAgentRequest(
                    task = args.task,
                    accessMode = args.accessMode,
                    prometheSessionId = invocation.sessionId,
                    externalSessionId = args.externalSessionId,
                    workspaceRelativePath = invocation.workspaceRelativePath,
                ),
            )
        return buildString {
            appendLine("${kind.displayName} completed the delegated task.")
            result.externalSessionId?.let { appendLine("External session: $it") }
            append(result.summary)
        }
    }
}

private const val PROBE_TIMEOUT_MILLIS = 5_000L
private const val MAX_TASK_LENGTH = 100_000
private const val MAX_VERSION_LENGTH = 160
private const val SESSION_METADATA_KEY = "localCodingAgents"
