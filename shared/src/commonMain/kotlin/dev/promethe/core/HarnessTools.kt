package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken

/** Shared by application bootstrap and integration tests; removes stale adaptive exposure. */
suspend fun registerHarnessTools(control: HarnessControl) {
    ToolRegistry.register(HarnessInspectTool(control))
    ToolRegistry.register(HarnessProposeTool(control))
    ToolRegistry.register(HarnessEvaluateTool(control))
    ToolRegistry.register(HarnessActivateTool(control))
    ToolRegistry.register(HarnessRollbackTool(control))
    ToolRegistry.register(HarnessDisableTool(control))
    if (control is AdaptiveHarnessControl) ToolRegistry.register(HarnessAdaptTool(control)) else ToolRegistry.unregister("harness_adapt")
}

class HarnessInspectTool(
    private val control: HarnessControl,
) : SimpleTool<HarnessArguments>(
        argsType = typeToken<HarnessArguments>(),
        name = "harness_inspect",
        description = "Read current session LAB processor state and validation examples. Takes no arguments and does not create a revision. Then call harness_propose to submit code.",
    ) {
    override suspend fun execute(args: HarnessArguments): String {
        val request = currentToolInvocation() ?: return "[BLOCKED] Missing invocation context"
        return control.command(request, "inspect", args)
    }
}

class HarnessProposeTool(
    private val control: HarnessControl,
) : SimpleTool<HarnessArguments>(
        argsType = typeToken<HarnessArguments>(),
        name = "harness_propose",
        description = "Create a candidate revision. Set source to ${control.sourceDescription}; set toolName to read_file or json_query. Set baseRevision to current active ID, or null initially. Returns the new revision id; pass it to harness_evaluate. Does not activate code.",
    ) {
    override suspend fun execute(args: HarnessArguments): String {
        val request = currentToolInvocation() ?: return "[BLOCKED] Missing invocation context"
        return control.command(request, "propose", args)
    }
}

class HarnessEvaluateTool(
    private val control: HarnessControl,
) : SimpleTool<HarnessArguments>(
        argsType = typeToken<HarnessArguments>(),
        name = "harness_evaluate",
        description = "Validate a previously proposed candidate in the native sandbox. Set revision to its exact returned id. If passed=true, call harness_activate with that revision. Otherwise propose corrected source.",
    ) {
    override suspend fun execute(args: HarnessArguments): String {
        val request = currentToolInvocation() ?: return "[BLOCKED] Missing invocation context"
        return control.command(request, "evaluate", args)
    }
}

class HarnessActivateTool(
    private val control: HarnessControl,
) : SimpleTool<HarnessArguments>(
        argsType = typeToken<HarnessArguments>(),
        name = "harness_activate",
        description = "Schedule a validated revision for the next agent step. Set revision to the exact candidate id that passed evaluation. Then call its target tool to use the transformed observation. Raw status and provenance remain unchanged.",
    ) {
    override suspend fun execute(args: HarnessArguments): String {
        val request = currentToolInvocation() ?: return "[BLOCKED] Missing invocation context"
        return control.command(request, "activate", args)
    }
}

class HarnessRollbackTool(
    private val control: HarnessControl,
) : SimpleTool<HarnessArguments>(
        argsType = typeToken<HarnessArguments>(),
        name = "harness_rollback",
        description = "Schedule the previous session revision for the next step. Takes no arguments. Fails if no previous revision exists; use harness_disable to return to original observations.",
    ) {
    override suspend fun execute(args: HarnessArguments): String {
        val request = currentToolInvocation() ?: return "[BLOCKED] Missing invocation context"
        return control.command(request, "rollback", args)
    }
}

class HarnessDisableTool(
    private val control: HarnessControl,
) : SimpleTool<HarnessArguments>(
        argsType = typeToken<HarnessArguments>(),
        name = "harness_disable",
        description = "Disable session observation processing at the next step and return to original tool observations. Takes no arguments.",
    ) {
    override suspend fun execute(args: HarnessArguments): String {
        val request = currentToolInvocation() ?: return "[BLOCKED] Missing invocation context"
        return control.command(request, "disable", args)
    }
}
