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
//  GitHub Integration Tool
//  Full GitHub API access: issues, PRs, repos, search, actions
//  Requires GITHUB_TOKEN environment variable.
// ══════════════════════════════════════════════════════════════

@Serializable
data class GitHubArgs(
    @property:LLMDescription(
        "GitHub API action: 'list_issues', 'create_issue', 'get_issue', 'list_prs', 'create_pr', 'search_repos', 'search_code', 'list_actions', 'get_repo'",
    )
    val action: String,
    @property:LLMDescription("Repository in 'owner/repo' format. Required for most actions.")
    val repo: String = "",
    @property:LLMDescription("Title for creating issues or PRs.")
    val title: String = "",
    @property:LLMDescription("Body/description for issues or PRs.")
    val body: String = "",
    @property:LLMDescription("Search query for search actions.")
    val query: String = "",
    @property:LLMDescription("Issue or PR number for get/update actions.")
    val number: Int = 0,
    @property:LLMDescription("Labels as comma-separated string.")
    val labels: String = "",
    @property:LLMDescription("Base branch for PRs (default: 'main').")
    val baseBranch: String = "main",
    @property:LLMDescription("Head branch for PRs.")
    val headBranch: String = "",
    @property:LLMDescription("State filter: 'open', 'closed', 'all'.")
    val state: String = "open",
)

