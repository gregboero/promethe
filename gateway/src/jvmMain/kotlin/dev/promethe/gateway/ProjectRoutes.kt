package dev.promethe.gateway

import dev.promethe.api.ACTIVE_PROJECT_SETTING_KEY
import dev.promethe.api.CreateProjectRequest
import dev.promethe.api.ProjectInfo
import dev.promethe.api.ProjectListResponse
import dev.promethe.api.UpdateProjectRequest
import dev.promethe.db.ProjectRow
import dev.promethe.db.PrometheDatabaseApi
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private const val MAX_PROJECT_NAME_LENGTH = 120
private const val MAX_PROJECT_DESCRIPTION_LENGTH = 4_000
private const val MAX_PROJECT_INSTRUCTIONS_LENGTH = 16_000

fun Route.projectRoutes(
    database: PrometheDatabaseApi,
    workspaceRoot: String,
) {
    val canonicalWorkspaceRoot = Path.of(workspaceRoot).toRealPath()

    get("/projects") {
        call.respond(database.projectListResponse())
    }

    post("/projects") {
        val request = call.receive<CreateProjectRequest>()
        val validationError = validateProjectFields(request.name, request.description, request.instructions)
        if (validationError != null) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
        }

        val id = "project-${UUID.randomUUID()}"
        val relativeWorkspace = "projects/$id"
        val projectWorkspace = resolveProjectWorkspace(canonicalWorkspaceRoot, relativeWorkspace)
        val now = System.currentTimeMillis()
        val project =
            ProjectRow(
                id = id,
                name = request.name.trim(),
                description = request.description.trim(),
                instructions = request.instructions.trim(),
                workspacePath = relativeWorkspace,
                memoryNamespace = "project:$id",
                createdAt = now,
                updatedAt = now,
            )

        Files.createDirectories(projectWorkspace)
        writeProjectBrief(projectWorkspace, project)
        database.insertProject(project)
        if (database.getSetting(ACTIVE_PROJECT_SETTING_KEY).isNullOrBlank()) {
            database.upsertSetting(ACTIVE_PROJECT_SETTING_KEY, id)
        }
        call.respond(HttpStatusCode.Created, database.projectInfo(project))
    }

    get("/projects/{id}") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val project = database.getProject(id)
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
        call.respond(database.projectInfo(project))
    }

    put("/projects/{id}") {
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
        val existing = database.getProject(id)
            ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
        val request = call.receive<UpdateProjectRequest>()
        val updated =
            existing.copy(
                name = request.name?.trim() ?: existing.name,
                description = request.description?.trim() ?: existing.description,
                instructions = request.instructions?.trim() ?: existing.instructions,
                archived = request.archived ?: existing.archived,
                updatedAt = System.currentTimeMillis(),
            )
        val validationError = validateProjectFields(updated.name, updated.description, updated.instructions)
        if (validationError != null) {
            return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
        }

        val projectWorkspace = resolveProjectWorkspace(canonicalWorkspaceRoot, updated.workspacePath)
        Files.createDirectories(projectWorkspace)
        writeProjectBrief(projectWorkspace, updated)
        database.updateProject(updated)
        if (updated.archived && database.getSetting(ACTIVE_PROJECT_SETTING_KEY) == id) {
            database.deleteSetting(ACTIVE_PROJECT_SETTING_KEY)
        }
        call.respond(database.projectInfo(updated))
    }

    put("/projects/{id}/active") {
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
        val project = database.getProject(id)
            ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
        if (project.archived) {
            return@put call.respond(HttpStatusCode.Conflict, mapOf("error" to "An archived project cannot be active"))
        }
        database.upsertSetting(ACTIVE_PROJECT_SETTING_KEY, id)
        call.respond(database.projectInfo(project, id, database.getProjectSessionCounts()))
    }

    delete("/projects/active") {
        database.deleteSetting(ACTIVE_PROJECT_SETTING_KEY)
        call.respond(mapOf("status" to "cleared"))
    }

    delete("/projects/{id}") {
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val project = database.getProject(id)
            ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Project not found"))
        val archived = project.copy(archived = true, updatedAt = System.currentTimeMillis())
        database.updateProject(archived)
        if (database.getSetting(ACTIVE_PROJECT_SETTING_KEY) == id) {
            database.deleteSetting(ACTIVE_PROJECT_SETTING_KEY)
        }
        call.respond(database.projectInfo(archived))
    }
}

private suspend fun PrometheDatabaseApi.projectListResponse(): ProjectListResponse {
    val activeProjectId = getSetting(ACTIVE_PROJECT_SETTING_KEY)
    val counts = getProjectSessionCounts()
    val projects = getAllProjects().map { projectInfo(it, activeProjectId, counts) }
    return ProjectListResponse(projects = projects, activeProjectId = activeProjectId)
}

private suspend fun PrometheDatabaseApi.projectInfo(
    project: ProjectRow,
): ProjectInfo = projectInfo(project, getSetting(ACTIVE_PROJECT_SETTING_KEY), getProjectSessionCounts())

private suspend fun PrometheDatabaseApi.projectInfo(
    project: ProjectRow,
    activeProjectId: String?,
    sessionCounts: Map<String, Int>,
): ProjectInfo =
    ProjectInfo(
        id = project.id,
        name = project.name,
        description = project.description,
        instructions = project.instructions,
        workspacePath = project.workspacePath,
        memoryNamespace = project.memoryNamespace,
        active = project.id == activeProjectId,
        archived = project.archived,
        sessionCount = sessionCounts[project.id] ?: 0,
        memoryCount = getAllUserFacts(project.memoryNamespace).size,
        createdAt = project.createdAt,
        updatedAt = project.updatedAt,
    )

private fun validateProjectFields(
    name: String,
    description: String,
    instructions: String,
): String? =
    when {
        name.isBlank() -> "Project name is required"
        name.length > MAX_PROJECT_NAME_LENGTH -> "Project name is too long"
        description.length > MAX_PROJECT_DESCRIPTION_LENGTH -> "Project description is too long"
        instructions.length > MAX_PROJECT_INSTRUCTIONS_LENGTH -> "Project instructions are too long"
        else -> null
    }

private fun resolveProjectWorkspace(
    workspaceRoot: Path,
    relativePath: String,
): Path {
    val resolved = workspaceRoot.resolve(relativePath).normalize()
    check(resolved.startsWith(workspaceRoot)) { "Project workspace escaped the configured workspace" }
    return resolved
}

private fun writeProjectBrief(
    workspace: Path,
    project: ProjectRow,
) {
    val content =
        buildString {
            appendLine("# ${project.name}")
            if (project.description.isNotBlank()) {
                appendLine()
                appendLine(project.description)
            }
            if (project.instructions.isNotBlank()) {
                appendLine()
                appendLine("## Instructions")
                appendLine()
                appendLine(project.instructions)
            }
        }
    Files.writeString(workspace.resolve("PROJECT.md"), content)
}
