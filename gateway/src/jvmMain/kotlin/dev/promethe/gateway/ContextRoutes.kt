package dev.promethe.gateway

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File

// ── DTOs ──

@Serializable
data class ContextFileDto(
    val name: String,
    val content: String,
    val exists: Boolean = true,
    val sizeBytes: Long = 0,
)

@Serializable
data class ContextFilesResponse(
    val files: List<ContextFileDto>,
    val directory: String,
)

@Serializable
data class UpdateContextFileRequest(
    val content: String,
)

@Serializable
data class ContextErrorResponse(
    val error: String,
)

@Serializable
data class ContextDeleteResponse(
    val deleted: Boolean,
    val name: String,
)

/**
 * Context Files REST API — list, read, update, and delete project context files.
 *
 * Recognized files: `.promethe.md`, `SOUL.md`, `AGENTS.md`, `CONTEXT.md`
 */
fun Route.contextRoutes() {
    val contextFileNames =
        listOf(
            ".promethe.md",
            "SOUL.md",
            "AGENTS.md",
            "CONTEXT.md",
        )

    val workingDir = run {
        val base = File(System.getProperty("user.dir"))
        // Si lancé depuis la racine du repo (ex: projectRandD/), on cherche le sous-dossier promethe/
        val candidates = listOf(
            base,
            File(base, "promethe"),
            File(base, "..\\promethe"),
        )
        candidates.firstOrNull { it.exists() && it.isDirectory } ?: base
    }

    // GET /context/files — list all context files with their content
    get("/context/files") {
        val files =
            contextFileNames.map { name ->
                val file = File(workingDir, name)
                if (file.exists() && file.isFile) {
                    ContextFileDto(name = name, content = file.readText(), exists = true, sizeBytes = file.length())
                } else {
                    ContextFileDto(name = name, content = "", exists = false, sizeBytes = 0)
                }
            }
        call.respond(ContextFilesResponse(files = files, directory = workingDir.absolutePath))
    }

    // GET /context/files/{name} — get a single context file
    get("/context/files/{name}") {
        val name =
            call.parameters["name"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ContextErrorResponse("Missing file name"))
        if (name !in contextFileNames) {
            return@get call.respond(HttpStatusCode.Forbidden, ContextErrorResponse("Not a recognized context file"))
        }
        val file = File(workingDir, name)
        if (file.exists()) {
            call.respond(ContextFileDto(name = name, content = file.readText(), exists = true, sizeBytes = file.length()))
        } else {
            call.respond(ContextFileDto(name = name, content = "", exists = false, sizeBytes = 0))
        }
    }

    // PUT /context/files/{name} — create or update a context file
    put("/context/files/{name}") {
        val name =
            call.parameters["name"]
                ?: return@put call.respond(HttpStatusCode.BadRequest, ContextErrorResponse("Missing file name"))
        if (name !in contextFileNames) {
            return@put call.respond(HttpStatusCode.Forbidden, ContextErrorResponse("Not a recognized context file"))
        }
        val request = call.receive<UpdateContextFileRequest>()
        val file = File(workingDir, name)
        file.parentFile?.mkdirs()
        file.writeText(request.content)
        call.respond(ContextFileDto(name = name, content = request.content, exists = true, sizeBytes = file.length()))
    }

    // DELETE /context/files/{name} — delete a context file
    delete("/context/files/{name}") {
        val name =
            call.parameters["name"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest, ContextErrorResponse("Missing file name"))
        if (name !in contextFileNames) {
            return@delete call.respond(HttpStatusCode.Forbidden, ContextErrorResponse("Not a recognized context file"))
        }
        val file = File(workingDir, name)
        val deleted = file.delete()
        call.respond(ContextDeleteResponse(deleted = deleted, name = name))
    }
}
