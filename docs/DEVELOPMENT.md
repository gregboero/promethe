# Development Guide

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](EXPERIMENTAL_STATUS.md).

> Local setup, project structure, conventions, CI/CD, and guides for contributing.

## Prerequisites

| Tool | Version | Check | Notes |
|---|---|---|---|
| **JDK** | 21+ (Corretto recommended) | `java -version` | Required |
| **Gradle** | 8.12+ | Included via `gradlew` | Automatic |
| **Docker** | 24+ | `docker --version` | Optional (execution backend) |
| **Android SDK** | API 34+ | Via Android Studio | Optional (Android build) |
| **Xcode** | 15+ | macOS only | Optional (iOS build) |

## Project structure

```
promethe/
├── shared/          # Core KMP — agent engine, tools, memory, tracing
│   ├── commonMain/  #   Shared code (all platforms)
│   ├── jvmMain/     #   JVM implementations (MCP, transport)
│   └── jvmTest/     #   Unit tests
├── gateway/         # Ktor CIO server — REST + WebSocket + A2A
│   └── jvmMain/     #   Routes, channels, security, MCP export
├── composeApp/      # Compose Multiplatform UI + unified entry point
│   ├── commonMain/  #   Shared screens (Desktop + Web + Android + iOS)
│   ├── desktopMain/ #   Desktop entry point (Main.kt — 4 launch modes)
│   ├── androidMain/ #   Android entry point
│   ├── wasmJsMain/  #   Web entry point
│   └── iosMain/     #   Swift/Kotlin bridge
├── api/             # Shared API models (JVM + wasmJs)
├── evals/           # Deterministic eval runner, golden sets, adversarial metrics
└── docs/            # Documentation
```

## Build commands

```bash
# Compile the whole project
./gradlew build

# Unit, contract, security, and golden evaluation tests
./gradlew api:jvmTest evals:test shared:jvmTest gateway:test

# Run the gateway
./gradlew gateway:run

# Run the Desktop app
./gradlew composeApp:run

# Run the Web app (wasmJs)
./gradlew composeApp:wasmJsBrowserDevelopmentRun

# Fat JAR (standalone gateway)
./gradlew gateway:shadowJar
# → gateway/build/libs/gateway-all.jar

# Package Desktop (native installer)
./gradlew composeApp:packageDistributionForCurrentOS

# Lint (ktlint)
./gradlew ktlintCheck        # Check
./gradlew ktlintFormat       # Auto-fix

# E2E tests (PowerShell)
./test-e2e.ps1
```

## Code conventions

### ktlint

The project uses **ktlint 1.8.0** via the Gradle plugin `jlleitschuh.gradle.ktlint` v12.1.2.

Configuration in `.editorconfig`:
```ini
[*.{kt,kts}]
ktlint_standard_no-wildcard-imports = disabled
ktlint_standard_filename = disabled
ktlint_standard_no-consecutive-comments = disabled
ktlint_standard_function-naming = disabled        # Compose @Composable = PascalCase
ktlint_standard_chain-method-continuation = disabled
ktlint_standard_multiline-expression-wrapping = disabled
ktlint_standard_import-ordering = disabled
```

### Kotlin/Compose conventions

- **Composables**: PascalCase (`SettingsScreen`, `SectionHeader`)
- **State**: `remember { mutableStateOf(...) }` with `by` delegate
- **API client**: Always via `PrometheClient` (never direct HTTP)
- **Navigation**: Register in `App.kt` (navItems + when branch)

## CI Pipeline

The `.github/workflows/ci.yml` pipeline compiles the gateway, API, shared core, evals, Desktop and Wasm clients. It runs unit, contract, security, golden eval, Desktop, documentation and lint checks, plus dependency, CodeQL, Trivy and Qodana analysis. Main additionally validates Docker images and desktop packages without publishing them.

## Practical guides

### Adding a new UI screen

