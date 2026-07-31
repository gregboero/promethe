# 📚 Prométhé Documentation Map

> Single entry point for navigating the project documentation.

## Existing documents

### Release acceptance

| Document | Description |
|---|---|
| [MANUAL_ACCEPTANCE_V1.md](release/MANUAL_ACCEPTANCE_V1.md) | Executable M01-M24 v1.0 manual acceptance checklist, evidence, maturity rules, and sign-off |
| [CAPABILITY_CERTIFICATION.md](release/CAPABILITY_CERTIFICATION.md) | Public capability maturity definitions and per-capability certification evidence |
| [SANDBOX_MANUAL_TESTS.md](release/SANDBOX_MANUAL_TESTS.md) | Manual OS, escape, resource-limit, setup and fail-closed acceptance tests |

### 🏗️ Architecture & Design

| Document | Description | Source of truth |
|---|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Modules, bootstrap, request flow, DI, persistence | `AgentBootstrap.kt`, `OmnichannelGateway.kt`, `settings.gradle.kts` |
| [architecture.mmd](architecture.mmd) | Mermaid diagram of the full architecture | Same |
| [MEMORY.md](MEMORY.md) | 4-tier memory (L0-L3), providers, API | `MemoryLayer.kt`, `memory/*.kt`, `MemoryRoutes.kt` |
| [RAG.md](RAG.md) | Knowledge base: ingestion, hybrid search, re-ranking | `core/rag/*.kt`, `RagRoutes.kt` |
| [GEPA.md](GEPA.md) | Self-evolution: genetic optimization of prompts and skills | `GepaEngine.kt`, `gepa/*.kt`, `GepaJobManager.kt` |
| [RESILIENCE.md](RESILIENCE.md) | Context compression, retry, auto-healing, LLM fallback | `ContextCompressor.kt`, `ResilienceStrategy.kt`, `KoogLlmAdapter.kt` |
| [OBSERVABILITY.md](OBSERVABILITY.md) | Tracing, persisted LLM stats, logging/metrics hooks | `TracySetup.kt`, `hooks/BuiltinHooks.kt`, `StatusRoutes.kt` |

### 🔌 API & Protocols

| Document | Description | Source of truth |
|---|---|---|
| [API.md](API.md) | REST/WS/A2A reference — all endpoints | `OmnichannelGateway.kt` + `*Routes.kt` files |
| [AGENTS.md](AGENTS.md) | Agent profiles, multi-agent orchestration | `AgentOrchestrator.kt`, `ProfileSeeder.kt` |
| [JS_BRIDGE.md](JS_BRIDGE.md) | JavaScript bridge for the WasmJS UI | `composeApp/` |
| [SECURITY.md](SECURITY.md) | Local key and owner-session auth, CORS, approval gate, secrets | `AuthMiddleware.kt`, `OwnerAuthService.kt`, `SecretCipher.kt` |
| [SANDBOX.md](SANDBOX.md) | Native sandbox architecture, profiles, helper protocol, setup and limitations | `sandbox-native/`, `core/sandbox/`, `AgentBootstrap.kt` |
| [A2A.md](A2A.md) | A2A/ACP protocols: registry, loopback, agent card, routing | `AgentA2ARegistry.kt`, `AcpRoutes.kt`, `PrometheA2AExecutor.kt` |
| [A2UI.md](A2UI.md) | Agent-to-UI protocol: UiNode schema, renderers, bindings | `A2UIModels.kt`, `RenderUITool.kt`, `composeApp/.../a2ui/` |
| [MCP.md](MCP.md) | MCP client + server: transports, discovery, management | `McpBridge.kt`, `McpManagementRoutes.kt`, `gateway/mcp/` |

### ⚙️ Configuration & Deployment

| Document | Description | Source of truth |
|---|---|---|
| [CONFIGURATION.md](CONFIGURATION.md) | Environment variables, LLM providers, tools | `AgentConfig` (AgentState.kt), `ConfigProvider.kt` |
| [CHANNELS.md](CHANNELS.md) | Guide to the 19 messaging channels | `shared/.../channels/*.kt` |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Docker, CI/CD, production | `Dockerfile`, `docker-compose.yml`, `.github/` |
| [SKILLS.md](SKILLS.md) | Skill system — format, synthesis, curation | `SkillLoader.kt`, `SkillWriter.kt`, `SkillCurator.kt` |
| [PLUGINS.md](PLUGINS.md) | Plugin system — structure, hooks, hot-reload | `PluginLoader.kt`, `HotReloadWatcher.kt` |
| [PROVIDERS.md](PROVIDERS.md) | The 28 AI providers (image, vision, embeddings, video, voice) | `ProviderRegistry.kt`, `CapabilityRouter.kt`, `VoiceProviderRegistry.kt` |
| [INTEGRATIONS.md](INTEGRATIONS.md) | External integrations: GitHub, Email, Notion, Jira, Twilio, HA, browser | `IntegrationRegistrar.kt` |
| [SCHEDULER.md](SCHEDULER.md) | Scheduled tasks: CRUD, cron, execution | `TaskScheduler.kt`, `TaskExecutor.kt`, `SchedulerRoutes.kt` |

### 🧑‍💻 Development

| Document | Description | Source of truth |
|---|---|---|
| [DEVELOPMENT.md](DEVELOPMENT.md) | Dev setup, build, tests | `build.gradle.kts`, `gradlew` |
| [REPOSITORY_SYNC.md](REPOSITORY_SYNC.md) | Public repository export and contribution synchronization | `.copybara/`, `scripts/verify-public-export.ps1` |
| [BROWSER_TESTING.md](BROWSER_TESTING.md) | E2E tests with Playwright | `test-e2e.ps1` |
| [ACCESSIBILITY.md](ACCESSIBILITY.md) | Accessibility audit and compliance | `composeApp/` |
| [UI.md](UI.md) | Guide to the 18 desktop app screens | `composeApp/.../screens/*.kt`, `App.kt` |

### 📄 Project root

| Document | Description |
|---|---|
| [README.md](../README.md) | Overview, quick start, tech stack |
| [.promethe.md](../.promethe.md) | Agent context (stack, conventions, architecture) |
| [CONTEXT.md](../CONTEXT.md) | Business context of the project |
| [SOUL.md](../SOUL.md) | Agent personality and directives |
| [AGENTS.md (root)](../AGENTS.md) | Rules for AI agents working on the project |

## Tech stack (verified)

| Component | Version | Evidence |
|---|---|---|
| Kotlin | 2.4.0 | `libs.versions.toml` |
| Ktor | 3.5.0 (CIO) | `libs.versions.toml` |
| Exposed | 1.3.0 | `libs.versions.toml` |
| Flyway | 12.8.1 | `libs.versions.toml` |
| Koog SDK | 1.0.0-beta | `libs.versions.toml` |
| Compose Multiplatform | 1.11.1 | `libs.versions.toml` |
| SQLite (JDBC) | 3.53.2.0 | `libs.versions.toml` |

## Gradle modules

```
promethe/
├── :shared       ← KMP commonMain + jvmMain (core, tools, memory, DB, LLM)
├── :api           ← Shared data models and interfaces
├── :gateway       ← Ktor server (OmnichannelGateway, routes, A2A)
└── :composeApp    ← Compose Multiplatform UI + unified entry point (Main.kt)
```

## Launch modes

```
promethe                  → Desktop GUI (default)
promethe --cli            → Embedded gateway + terminal REPL
promethe --connect <url>  → Remote CLI client
promethe --daemon         → Headless server (no UI)
promethe --mcp-stdio      → MCP server on stdin/stdout (gateway module)
```
