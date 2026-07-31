package dev.promethe.gateway

import dev.promethe.api.PrometheVersion
import io.ktor.server.application.plugin
import io.ktor.server.response.respond
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun Route.openApiRoutes() {
    get("/api/v1/openapi.json") {
        val routingRoot = call.application.plugin(RoutingRoot)
        call.respond(buildOpenApiDocument(routingRoot))
    }
}

internal fun buildOpenApiDocument(routingRoot: RoutingNode) =
    buildJsonObject {
        put("openapi", "3.1.0")
        put(
            "info",
            buildJsonObject {
                put("title", "Promethe Gateway API")
                put("version", PrometheVersion.CURRENT)
                put("description", "Generated from the Ktor routes mounted by this gateway instance.")
            },
        )
        put(
            "paths",
            buildJsonObject {
                collectRouteOperations(routingRoot).forEach { (path, methods) ->
                    put(
                        path,
                        buildJsonObject {
                            methods.sorted().forEach { method ->
                                put(method.lowercase(), buildOperation(path, method))
                            }
                        },
                    )
                }
            },
        )
        put(
            "components",
            buildJsonObject {
                put(
                    "securitySchemes",
                    buildJsonObject {
                        put(
                            "bearerAuth",
                            buildJsonObject {
                                put("type", "http")
                                put("scheme", "bearer")
                            },
                        )
                        put(
                            "ownerSession",
                            buildJsonObject {
                                put("type", "apiKey")
                                put("in", "cookie")
                                put("name", "promethe_session")
                            },
                        )
                    },
                )
            },
        )
        put("x-promethe-generated", true)
    }

private fun buildOperation(
    path: String,
    method: String,
) = buildJsonObject {
    put("operationId", operationId(method, path))
    put("responses", buildJsonObject { put("default", buildJsonObject { put("description", "Gateway response") }) })
    val parameters = PATH_PARAMETER.findAll(path).map { it.groupValues[1] }.toList()
    if (parameters.isNotEmpty()) {
        put(
            "parameters",
            buildJsonArray {
                parameters.forEach { name ->
                    add(
                        buildJsonObject {
                            put("name", name)
                            put("in", "path")
                            put("required", true)
                            put("schema", buildJsonObject { put("type", "string") })
                        },
                    )
                }
            },
        )
    }
    put(
        "security",
        if (isPublicRoute(path)) {
            buildJsonArray {}
        } else {
            buildJsonArray {
                add(buildJsonObject { put("bearerAuth", buildJsonArray {}) })
                add(buildJsonObject { put("ownerSession", buildJsonArray {}) })
            }
        },
    )
}

private fun collectRouteOperations(root: RoutingNode): Map<String, Set<String>> {
    val operations = sortedMapOf<String, MutableSet<String>>()

    fun visit(
        node: RoutingNode,
        segments: List<String>,
        method: String?,
    ) {
        val nextSegments = segments + selectorSegment(node.selector)
        val nextMethod = (node.selector as? HttpMethodRouteSelector)?.method?.value ?: method
        if (node.hasHandler() && nextMethod != null) {
            val path = "/" + nextSegments.filter(String::isNotBlank).joinToString("/")
            operations.getOrPut(path.replace(DUPLICATE_SLASH, "/")) { sortedSetOf() }.add(nextMethod)
        }
        node.children.forEach { child -> visit(child, nextSegments, nextMethod) }
    }

    visit(root, emptyList(), null)
    return operations
}

private fun selectorSegment(selector: Any): List<String> =
    when (selector) {
        is PathSegmentConstantRouteSelector -> listOf(selector.value)
        is PathSegmentParameterRouteSelector -> listOf("${selector.prefix.orEmpty()}{${selector.name}}${selector.suffix.orEmpty()}")
        is PathSegmentOptionalParameterRouteSelector -> listOf("${selector.prefix.orEmpty()}{${selector.name}}${selector.suffix.orEmpty()}")
        is PathSegmentTailcardRouteSelector -> listOf("{${selector.name.ifBlank { "path" }}}")
        PathSegmentWildcardRouteSelector -> listOf("{segment}")
        else -> emptyList()
    }

private fun operationId(
    method: String,
    path: String,
): String =
    (method.lowercase() + "_" + path.trim('/'))
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trim('_')

private fun isPublicRoute(path: String): Boolean =
    path == "/health" ||
        path == "/auth/login" ||
        path == "/auth/oauth/callback" ||
        path.startsWith("/webhook/")

private val PATH_PARAMETER = Regex("\\{([^}]+)}")
private val DUPLICATE_SLASH = Regex("/{2,}")
