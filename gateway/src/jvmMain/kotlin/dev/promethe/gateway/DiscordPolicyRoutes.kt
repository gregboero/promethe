package dev.promethe.gateway

import dev.promethe.api.UpsertDiscordChannelRuleRequest
import dev.promethe.api.UpsertDiscordUserRuleRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put

internal fun Route.discordPolicyRoutes(
    service: DiscordPolicyService,
    onPolicyChanged: suspend () -> Unit,
) {
    get("/channels/discord/policy") {
        call.respond(service.current())
    }

    put("/channels/discord/policy/users/{userId}") {
        val userId = call.parameters["userId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
        val request = call.receive<UpsertDiscordUserRuleRequest>()
        call.respondPolicyMutation {
            val policy = service.upsertUserRule(userId, request)
            onPolicyChanged()
            policy
        }
    }

    delete("/channels/discord/policy/users/{userId}") {
        val userId = call.parameters["userId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val guildId = call.request.queryParameters["guildId"]
        val channelId = call.request.queryParameters["channelId"]
        call.respondPolicyMutation {
            val policy = service.removeUserRule(userId, guildId, channelId)
            onPolicyChanged()
            policy
        }
    }

    put("/channels/discord/policy/channels/{channelId}") {
        val channelId = call.parameters["channelId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
        val request = call.receive<UpsertDiscordChannelRuleRequest>()
        call.respondPolicyMutation {
            val policy = service.upsertChannelRule(channelId, request)
            onPolicyChanged()
            policy
        }
    }

    delete("/channels/discord/policy/channels/{channelId}") {
        val channelId = call.parameters["channelId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        call.respondPolicyMutation {
            val policy = service.removeChannelRule(channelId)
            onPolicyChanged()
            policy
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondPolicyMutation(
    mutation: suspend () -> dev.promethe.api.DiscordAccessPolicy,
) {
    try {
        respond(mutation())
    } catch (error: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "Invalid Discord policy")))
    }
}
