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
import java.util.Base64

// ══════════════════════════════════════════════════════════════
//  Jira Integration Tool
//  Create/search/transition issues, manage sprints.
//  Requires JIRA_URL, JIRA_EMAIL, JIRA_API_TOKEN.
// ══════════════════════════════════════════════════════════════

@Serializable
data class JiraArgs(
    @property:LLMDescription("Action: 'search', 'get_issue', 'create_issue', 'transition', 'add_comment', 'list_projects'")
    val action: String,
    @property:LLMDescription("JQL query for search, or issue key (e.g. 'PROJ-123') for get/transition.")
    val query: String = "",
    @property:LLMDescription("Jira project key for creating issues (e.g. 'PROJ').")
    val projectKey: String = "",
    @property:LLMDescription("Issue type: 'Bug', 'Task', 'Story', 'Epic'.")
    val issueType: String = "Task",
    @property:LLMDescription("Issue summary/title.")
    val summary: String = "",
    @property:LLMDescription("Issue description.")
    val description: String = "",
    @property:LLMDescription("Priority: 'Highest', 'High', 'Medium', 'Low', 'Lowest'.")
    val priority: String = "Medium",
    @property:LLMDescription("Transition ID or name for transitioning issues (e.g. 'Done', 'In Progress').")
    val transition: String = "",
    @property:LLMDescription("Comment text to add to an issue.")
    val comment: String = "",
    @property:LLMDescription("Assignee email or account ID.")
    val assignee: String = "",
    @property:LLMDescription("Max results for search (default: 20).")
    val maxResults: Int = 20,
)

