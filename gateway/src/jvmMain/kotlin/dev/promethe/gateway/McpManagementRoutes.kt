package dev.promethe.gateway

import dev.promethe.api.ErrorResponse
import dev.promethe.api.McpConnectResponse
import dev.promethe.api.McpToolInfo
import dev.promethe.core.McpBridge
import dev.promethe.core.ToolRegistry
import dev.promethe.core.mcp.McpConfigLoader
import dev.promethe.core.mcp.McpConfigurationStore
import dev.promethe.core.security.SecretCipher
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.SecurityAuditLogRow
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Persistent, encrypted MCP configuration management. */
fun Route.mcpManagementRoutes(
    mcpBridge: McpBridge,
    database: PrometheDatabaseApi,
    masterCipher: SecretCipher?,
) {
    val store = McpConfigurationStore(database, masterCipher)
    val environmentIds = McpConfigLoader.environmentConfigIds()

    get("/mcp/servers") {
        val servers = mcpBridge.listServers()
        call.respond(
            buildJsonObject {
                put(
                    "servers",
                    buildJsonArray {
                        servers.forEach { state ->
                            add(
                                redactedServer(state.config, state.status.name, state.tools.size, state.error, state.config.id in environmentIds),
                            )
                        }
                    },
                )
            },
        )
    }

    post("/mcp/servers") {
        val config = call.receive<McpBridge.McpServerConfig>()
        if (config.id in environmentIds) {
            return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("MCP server '${config.id}' is defined by MCP_SERVERS and cannot be changed from the UI"))
        }
        persistAndRegister(store, mcpBridge, config, call.request.local.remoteHost, database, "mcp_config_created", call)
    }

    put("/mcp/servers/{id}") {
        val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing MCP server id"))
        if (id in environmentIds) {
            return@put call.respond(HttpStatusCode.Conflict, ErrorResponse("MCP server '$id' is defined by MCP_SERVERS and cannot be changed from the UI"))
        }
        val config = call.receive<McpBridge.McpServerConfig>()
        if (config.id != id) return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Route id and payload id must match"))
        persistAndRegister(store, mcpBridge, config, call.request.local.remoteHost, database, "mcp_config_updated", call)
    }

    delete("/mcp/servers/{id}") {
        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing MCP server id"))
        if (id in environmentIds) {
            return@delete call.respond(HttpStatusCode.Conflict, ErrorResponse("MCP server '$id' is defined by MCP_SERVERS and cannot be deleted from the UI"))
        }
        mcpBridge.removeServer(id)
        store.delete(id)
        auditMcp(database, "mcp_config_deleted", id, call.request.local.remoteHost)
        call.respond(mapOf("status" to "deleted", "id" to id))
    }

    post("/mcp/servers/{id}/connect") {
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val result = mcpBridge.connectServer(id)
        result.fold(
            onSuccess = { tools ->
                call.respond(McpConnectResponse(tools = tools.map { tool -> McpToolInfo(name = tool.toolName, description = tool.description) }))
            },
            onFailure = { error -> call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error.message ?: "Connection failed")) },
        )
    }

    post("/mcp/servers/{id}/disconnect") {
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        mcpBridge.disconnectServer(id)
        call.respond(mapOf("status" to "disconnected"))
    }

    get("/mcp/tools") {
        val tools = mcpBridge.listMcpTools()
        val builtinTools = ToolRegistry.toolsSnapshot()
        call.respond(
            buildJsonObject {
                put("count", tools.size + builtinTools.size)
                put(
                    "tools",
                    buildJsonArray {
                        tools.forEach { tool ->
                            addJsonObject {
                                put("server", tool.serverId)
                                put("name", tool.toolName)
                                put("description", tool.description)
                                put("source", "MCP")
                            }
                        }
                        builtinTools.forEach { tool ->
                            addJsonObject {
                                put("server", JsonNull)
                                put("name", tool.name)
                                put("description", "Built-in tool: ${tool.name}")
                                put("source", "Built-in")
                            }
                        }
                    },
                )
            },
        )
    }
}

private suspend fun persistAndRegister(
    store: McpConfigurationStore,
    bridge: McpBridge,
    config: McpBridge.McpServerConfig,
    remoteAddress: String,
    database: PrometheDatabaseApi,
    event: String,
    call: io.ktor.server.application.ApplicationCall,
) {
    try {
        store.upsert(config)
        bridge.registerServer(config)
        auditMcp(database, event, config.id, remoteAddress)
        call.respond(HttpStatusCode.OK, mapOf("id" to config.id, "status" to "registered"))
    } catch (error: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid MCP configuration"))
    } catch (error: IllegalStateException) {
        call.respond(HttpStatusCode.Conflict, ErrorResponse(error.message ?: "MCP persistence unavailable"))
    }
}

private suspend fun auditMcp(
    database: PrometheDatabaseApi,
    event: String,
    id: String,
    remoteAddress: String,
) {
    database.insertSecurityAuditLog(
        SecurityAuditLogRow(
            eventType = event,
            actor = "owner",
            remoteAddress = remoteAddress.take(255),
            detail = "server=$id",
            createdAt = System.currentTimeMillis(),
        ),
    )
}

private fun redactedServer(
    config: McpBridge.McpServerConfig,
    status: String,
    toolCount: Int,
    error: String?,
    environmentManaged: Boolean,
) = buildJsonObject {
    put("id", config.id)
    put("name", config.name)
    put("transport", config.transport)
    put("command", config.command)
    put("url", config.url)
    put("enabled", config.enabled)
    put("status", status)
    put("toolCount", toolCount)
    put("error", error)
    put("environmentManaged", environmentManaged)
    put("env", buildJsonObject { config.env.keys.sorted().forEach { key -> put(key, "***") } })
    put("headers", buildJsonObject { config.headers.keys.sorted().forEach { key -> put(key, "***") } })
}
