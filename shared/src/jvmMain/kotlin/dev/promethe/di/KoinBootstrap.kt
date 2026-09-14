package dev.promethe.di

import dev.promethe.core.*
import dev.promethe.core.hooks.HookManager
import dev.promethe.db.PrometheDatabaseApi
import io.ktor.client.*
import okio.Path
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * KoinBootstrap — bridges the existing AgentBootstrap initialization logic
 * with the Koin DI container.
 *
 * Phase 1 strategy (non-breaking):
 * - AgentBootstrap.create() still performs all complex initialization
 * - This class takes the resulting AgentStack and registers everything in Koin
 * - Consumers can then use `by inject()` instead of passing 15+ params
 */
object KoinBootstrap {
    /**
     * Initialize Koin with all Promethe dependencies from an existing AgentStack.
     *
     * Call this once after `AgentBootstrap.create()` returns.
     */
    fun initFromStack(stack: AgentStack): KoinApplication {
        val stackModule = module {
            // ── Core infrastructure ──
            single<AgentConfig> { stack.config }
            single<PrometheDatabaseApi> { stack.database }
            single<HttpClient> { stack.httpClient }
            single<ResourceGovernorRegistry> { stack.resourceGovernors }

            // ── Agent ──
            single<AIAgent> { stack.agent }
            single<KoogLlmAdapter> { stack.llmAdapter }

            // ── Services ──
            single<FeedbackCollector> { stack.feedbackCollector }
            single<AgentOrchestrator> { stack.orchestrator }
            single<McpBridge> { stack.mcpBridge }
            single<McpElicitationBroker> { stack.mcpElicitationBroker }
            single<MemoryLayer> { stack.memoryLayer }
            single<AgentA2ARegistry> { stack.registry }
            single<HookManager> { stack.hookManager }
            single<TaskScheduler> { stack.taskScheduler }
            single<dev.promethe.core.sandbox.SandboxManager> { stack.sandboxManager }
            single<dev.promethe.core.sandbox.SandboxRuntimePolicy> { stack.sandboxRuntimePolicy }
            single<dev.promethe.core.sandbox.SandboxedCommandRunner> { stack.sandboxCommandRunner }
            single<dev.promethe.core.coding.LocalCodingAgentService> { stack.localCodingAgentService }

            // ── Optional services ──
            stack.pluginLoader?.let { pl ->
                single<PluginLoader> { pl }
            }
            stack.skillCurator?.let { sc ->
                single<SkillCurator> { sc }
            }
            stack.approvalGate?.let { ag ->
                single<ToolApprovalGate> { ag }
            }
            stack.skillLoader?.let { sl ->
                single<SkillLoader> { sl }
            }
            stack.skillWriter?.let { sw ->
                single<SkillWriter> { sw }
            }
            stack.actionExecutor?.let { ae ->
                single<ActionExecutor> { ae }
            }

            // ── Named values ──
            stack.skillsDir?.let { sd ->
                single(named("skillsDir")) { sd }
            }

            // ── Delegation tools (factory with runtime param) ──
            factory { (parentSessionId: String) ->
                DelegateTaskTool(get(), parentSessionId)
            }
            single { GetSubtaskResultTool(get()) }
        }

        return startKoin {
            modules(stackModule)
        }
    }
}
