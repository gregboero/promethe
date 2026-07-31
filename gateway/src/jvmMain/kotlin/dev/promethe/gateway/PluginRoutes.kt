package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.PluginLoader
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.pluginRoutes(pluginLoader: PluginLoader) {
    get("/plugins") {
        try {
            val manifests = pluginLoader.discover()
            val totalPlugins = manifests.size
            val enabledPlugins = manifests.count { it.enabled }
            val totalTools = manifests.filter { it.enabled }.sumOf { it.tools.size }

            val dtos = manifests.map { manifest ->
                PluginResponse(
                    name = manifest.name,
                    version = manifest.version,
                    description = manifest.description,
                    author = manifest.author,
                    enabled = manifest.enabled,
                    toolCount = manifest.tools.size,
                    hookCount = manifest.hooks.size,
                    promptCount = manifest.prompts.size,
                )
            }
            call.respond(
                PluginListResponse(
                    plugins = dtos,
                    totalPlugins = totalPlugins,
                    enabledPlugins = enabledPlugins,
                    totalTools = totalTools,
                ),
            )
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }

    post("/plugins/{name}/toggle") {
        try {
            val name = call.parameters["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            // Receive request to ensure type validation
            val req = call.receive<PluginToggleRequest>()
            call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "Plugin toggle runtime is not implemented yet"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }

    get("/plugins/{name}") {
        try {
            val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val manifest = pluginLoader.discover().find { it.name.equals(name, ignoreCase = true) }
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Plugin not found"))

            val response = PluginResponse(
                name = manifest.name,
                version = manifest.version,
                description = manifest.description,
                author = manifest.author,
                enabled = manifest.enabled,
                toolCount = manifest.tools.size,
                hookCount = manifest.hooks.size,
                promptCount = manifest.prompts.size,
            )
            call.respond(response)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
    }
}
