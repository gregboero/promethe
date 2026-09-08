package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

@Serializable
data class HarnessAdaptationArguments(
    val operation: String = "decide",
    val contractId: String = "",
    val toolName: String = "read_file",
    val remainingObservations: Int = 0,
    val maxAddedLatencyMillis: Long = 0,
    val entryId: String = "",
)

interface AdaptiveHarnessControl : HarnessControl {
    suspend fun adapt(
        request: ToolExecutionRequest,
        args: HarnessAdaptationArguments,
    ): String
}

class HarnessAdaptTool(
    private val control: AdaptiveHarnessControl,
) : SimpleTool<HarnessAdaptationArguments>(
        argsType = typeToken<HarnessAdaptationArguments>(),
        name = "harness_adapt",
        description = "Experimental Kotlin adaptation. operation=catalog lists evaluated versions; decide selects original/create/reuse from observed sizes and your remaining-work estimate. Set contractId=answer-extraction-v1 ONLY when the task needs solely the top-level answer value (JSON), answer column (simple two-line CSV), or DATA answer=value; other text stays unchanged. Set toolName and remainingObservations (0..100), and an acceptable maxAddedLatencyMillis (0..300000; zero keeps original). Reuse is revalidated and scheduled at the next step. CREATE only recommends proposing code, never generates it. invalidate disables a catalog entry; restore re-evaluates that exact entry. inspect the harness for evidence and the decision trace. Estimates are not measured provider savings.",
    ) {
    override suspend fun execute(args: HarnessAdaptationArguments): String {
        val request = currentToolInvocation() ?: return "[BLOCKED] Missing invocation context"
        return control.adapt(request, args)
    }
}
