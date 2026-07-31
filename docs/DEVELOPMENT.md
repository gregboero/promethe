# Development Guide

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
└── docs/            # Documentation
```

## Build commands

```bash
# Compile the whole project
./gradlew build

# Unit tests (shared)
./gradlew shared:jvmTest

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

The `.github/workflows/ci.yml` file defines 4 jobs:

| Job | Trigger | Description |
|---|---|---|
| **test** | push/PR | `shared:jvmTest` |
| **build** | after test | 5 targets: Desktop, Android, WasmJS, Gateway, iOS |
| **docker** | main only | Build + push `ghcr.io/.../promethe-gateway:latest` |
| **desktop-package** | main only | Linux/macOS/Windows installers |

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
2. Register it in `BuiltinTools.kt`:
   ```kotlin
   ToolRegistry.register(MyTool())
   ```
3. The tool will automatically be available in the system prompt

## Running tests

The suite is split across three modules (~404 tests total):

```bash
./gradlew shared:jvmTest    # Agent engine, memory providers, tools, tracing (shared/src/jvmTest, shared/src/commonTest)
./gradlew gateway:test      # Route/integration tests — routes, rate limiter, MCP, scheduler (gateway/src/test)
./gradlew api:jvmTest       # kotlinx-serialization round-trip tests (api/src/commonTest)

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
