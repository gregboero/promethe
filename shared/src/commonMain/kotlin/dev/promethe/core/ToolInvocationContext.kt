package dev.promethe.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** Carries the authenticated Promethe invocation into typed Koog tools. */
class ToolInvocationContext(
    val invocation: ToolExecutionRequest,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolInvocationContext>
}

suspend fun currentToolInvocation(): ToolExecutionRequest? = currentCoroutineContext()[ToolInvocationContext]?.invocation

suspend fun projectScopedPath(requestedPath: String): String = scopePathToWorkspace(requestedPath, currentToolInvocation()?.workspaceRelativePath)

internal fun scopePathToWorkspace(
    requestedPath: String,
    workspaceRelativePath: String?,
): String {
    val projectRoot = workspaceRelativePath?.trim()?.trimEnd('/', '\\')?.takeIf { it.isNotEmpty() }
        ?: return requestedPath
    val requested = requestedPath.trim().ifBlank { "." }
    if (requested.startsWith('/') || requested.startsWith('\\') || WINDOWS_ABSOLUTE_PATH.matches(requested)) {
        return requested
    }
    require(requested.split('/', '\\').none { it == ".." }) {
        "Parent-directory traversal is not allowed from an active project workspace"
    }
    return if (requested == ".") {
        projectRoot
    } else {
        "$projectRoot/${requested.replace('\\', '/').removePrefix("./")}"
    }
}

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