class GitHubTool(
    private val httpClient: HttpClient,
    private val staticToken: String = "",
    private val tokenSupplier: suspend () -> String? = { null },
) : SimpleTool<GitHubArgs>(
        argsType = typeToken<GitHubArgs>(),
        name = "github",
        description = "Interact with GitHub: create/list issues and PRs, search repos/code, view actions.",
    ) {
    private val json = PrometheJson
    private val baseUrl = "https://api.github.com"

    override suspend fun execute(args: GitHubArgs): String {
        if (resolveToken().isNullOrBlank()) return "[ERROR] GitHub token not configured (OAuth or GITHUB_TOKEN required)"

        return try {
            when (args.action.lowercase()) {
                "list_issues" -> {
                    listIssues(args.repo, args.state)
                }

                "create_issue" -> {
                    createIssue(args.repo, args.title, args.body, args.labels)
                }

                "get_issue" -> {
                    getIssue(args.repo, args.number)
                }

                "list_prs" -> {
                    listPRs(args.repo, args.state)
                }

                "create_pr" -> {
                    createPR(args.repo, args.title, args.body, args.baseBranch, args.headBranch)
                }

                "search_repos" -> {
                    searchRepos(args.query)
                }

                "search_code" -> {
                    searchCode(args.query, args.repo)
                }

                "list_actions" -> {
                    listActions(args.repo)
                }

                "get_repo" -> {
                    getRepo(args.repo)
                }

                else -> {
                    "[ERROR] Unknown GitHub action: ${args.action}. " +
                        "Use: list_issues, create_issue, get_issue, list_prs, " +
                        "create_pr, search_repos, search_code, list_actions, get_repo"
                }
            }
        } catch (e: Exception) {
            "[ERROR] GitHub API call failed: ${e.message}"
        }
    }

    private suspend fun apiGet(path: String): String {
        val token = requireToken()
        val response =
            httpClient.get("$baseUrl$path") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                timeout { requestTimeoutMillis = 15_000 }
            }
        return response.bodyAsText()
    }

    private suspend fun apiPost(
        path: String,
        body: JsonObject,
    ): String {
        val token = requireToken()
        val response =
            httpClient.post("$baseUrl$path") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                contentType(ContentType.Application.Json)
                timeout { requestTimeoutMillis = 15_000 }
                setBody(body.toString())
            }
        return response.bodyAsText()
    }

    private suspend fun listIssues(
        repo: String,
        state: String,
    ): String {
        val raw = apiGet("/repos/$repo/issues?state=$state&per_page=20")
        val issues = json.parseToJsonElement(raw).jsonArray
        return issues
            .joinToString("\n") { el ->
                val obj = el.jsonObject
                "#${obj["number"]?.jsonPrimitive?.content} [${obj["state"]?.jsonPrimitive?.content}] ${obj["title"]?.jsonPrimitive?.content}"
            }.ifBlank { "No issues found." }
    }

    private suspend fun createIssue(
        repo: String,
        title: String,
        body: String,
        labels: String,
    ): String {
        val payload =
            buildJsonObject {
                put("title", title)
                put("body", body)
                if (labels.isNotBlank()) {
                    putJsonArray("labels") { labels.split(",").map { it.trim() }.forEach { add(it) } }
                }
            }
        val raw = apiPost("/repos/$repo/issues", payload)
        val obj = json.parseToJsonElement(raw).jsonObject
        return "Issue created: #${obj["number"]?.jsonPrimitive?.content} — ${obj["html_url"]?.jsonPrimitive?.content}"
    }

    private suspend fun getIssue(
        repo: String,
        number: Int,
    ): String {
        val raw = apiGet("/repos/$repo/issues/$number")
        val obj = json.parseToJsonElement(raw).jsonObject
        return buildString {
            appendLine("Issue #$number: ${obj["title"]?.jsonPrimitive?.content}")
            appendLine("State: ${obj["state"]?.jsonPrimitive?.content}")
            appendLine("Author: ${obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content}")
            appendLine("Labels: ${obj["labels"]?.jsonArray?.joinToString { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }}")
            appendLine("---")
            appendLine(obj["body"]?.jsonPrimitive?.content ?: "(no body)")
        }
    }

    private suspend fun listPRs(
        repo: String,
        state: String,
    ): String {
        val raw = apiGet("/repos/$repo/pulls?state=$state&per_page=20")
        val prs = json.parseToJsonElement(raw).jsonArray
        return prs
            .joinToString("\n") { el ->
                val obj = el.jsonObject
                val num = obj["number"]?.jsonPrimitive?.content
                val state = obj["state"]?.jsonPrimitive?.content
                val title = obj["title"]?.jsonPrimitive?.content
                val head =
                    obj["head"]
                        ?.jsonObject
                        ?.get("ref")
                        ?.jsonPrimitive
                        ?.content
                val base =
                    obj["base"]
                        ?.jsonObject
                        ?.get("ref")
                        ?.jsonPrimitive
                        ?.content
                "#$num [$state] $title ($head → $base)"
            }.ifBlank { "No pull requests found." }
    }

    private suspend fun createPR(
        repo: String,
        title: String,
        body: String,
        base: String,
        head: String,
    ): String {
        val payload =
            buildJsonObject {
                put("title", title)
                put("body", body)
                put("base", base)
                put("head", head)
            }
        val raw = apiPost("/repos/$repo/pulls", payload)
        val obj = json.parseToJsonElement(raw).jsonObject
        return "PR created: #${obj["number"]?.jsonPrimitive?.content} — ${obj["html_url"]?.jsonPrimitive?.content}"
    }

    private suspend fun searchRepos(query: String): String {
        val raw = apiGet("/search/repositories?q=${query.encodeURLParameter()}&per_page=10")
        val items = json.parseToJsonElement(raw).jsonObject["items"]?.jsonArray ?: return "No repos found."
        return items.joinToString("\n") { el ->
            val obj = el.jsonObject
            "${obj["full_name"]?.jsonPrimitive?.content} ⭐${obj["stargazers_count"]?.jsonPrimitive?.content} — ${obj["description"]?.jsonPrimitive?.content ?: ""}"
        }
    }

    private suspend fun searchCode(
        query: String,
        repo: String,
    ): String {
        val q = if (repo.isNotBlank()) "$query+repo:$repo" else query
        val raw = apiGet("/search/code?q=${q.encodeURLParameter()}&per_page=10")
        val items = json.parseToJsonElement(raw).jsonObject["items"]?.jsonArray ?: return "No code found."
        return items.joinToString("\n") { el ->
            val obj = el.jsonObject
            "${obj["repository"]?.jsonObject?.get("full_name")?.jsonPrimitive?.content}/${obj["path"]?.jsonPrimitive?.content}"
        }
    }

    private suspend fun listActions(repo: String): String {
        val raw = apiGet("/repos/$repo/actions/runs?per_page=10")
        val runs = json.parseToJsonElement(raw).jsonObject["workflow_runs"]?.jsonArray ?: return "No workflow runs."
        return runs.joinToString("\n") { el ->
            val obj = el.jsonObject
            "#${obj["run_number"]?.jsonPrimitive?.content} [${obj["conclusion"]?.jsonPrimitive?.content ?: obj["status"]?.jsonPrimitive?.content}] ${obj["name"]?.jsonPrimitive?.content}"
        }
    }

    private suspend fun getRepo(repo: String): String {
        val raw = apiGet("/repos/$repo")
        val obj = json.parseToJsonElement(raw).jsonObject
        return buildString {
            appendLine("${obj["full_name"]?.jsonPrimitive?.content}")
            appendLine("Description: ${obj["description"]?.jsonPrimitive?.content ?: "none"}")
            appendLine("Stars: ${obj["stargazers_count"]?.jsonPrimitive?.content} | Forks: ${obj["forks_count"]?.jsonPrimitive?.content}")
            appendLine("Language: ${obj["language"]?.jsonPrimitive?.content ?: "unknown"}")
            appendLine("Default branch: ${obj["default_branch"]?.jsonPrimitive?.content}")
            appendLine("URL: ${obj["html_url"]?.jsonPrimitive?.content}")
        }
    }

    private fun String.encodeURLParameter(): String = this.replace(" ", "+")

    private suspend fun resolveToken(): String? = tokenSupplier()?.takeIf { it.isNotBlank() } ?: staticToken.takeIf { it.isNotBlank() }

    private suspend fun requireToken(): String = resolveToken() ?: throw IllegalStateException("GitHub token not configured")
}