class JiraTool(
    private val httpClient: HttpClient,
    private val jiraUrl: String,
    private val email: String,
    private val apiToken: String,
) : SimpleTool<JiraArgs>(
        argsType = typeToken<JiraArgs>(),
        name = "jira",
        description = "Interact with Jira: search issues (JQL), create issues, transition status, add comments, list projects.",
    ) {
    private val json = PrometheJson
    private val authHeader: String =
        "Basic " +
            Base64
                .getEncoder()
                .encodeToString("$email:$apiToken".toByteArray())

    override suspend fun execute(args: JiraArgs): String {
        if (jiraUrl.isBlank() || apiToken.isBlank()) {
            return "[ERROR] Jira not configured. Set JIRA_URL, JIRA_EMAIL, JIRA_API_TOKEN."
        }

        return try {
            when (args.action.lowercase()) {
                "search" -> searchIssues(args.query, args.maxResults)
                "get_issue" -> getIssue(args.query)
                "create_issue" -> createIssue(args)
                "transition" -> transitionIssue(args.query, args.transition)
                "add_comment" -> addComment(args.query, args.comment)
                "list_projects" -> listProjects()
                else -> "[ERROR] Unknown Jira action: ${args.action}"
            }
        } catch (e: Exception) {
            "[ERROR] Jira API call failed: ${e.message}"
        }
    }

    private suspend fun jiraGet(path: String): String {
        val response =
            httpClient.get("$jiraUrl/rest/api/3$path") {
                header("Authorization", authHeader)
                header("Accept", "application/json")
                timeout { requestTimeoutMillis = 15_000 }
            }
        return response.bodyAsText()
    }

    private suspend fun jiraPost(
        path: String,
        body: JsonObject,
    ): String {
        val response =
            httpClient.post("$jiraUrl/rest/api/3$path") {
                header("Authorization", authHeader)
                contentType(ContentType.Application.Json)
                timeout { requestTimeoutMillis = 15_000 }
                setBody(body.toString())
            }
        return response.bodyAsText()
    }

    private suspend fun searchIssues(
        jql: String,
        maxResults: Int,
    ): String {
        val query = jql.ifBlank { "assignee = currentUser() AND resolution = Unresolved ORDER BY priority DESC" }
        val raw = jiraGet("/search?jql=${query.replace(" ", "%20")}&maxResults=$maxResults&fields=summary,status,priority,assignee")
        val obj = json.parseToJsonElement(raw).jsonObject
        val issues = obj["issues"]?.jsonArray ?: return "No issues found."

        return issues
            .joinToString("\n") { el ->
                val issue = el.jsonObject
                val key = issue["key"]?.jsonPrimitive?.content ?: "?"
                val fields = issue["fields"]?.jsonObject
                val summary = fields?.get("summary")?.jsonPrimitive?.content ?: ""
                val status =
                    fields
                        ?.get("status")
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content ?: "?"
                val priority =
                    fields
                        ?.get("priority")
                        ?.jsonObject
                        ?.get("name")
                        ?.jsonPrimitive
                        ?.content ?: "?"
                "[$key] ($status) [$priority] $summary"
            }.ifBlank { "No issues found." }
    }

    private suspend fun getIssue(issueKey: String): String {
        if (issueKey.isBlank()) return "[ERROR] Issue key required"
        val raw = jiraGet("/issue/$issueKey")
        val obj = json.parseToJsonElement(raw).jsonObject
        val fields = obj["fields"]?.jsonObject ?: return "Issue not found."

        return buildString {
            appendLine("${obj["key"]?.jsonPrimitive?.content}: ${fields["summary"]?.jsonPrimitive?.content}")
            appendLine("Status: ${fields["status"]?.jsonObject?.get("name")?.jsonPrimitive?.content}")
            appendLine("Priority: ${fields["priority"]?.jsonObject?.get("name")?.jsonPrimitive?.content}")
            appendLine("Type: ${fields["issuetype"]?.jsonObject?.get("name")?.jsonPrimitive?.content}")
            appendLine(
                "Assignee: ${fields["assignee"]
                    ?.jsonObject
                    ?.get("displayName")
                    ?.jsonPrimitive
                    ?.content ?: "Unassigned"}",
            )
            appendLine("Reporter: ${fields["reporter"]?.jsonObject?.get("displayName")?.jsonPrimitive?.content}")
            appendLine("Created: ${fields["created"]?.jsonPrimitive?.content}")
            appendLine("---")
            appendLine(
                fields["description"]
                    ?.jsonObject
                    ?.get("content")
                    ?.toString()
                    ?.take(500) ?: "(no description)",
            )
        }
    }

    private suspend fun createIssue(args: JiraArgs): String {
        if (args.projectKey.isBlank()) return "[ERROR] Project key required"
        val body =
            buildJsonObject {
                putJsonObject("fields") {
                    putJsonObject("project") { put("key", args.projectKey) }
                    put("summary", args.summary)
                    putJsonObject("issuetype") { put("name", args.issueType) }
                    putJsonObject("priority") { put("name", args.priority) }
                    if (args.description.isNotBlank()) {
                        putJsonObject("description") {
                            put("type", "doc")
                            put("version", 1)
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "paragraph")
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "text")
                                            put("text", args.description)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        val raw = jiraPost("/issue", body)
        val obj = json.parseToJsonElement(raw).jsonObject
        val key = obj["key"]?.jsonPrimitive?.content ?: "?"
        return "Issue created: $key — $jiraUrl/browse/$key"
    }

    private suspend fun transitionIssue(
        issueKey: String,
        transition: String,
    ): String {
        if (issueKey.isBlank() || transition.isBlank()) return "[ERROR] Issue key and transition required"

        // First get available transitions
        val transRaw = jiraGet("/issue/$issueKey/transitions")
        val transitions = json.parseToJsonElement(transRaw).jsonObject["transitions"]?.jsonArray ?: return "No transitions available."

        val matched =
            transitions.firstOrNull { el ->
                val name = el.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                name.equals(transition, ignoreCase = true) || el.jsonObject["id"]?.jsonPrimitive?.content == transition
            }
                ?: return "[ERROR] Transition '$transition' not found. Available: ${transitions.joinToString {
                    it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                }}"

        val transId = matched.jsonObject["id"]?.jsonPrimitive?.content ?: return "[ERROR] No transition ID"
        val body =
            buildJsonObject {
                putJsonObject("transition") { put("id", transId) }
            }
        jiraPost("/issue/$issueKey/transitions", body)
        return "Issue $issueKey transitioned to: ${matched.jsonObject["name"]?.jsonPrimitive?.content}"
    }

    private suspend fun addComment(
        issueKey: String,
        comment: String,
    ): String {
        if (issueKey.isBlank() || comment.isBlank()) return "[ERROR] Issue key and comment text required"
        val body =
            buildJsonObject {
                putJsonObject("body") {
                    put("type", "doc")
                    put("version", 1)
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "paragraph")
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", comment)
                                }
                            }
                        }
                    }
                }
            }
        jiraPost("/issue/$issueKey/comment", body)
        return "Comment added to $issueKey"
    }

    private suspend fun listProjects(): String {
        val raw = jiraGet("/project?maxResults=50")
        val projects = json.parseToJsonElement(raw).jsonArray
        return projects
            .joinToString("\n") { el ->
                val obj = el.jsonObject
                "[${obj["key"]?.jsonPrimitive?.content}] ${obj["name"]?.jsonPrimitive?.content}"
            }.ifBlank { "No projects found." }
    }
}
