# 📚 Prométhé Documentation Map

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](EXPERIMENTAL_STATUS.md).

> Single entry point for navigating the project documentation.

## Existing documents

| Document | Description |
|---|---|
| [EXPERIMENTAL_STATUS.md](EXPERIMENTAL_STATUS.md) | Personal research sandbox scope, non-production status and support limits (FR/EN) |
| [AUDIT_2026-09-06.md](reports/AUDIT_2026-09-06.md) | Audit du code, dépendances, Koog 1.2.0, roadmap et harnesses, avec preuves et priorités |
| [IMPLEMENTATION_2026-09-06.md](reports/IMPLEMENTATION_2026-09-06.md) | Travaux exécutés après audit, résultats vérifiés et expériences restant ouvertes |
| [NEXT_STEPS_HARNESSES_2026-09-06.md](reports/NEXT_STEPS_HARNESSES_2026-09-06.md) | Comparaison des mécanismes DeepSeek/Hermes, auto-mutation et ajouts restant à réaliser dans Prométhé |
| [HARNESS_ITERATION_2026-09-06.md](reports/HARNESS_ITERATION_2026-09-06.md) | Résultats de l'itération : mutation de session, essais réels, comparaison Cordis/Hermes, budget et limites vérifiées |
| [HARNESS_DECISIONS_2026-09-07.md](reports/HARNESS_DECISIONS_2026-09-07.md) | Comparaison originale/fixe/libre : 18 parcours corrects, abstention du modèle, compromis coût/latence et diagnostics conservés |
| [HARNESS_LANGUAGE_DECISION_2026-09-07.md](reports/HARNESS_LANGUAGE_DECISION_2026-09-07.md) | Analyse architecturale préalable aux essais : JavaScript isolé, Kotlin généré et plan déclaratif borné |
| [HARNESS_KOTLIN_2026-09-07.md](reports/HARNESS_KOTLIN_2026-09-07.md) | Itération suivante : scripting Kotlin exécuté nativement et dans AIAgent ; comparaison de lots, coût de compilation et limites Windows |
| [HARNESS_KOTLIN_CACHE_2026-09-07.md](reports/HARNESS_KOTLIN_CACHE_2026-09-07.md) | Cache Kotlin par session : 27 lots corrects, parcours AIAgent réel, validations, limites et diagnostics de transport |
| [HARNESS_KOTLIN_DECISIONS_2026-09-07.md](reports/HARNESS_KOTLIN_DECISIONS_2026-09-07.md) | Décisions avec cache Kotlin : 27 parcours corrects, neuf abstentions, compromis dépense modèle/temps total et prochaine mesure d'exposition des outils |
| [HARNESS_TOOL_EXPOSURE_2026-09-07.md](reports/HARNESS_TOOL_EXPOSURE_2026-09-07.md) | Exposition conditionnelle LAB : 14 réponses correctes sur 18, contexte initial réduit, échecs conservés et diagnostic d'achèvement prioritaire |
| [HARNESS_RESPONSE_DIAGNOSTICS_2026-09-07.md](reports/HARNESS_RESPONSE_DIAGNOSTICS_2026-09-07.md) | Diagnostics v2 et validation facultative d'achèvement : relecture hors ligne des 18 parcours, 1 005 tests JVM, aucun appel modèle |
| [HARNESS_DIAGNOSTIC_PILOT_2026-09-07.md](reports/HARNESS_DIAGNOSTIC_PILOT_2026-09-07.md) | Pilote réel arrêté à cinq parcours sur six : trois corrects, deux vides avec métadonnées connues, cause inconnue et budget conservé |
| [HARNESS_EMPTY_RESPONSE_PROBE_2026-09-07.md](reports/HARNESS_EMPTY_RESPONSE_PROBE_2026-09-07.md) | Sonde de 15 premiers tours : 12 conformes, défaut sans remède démontré, décodage indépendant et limites causales |
| [HARNESS_KOOG_TOOL_PROTOCOL_2026-09-07.md](reports/HARNESS_KOOG_TOOL_PROTOCOL_2026-09-07.md) | Comparaison Responses réelle : deux parcours réussis sur trois enregistrés, arrêt sur lectures dupliquées, historique structuré à préserver |
| [HARNESS_NATIVE_HISTORY_2026-09-07.md](reports/HARNESS_NATIVE_HISTORY_2026-09-07.md) | Historique structuré chronologique corrigé : six parcours complets et corrects, transport vérifié, limites de contexte et causalité |
| [HARNESS_KOOG_KOTLIN_MUTATION_2026-09-07.md](reports/HARNESS_KOOG_KOTLIN_MUTATION_2026-09-07.md) | Paire réelle témoin/libre : deux tâches réussies, aucune mutation choisie, précontrôle natif et brouillons de skills distingués |
| [HARNESS_KOTLIN_DIRECTED_CONTROL_2026-09-07.md](reports/HARNESS_KOTLIN_DIRECTED_CONTROL_2026-09-07.md) | Source Kotlin générée et cycle natif réussis sur demande ; paire de huit pages sans mutation libre, coûts complets et limites |
| [HARNESS_KOTLIN_AMORTIZATION_2026-09-07.md](reports/HARNESS_KOTLIN_AMORTIZATION_2026-09-07.md) | Diagnostics CI synthétiques : réutilisation Kotlin −80,59 % de coût modèle mais +50,19 % de durée ; exposition conditionnelle sans mutation, recommandation consolidée LAB |
| [HARNESS_KOTLIN_WORKER_LATENCY_2026-09-07.md](reports/HARNESS_KOTLIN_WORKER_LATENCY_2026-09-07.md) | Benchmark local : durée native médiane −3,54 %, 48 pages correctes et isolation vérifiée ; aucun appel modèle ni gain applicatif établi |
| [HARNESS_KOTLIN_RUNTIME_OPTIMIZATION_2026-09-07.md](reports/HARNESS_KOTLIN_RUNTIME_OPTIMIZATION_2026-09-07.md) | Préparation du runtime par session livrée : durée native médiane −26,72 %, 48 pages correctes, empreintes et processus jetables conservés ; zéro appel modèle |
| [HARNESS_ADAPTIVE_LIBRARY_2026-09-07.md](reports/HARNESS_ADAPTIVE_LIBRARY_2026-09-07.md) | Mode adaptatif Kotlin livré en LAB : décision, évaluation obligatoire, versions et invalidation ; 1 041 tests JVM, 12 transformations natives correctes, zéro appel modèle |
| [RESOURCE_AGGREGATE_QUOTAS_2026-09-07.md](reports/RESOURCE_AGGREGATE_QUOTAS_2026-09-07.md) | Quotas agrégés de départs livrés : profil local, fournisseur et outil ; 1 058 tests dont 17 nouveaux, configuration et diagnostics, zéro appel payé |
| [SKILL_EVALUATION_LIFECYCLE_2026-09-08.md](reports/SKILL_EVALUATION_LIFECYCLE_2026-09-08.md) | Cycle livré : évaluations par révision, revue, historique et restauration en quarantaine ; 1 071 tests, réutilisation agent avec fournisseur déterministe, zéro appel payé |
| [DEPENDENCIES_2026-09-06.md](reports/DEPENDENCIES_2026-09-06.md) | Inventaire des dépendances déclarées et verrouillées, versions publiées et sources |

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
| [AGENTIC_ROADMAP.md](AGENTIC_ROADMAP.md) | Faisabilité des propositions agentiques 2026 et roadmap d'implémentation | Code actuel, certification et décisions d'architecture |
| [EVALS.md](EVALS.md) | Evals-as-code, golden sets, adversarial baseline and promotion gates | `api/.../EvalModels.kt`, `evals/` |
| [architecture.mmd](architecture.mmd) | Mermaid diagram of the full architecture | Same |
| [MEMORY.md](MEMORY.md) | 4-tier memory (L0-L3), providers, API | `MemoryLayer.kt`, `memory/*.kt`, `MemoryRoutes.kt` |
| [PROJECTS.md](PROJECTS.md) | Durable project context, workspace, scoped memory and session assignment | `ProjectRoutes.kt`, `AgentExecutionService.kt`, `ProjectsScreen.kt` |
| [RAG.md](RAG.md) | Knowledge base: ingestion, hybrid search, re-ranking | `core/rag/*.kt`, `RagRoutes.kt` |
| [GEPA.md](GEPA.md) | Self-evolution: genetic optimization of prompts and skills | `GepaEngine.kt`, `gepa/*.kt`, `GepaJobManager.kt` |
| [HARNESS_MUTATION.md](HARNESS_MUTATION.md) | Auto-mutation LAB de la présentation des observations : opt-in, validation, activation par session et retour arrière | `HarnessMutation.kt`, `SessionHarness.kt`, `HarnessNodeRunner.kt`, `HarnessStore.kt` |
| [HARNESS_KOTLIN.md](HARNESS_KOTLIN.md) | Scripting Kotlin .kts : compilation et évaluation isolées, cache borné par session validé en LAB, résultats et limites Windows | `harness-kotlin/`, `HarnessKotlinRunner.kt` |
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
| [DEPLOYMENT.md](DEPLOYMENT.md) | Docker, CI/CD, experimental deployment | `Dockerfile`, `docker-compose.yml`, `.github/` |
| [SKILLS.md](SKILLS.md) | Skill format, evaluation cases, owner review, version history and restoration | `SkillLoader.kt`, `SkillEvaluationService.kt`, `SkillGovernanceStore.kt` |
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
| Kotlin | 2.4.10 | `libs.versions.toml` |
| Ktor | 3.5.2 (CIO) | `libs.versions.toml` |
| Exposed | 1.5.0 | `libs.versions.toml` |
| Flyway | 13.5.0 | `libs.versions.toml` |
| Koog SDK | 1.2.0 stable core / 1.2.0-beta integrations | `libs.versions.toml` |
| Compose Multiplatform | 1.12.0 | `libs.versions.toml` |
| SQLite (JDBC) | 3.53.4.0 | `libs.versions.toml` |

## Gradle modules

```
promethe/
├── :shared       ← KMP commonMain + jvmMain (core, tools, memory, DB, LLM)
├── :api           ← Shared data models and interfaces
├── :evals         ← Deterministic eval runner, golden sets and adversarial metrics
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
