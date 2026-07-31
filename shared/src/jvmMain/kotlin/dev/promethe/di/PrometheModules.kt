package dev.promethe.di

import dev.promethe.core.*
import dev.promethe.core.hooks.HookManager
import dev.promethe.db.PrometheDatabaseApi
import io.ktor.client.*
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Koin DI modules for the Promethe agent platform (Phase 2 — full DI).
 *
 * These modules define the dependency graph declaratively.
 * Currently used as reference; Phase 1 uses KoinBootstrap.initFromStack() instead.
 *
 * Architecture:
 * - [coreModule]         → AgentConfig, DB, HttpClient, base services
 * - [agentModule]        → AI agent, LLM adapter, tools, memory, skills
 * - [orchestratorModule] → Sub-agent orchestration, A2A registry, delegation
 */

// ═══════════════════════════════════════════════════════════════
// Core Module — config, database, HTTP, base infrastructure
// ═══════════════════════════════════════════════════════════════
val coreModule = module {
    // AgentConfig — built from env/credentials, registered externally
    // single { AgentConfig(...) } → registered by AgentBootstrap

    // Database — created by DatabaseFactory or injected
    // single<PrometheDatabaseApi> { ... } → registered by AgentBootstrap

    // HttpClient — created with plugins
    // single { HttpClient { ... } } → registered by AgentBootstrap
}

// ═══════════════════════════════════════════════════════════════
// Agent Module — AI agent + all its dependencies
// ═══════════════════════════════════════════════════════════════
val agentModule = module {
    // ActionExecutor — needs HttpClient + config + hookManager
    single { ActionExecutor(get(), get(), hookManager = get()) }

    // Skills
    single { SkillLoader(getFileSystem(), get(named("skillsDir"))) }
    single { SkillWriter(getFileSystem(), get(named("skillsDir"))) }
    single { SkillCurator(get(), get(), get(), get()) }

    // Evaluators & strategies
    single { TrajectoryEvaluator(get(), get()) }
    single { ResilienceStrategy(get()) }
    single { ContextCompressor(get(), get()) }
    single { FeedbackCollector(get()) }
    single { RewardSignal() }
    single { HookManager() }
    single { ProfileManager() }

    // AIAgent — the main agent (many optional params)
    single {
        AIAgent(
            config = get(),
            database = get(),
            llmAdapter = get(),
            profileManager = get(),
            actionExecutor = get(),
            skillLoader = get(),
            trajectoryEvaluator = get(),
            skillWriter = get(),
            memoryLayer = getOrNull(),
            rewardSignal = get(),
            resilience = getOrNull(),
            hookManager = getOrNull(),
            contextCompressor = getOrNull(),
            dryRun = false,
        )
    }
}

// ═══════════════════════════════════════════════════════════════
// Orchestrator Module — sub-agents, A2A, delegation
// ═══════════════════════════════════════════════════════════════
val orchestratorModule = module {
    single { AgentA2ARegistry() }

    single {
        AgentOrchestrator(
            llmAdapter = get(),
            database = get(),
            config = get(),
            profileManager = get(),
            actionExecutor = get(),
            skillLoader = get(),
            skillWriter = get(),
            trajectoryEvaluator = get(),
            registry = getOrNull(),
        )
    }

    // DelegateTaskTool — needs a runtime parentSessionId
    factory { (parentSessionId: String) ->
        DelegateTaskTool(get(), parentSessionId)
    }

    single { GetSubtaskResultTool(get()) }
}
