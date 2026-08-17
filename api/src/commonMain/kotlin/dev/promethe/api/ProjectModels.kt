package dev.promethe.api

import kotlinx.serialization.Serializable

const val ACTIVE_PROJECT_SETTING_KEY = "active_project_id"

@Serializable
data class ProjectInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val instructions: String = "",
    val workspacePath: String,
    val memoryNamespace: String,
    val active: Boolean = false,
    val archived: Boolean = false,
    val sessionCount: Int = 0,
    val memoryCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ProjectListResponse(
    val projects: List<ProjectInfo>,
    val activeProjectId: String? = null,
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val description: String = "",
    val instructions: String = "",
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val instructions: String? = null,
    val archived: Boolean? = null,
)

@Serializable
data class AssignSessionProjectRequest(
    val projectId: String? = null,
)