1. Create `composeApp/src/commonMain/.../screens/MyNewScreen.kt`
2. Follow the pattern `@Composable fun MyNewScreen(client: PrometheClient)`
3. Add the entry in `navItems` in `App.kt`:
   ```kotlin
   Triple("My Screen", Icons.Default.Star, "myscreen")
   ```
4. Add the `when` branch in the `Scaffold`'s `content`:
   ```kotlin
   "myscreen" -> MyNewScreen(client)
   ```

### Adding a new API route

1. Create `gateway/src/jvmMain/.../MyRoute.kt`
2. Define an extension `fun Route.myRoute() { ... }`
3. Mount it in `OmnichannelGateway.kt` via the `installRoutes()` method:
   ```kotlin
   // In OmnichannelGateway.installRoutes()
   routing {
       route("/api") {
           myRoute()
       }
   }
   ```

### Adding a new agent tool

1. Create a class that extends `ToolBase<Input, Output>`
2. Add an explicit entry to `ToolContractRegistry`. Declare every read and effectful operation, its
   approval policy, idempotency, owner restriction and egress. Unknown names are deliberately
   rejected by the startup coverage audit.
3. Register it in `BuiltinTools.kt`:
   ```kotlin
   ToolRegistry.register(MyTool())
   ```
4. Add a negative policy test for every operation that can write, execute, delete, change
   configuration, control a device or cause an external effect.
5. Run `./gradlew shared:jvmTest`. `ToolContractCoverageArchitectureTest` inventories literal
   `SimpleTool` declarations, validates dynamic MCP/ACP families and fails when an effect lacks
   mandatory approval.

The gateway validates the live `ToolRegistry` again during startup. MCP and ACP tools use explicit
fail-closed family contracts; only MCP tools explicitly certified by the server owner can be reduced
to read-only risk.

## Running tests

The suite is split across four backend modules plus the Desktop client:

```bash
./gradlew shared:jvmTest    # Agent engine, memory providers, tools, tracing (shared/src/jvmTest, shared/src/commonTest)
./gradlew gateway:test      # Route/integration tests — routes, rate limiter, MCP, scheduler (gateway/src/test)
./gradlew api:jvmTest       # kotlinx-serialization round-trip tests (api/src/commonTest)
./gradlew evals:test         # Golden sets, eval runner and adversarial baseline (evals/src/test)
./gradlew composeApp:desktopTest

# E2E tests (requires the gateway to be running)
./test-e2e.ps1

# Coverage report (Kover)
./gradlew koverHtmlReport

# Lint
./gradlew ktlintCheck        # Check
./gradlew ktlintFormat       # Auto-fix
```

`gateway` is a plain JVM module (task name `test`); `shared` and `api` are Kotlin Multiplatform
modules, so their JVM test task is `jvmTest`. `shared` also has `commonTest` sources (memory/GEPA
tests) that run as part of `shared:jvmTest` on the JVM target.

Run `promethe/docs/verify-docs.ps1` from `promethe/` or `promethe/docs/` to check documentation
against the code — it flags stale version numbers, phantom routes, a wrong channel count, or
references to the deleted `cli/` Gradle module.

## Hot-reload

Context files (`SOUL.md`, `AGENTS.md`, `.promethe.md`, `profiles/developer/MEMORY.md`,
`profiles/developer/USER.md`) are Markdown loaded by `ContextFileLoader`
(`shared/src/commonMain/kotlin/dev/promethe/core/ContextFileLoader.kt`) and injected into the
system prompt. On the JVM, `HotReloadWatcher`
(`shared/src/jvmMain/kotlin/dev/promethe/core/HotReloadWatcher.kt`) watches the working directory
(plus its `profiles`, `plugins`, and `.promethe` subdirectories) via `java.nio.file.WatchService`
and, after a 2-second debounce:

- reloads context files and re-injects them when any file in `ContextFileLoader.CONTEXT_FILES` changes,
- re-parses `.env` and notifies listeners when it changes,
- notifies listeners of individual file changes under `plugins/`.

Changed files over 100 KB are ignored. No process restart is required for any of the above.
