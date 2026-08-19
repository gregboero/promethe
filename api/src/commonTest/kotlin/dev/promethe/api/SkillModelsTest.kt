package dev.promethe.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillModelsTest {
    @Test
    fun `skill contract survives serialization`() {
        val skill =
            SkillDto(
                name = "release-check",
                content = "Verify the release.",
                contract =
                    SkillContract(
                        lifecycle = SkillLifecycle.CANDIDATE,
                        triggers = listOf("release"),
                        antiTriggers = listOf("draft only"),
                        requiredTools = listOf("git_status"),
                        evalSuite = listOf("release-smoke"),
                        contentHash = "abc123",
                    ),
            )

        val encoded = Json.encodeToString(SkillDto.serializer(), skill)

        assertEquals(skill, Json.decodeFromString(SkillDto.serializer(), encoded))
    }

    @Test
    fun `skill lifecycle only permits reviewed transitions`() {
        assertTrue(SkillLifecycle.DRAFT.canTransitionTo(SkillLifecycle.QUARANTINED))
        assertTrue(SkillLifecycle.CANDIDATE.canTransitionTo(SkillLifecycle.ACTIVE))
        assertFalse(SkillLifecycle.DRAFT.canTransitionTo(SkillLifecycle.ACTIVE))
        assertFalse(SkillLifecycle.DEPRECATED.canTransitionTo(SkillLifecycle.ACTIVE))
    }
}
