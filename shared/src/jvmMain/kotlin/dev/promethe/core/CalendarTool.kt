package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ══════════════════════════════════════════════════════════════
//  Calendar Tool — Google Calendar API integration
//  Create, list, update, and delete calendar events.
//  Requires GOOGLE_CALENDAR_API_KEY or OAuth token.
// ══════════════════════════════════════════════════════════════

@Serializable
data class CalendarArgs(
    @property:LLMDescription("Action: 'list_events', 'create_event', 'delete_event', 'get_event'")
    val action: String,
    @property:LLMDescription("Calendar ID. Default: 'primary'.")
    val calendarId: String = "primary",
    @property:LLMDescription("Event title/summary for creating events.")
    val title: String = "",
    @property:LLMDescription("Event description.")
    val description: String = "",
    @property:LLMDescription("Start time in ISO 8601 format (e.g., '2024-03-15T09:00:00-04:00').")
    val startTime: String = "",
    @property:LLMDescription("End time in ISO 8601 format.")
    val endTime: String = "",
    @property:LLMDescription("Event location.")
    val location: String = "",
    @property:LLMDescription("Attendees as comma-separated email addresses.")
    val attendees: String = "",
    @property:LLMDescription("Event ID for get/delete actions.")
    val eventId: String = "",
    @property:LLMDescription("Max results to return for list (default: 10).")
    val maxResults: Int = 10,
    @property:LLMDescription("Timezone (e.g., 'America/New_York'). Default: UTC.")
    val timezone: String = "UTC",
)

class CalendarTool(
    private val httpClient: HttpClient,
    private val staticAccessToken: String = "",
    private val tokenSupplier: suspend () -> String? = { null },
) : SimpleTool<CalendarArgs>(
        argsType = typeToken<CalendarArgs>(),
        name = "calendar",
        description = "Manage Google Calendar events: list, create, get, delete events.",
    ) {
    private val json = PrometheJson
    private val baseUrl = "https://www.googleapis.com/calendar/v3"

    override suspend fun execute(args: CalendarArgs): String {
        if (resolveToken().isNullOrBlank()) return "[ERROR] Google Calendar token not configured (OAuth or GOOGLE_CALENDAR_TOKEN required)"

        return try {
            when (args.action.lowercase()) {
                "list_events" -> listEvents(args.calendarId, args.maxResults, args.timezone)
                "create_event" -> createEvent(args)
                "get_event" -> getEvent(args.calendarId, args.eventId)
                "delete_event" -> deleteEvent(args.calendarId, args.eventId)
                else -> "[ERROR] Unknown calendar action: ${args.action}. Use: list_events, create_event, get_event, delete_event"
            }
        } catch (e: Exception) {
            "[ERROR] Calendar API call failed: ${e.message}"
        }
    }

    private suspend fun listEvents(
        calendarId: String,
        maxResults: Int,
        timezone: String,
    ): String {
        val response =
            httpClient.get("$baseUrl/calendars/$calendarId/events") {
                header("Authorization", "Bearer ${requireToken()}")
                parameter("maxResults", maxResults)
                parameter("timeZone", timezone)
                parameter("orderBy", "startTime")
                parameter("singleEvents", true)
                parameter(
                    "timeMin",
                    java.time.Instant
                        .now()
                        .toString(),
                )
                timeout { requestTimeoutMillis = 10_000 }
            }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val items = body["items"]?.jsonArray ?: return "No upcoming events."

        return items
            .joinToString("\n") { el ->
                val obj = el.jsonObject
                val start = obj["start"]?.jsonObject
                val dateTime =
                    start?.get("dateTime")?.jsonPrimitive?.content
                        ?: start?.get("date")?.jsonPrimitive?.content ?: "?"
                "📅 $dateTime — ${obj["summary"]?.jsonPrimitive?.content ?: "(no title)"}"
            }.ifBlank { "No upcoming events." }
    }

    private suspend fun createEvent(args: CalendarArgs): String {
        val eventBody =
            buildJsonObject {
                put("summary", args.title)
                if (args.description.isNotBlank()) put("description", args.description)
                if (args.location.isNotBlank()) put("location", args.location)
                putJsonObject("start") {
                    put("dateTime", args.startTime)
                    put("timeZone", args.timezone)
                }
                putJsonObject("end") {
                    put("dateTime", args.endTime)
                    put("timeZone", args.timezone)
                }
                if (args.attendees.isNotBlank()) {
                    putJsonArray("attendees") {
                        args.attendees.split(",").map { it.trim() }.forEach { email ->
                            addJsonObject { put("email", email) }
                        }
                    }
                }
            }

        val response =
            httpClient.post("$baseUrl/calendars/${args.calendarId}/events") {
                header("Authorization", "Bearer ${requireToken()}")
                contentType(ContentType.Application.Json)
                timeout { requestTimeoutMillis = 10_000 }
                setBody(eventBody.toString())
            }
        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return "Event created: ${obj["summary"]?.jsonPrimitive?.content} — ${obj["htmlLink"]?.jsonPrimitive?.content}"
    }

    private suspend fun getEvent(
        calendarId: String,
        eventId: String,
    ): String {
        if (eventId.isBlank()) return "[ERROR] Event ID required for get_event"
        val response =
            httpClient.get("$baseUrl/calendars/$calendarId/events/$eventId") {
                header("Authorization", "Bearer ${requireToken()}")
                timeout { requestTimeoutMillis = 10_000 }
            }
        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return buildString {
            appendLine("Event: ${obj["summary"]?.jsonPrimitive?.content}")
            appendLine("Start: ${obj["start"]?.jsonObject?.get("dateTime")?.jsonPrimitive?.content}")
            appendLine("End: ${obj["end"]?.jsonObject?.get("dateTime")?.jsonPrimitive?.content}")
            appendLine("Location: ${obj["location"]?.jsonPrimitive?.content ?: "none"}")
            appendLine("Description: ${obj["description"]?.jsonPrimitive?.content ?: "none"}")
            appendLine("Link: ${obj["htmlLink"]?.jsonPrimitive?.content}")
        }
    }

    private suspend fun deleteEvent(
        calendarId: String,
        eventId: String,
    ): String {
        if (eventId.isBlank()) return "[ERROR] Event ID required for delete_event"
        val response =
            httpClient.delete("$baseUrl/calendars/$calendarId/events/$eventId") {
                header("Authorization", "Bearer ${requireToken()}")
                timeout { requestTimeoutMillis = 10_000 }
            }
        return if (response.status.isSuccess()) {
            "Event deleted: $eventId"
        } else {
            "[ERROR] Failed to delete event: ${response.status}"
        }
    }

    private suspend fun resolveToken(): String? = tokenSupplier()?.takeIf { it.isNotBlank() } ?: staticAccessToken.takeIf { it.isNotBlank() }

    private suspend fun requireToken(): String = resolveToken() ?: throw IllegalStateException("Google Calendar token not configured")
}
