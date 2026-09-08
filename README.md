# Prométhé

> Personal sandbox for experimenting with autonomous AI agents, a Perception–Reasoning–Action loop, genetic prompt optimization (GEPA) and observability.
>
> **Experimental project — not intended for production use.** Shared publicly to test ideas and learn. APIs, behavior and data formats may change; stability, security, compatibility and ongoing support are not guaranteed. “Sandbox” describes the project's purpose, not a guarantee of isolation for agent actions.
>
> **Projet personnel d'expérimentation — non destiné à la production.** Voir le [statut du projet / project status](docs/EXPERIMENTAL_STATUS.md).

Package version: 1.0.0. See [CHANGELOG.md](CHANGELOG.md) for historical release notes, the [September audit](docs/reports/AUDIT_2026-09-06.md) and its [implementation report](docs/reports/IMPLEMENTATION_2026-09-06.md) for verified changes and remaining experiments.

Prométhé is a self-hosted, single-owner instance: each deployment has one owner and is not a
multi-tenant service. The release profiles are:

| Profile | Intended use |
|---|---|
| **STABLE** | Conservative capability tier for routine experiments; does not imply production readiness. |
| **BETA** | Preview features for evaluation; behavior may change. |
| **LAB** | Experimental features for evaluation; behavior and interfaces may change without notice. |

### Experimentation targets

The 1.0.0 package targets the Desktop application on **Windows, macOS and Linux**, and the
**Web (`wasmJs`) client**. Android and iOS targets exist in the source tree but are not supported
release platforms at 1.0.0.

An Android debug APK is now built through `:androidApp:assembleDebug` (AGP 9.1.1, compile SDK 37). This build check does not certify device behavior or change the experimental platform status. `-PenableAndroid=false` excludes Android from server/Web-only builds.

The opt-in [harness mutation experiment](docs/HARNESS_MUTATION.md) adds a session-scoped processor for tool observations, preserving raw results and requiring native isolation. Nine paired cases with a real model returned correct answers in both branches: noisy JSON became smaller, while short CSV/log observations grew with provenance metadata. A model-directed tool cycle also passed on an explicitly requested synthetic task, with the raw result preserved and session state cleared. The [iteration report](docs/reports/HARNESS_ITERATION_2026-09-06.md) records the evidence and limits; no general quality, cost or durable-learning gain is claimed. This remains a personal research experiment, not a production capability.

The [follow-up comparison](docs/reports/HARNESS_DECISIONS_2026-09-07.md) completed 18 correct runs: a fixed processor lowered model cost for large JSON but increased total time, while the model proposed no mutation in six runs where it could choose whether to mutate. Small observations now remain unchanged by default. Spontaneous improvement is still unproven. The [initial language analysis](docs/reports/HARNESS_LANGUAGE_DECISION_2026-09-07.md) records the options considered before the Kotlin scripting experiment below.

The [real Kotlin scripting prototype](docs/HARNESS_KOTLIN.md) has a bounded compilation cache per session, with compilation and evaluation in separate native sandbox processes. The [cache benchmark](docs/reports/HARNESS_KOTLIN_CACHE_2026-09-07.md) reduced Kotlin's median three-batch sequence from 13.083 s to 7.074 s; JavaScript took 2.587 s. The [decision experiment](docs/reports/HARNESS_KOTLIN_DECISIONS_2026-09-07.md) completed 27 correct runs with no freely chosen mutation. The [conditional-exposure experiment](docs/reports/HARNESS_TOOL_EXPOSURE_2026-09-07.md) recorded 14 correct answers in 18 runs, retaining three empty responses and one partial answer. It reduced first-call input tokens by 60%, which does not establish whole-task savings. The [offline diagnostics follow-up](docs/reports/HARNESS_RESPONSE_DIAGNOSTICS_2026-09-07.md) adds response metadata and optional completion checks, with 1,005 JVM tests and no model calls; it does not demonstrate a model-quality gain. The policy remains LAB-only. JavaScript remains the default comparator; spontaneous benefit and skill reuse remain unproven.

The [instrumented pilot](docs/reports/HARNESS_DIAGNOSTIC_PILOT_2026-09-07.md) stopped at its baseline guard after five of six planned runs: three correct completions and two empty responses. Both empty responses had HTTP 200, `finishReason=stop` and recorded output usage; their cause remains unknown. No run was retried and no mutation worker ran during the tasks. Diagnosing compatibility with the minimal text protocol comes before further mutation measurements.

The [minimal response probe](docs/reports/HARNESS_EMPTY_RESPONSE_PROBE_2026-09-07.md) completed 15 first-turn calls: 12 matched expectations, while omitting the reasoning option on the reconstructed prompt produced two `[]` replies and one empty reply. Independent decoding confirmed the empty text was already in that HTTP response; its upstream cause remains unknown. These are not completed tasks, and explicit `none` is not a reliability guarantee. Comparing structured tool calls through the existing Koog adapter with textual JSON is the proposed next experiment.

The authorized [Koog protocol comparison](docs/reports/HARNESS_KOOG_TOOL_PROTOCOL_2026-09-07.md) stopped after three of six planned runs: two succeeded, while the third returned correct values after duplicate page reads and failed the completion contract. Structured function calls were actually used. An identified weakness in how older calls are represented in history is a plausible contributor, not a proven cause. Preserving complete chronological call/result history comes before another comparison and Kotlin mutation on this path.

The [native-history follow-up](docs/reports/HARNESS_NATIVE_HISTORY_2026-09-07.md) preserves chronological typed calls and results. All six retest runs completed correctly, with the full structured history verified in requests. This small before/after result does not prove the sole cause of earlier duplicate reads. History remains run-local, and its active suffix is not compressed. A bounded Kotlin mutation experiment on this path is the proposed next step.

The [optional Kotlin mutation trial](docs/reports/HARNESS_KOOG_KOTLIN_MUTATION_2026-09-07.md) completed both baseline and free-choice runs correctly, but the model proposed no mutation. No Kotlin worker ran during the tasks; native preflight checks were separate. The free run cost more, and one pair establishes no reliable speed gain. The next proposed tests separate an explicitly requested lifecycle check from free choice on a task with measurable opportunity to recover setup cost.

The [directed Kotlin control](docs/reports/HARNESS_KOTLIN_DIRECTED_CONTROL_2026-09-07.md) passed: the real model generated, validated and activated its own Kotlin source and transformed two pages when explicitly asked. The subsequent eight-page baseline/free pair also completed correctly, but the free run chose no mutation and cost 28.5% more. This establishes the requested lifecycle, not spontaneous benefit. Measuring setup-cost recovery and when to expose tools is the next priority.

The [CI diagnostics comparison](docs/reports/HARNESS_KOTLIN_AMORTIZATION_2026-09-07.md) completed all three runs correctly. Explicit reuse of a validated Kotlin script cut conservative model cost by **80.59%**, but increased total time by **50.19%** on this single synthetic comparison. Conditional tool exposure led to no mutation and cost 11.75% more than the baseline. Keep reuse explicit in the LAB; improving staging and worker latency takes priority over enabling automatic mutation by default.

The subsequent [local worker benchmark](docs/reports/HARNESS_KOTLIN_WORKER_LATENCY_2026-09-07.md) reduced median native duration by **3.54%** (16.733 to 16.140 seconds), with all 48 pages correct and isolation checks passing. This modest result combines two startup changes across three pairs; it does not establish faster LLM or application responses. Disposable workers remain, and no model calls or costs were added.

The [completed runtime preparation optimization](docs/reports/HARNESS_KOTLIN_RUNTIME_OPTIMIZATION_2026-09-07.md) reuses verified runtime files per session while keeping every worker disposable. Native median duration fell from **17.236 to 12.631 seconds (−26.72%)** across three local pairs, with all 48 pages correct and integrity, timeout and cleanup checks passing. This LAB feature is enabled by default in the Kotlin runner; no model calls, added model cost or full application latency claim accompany this result.

The opt-in [adaptive Kotlin library](docs/reports/HARNESS_ADAPTIVE_LIBRARY_2026-09-07.md) is implemented and validated in the LAB for `answer-extraction-v1`. It connects adaptation decisions, mandatory distinct evaluation cases and durable source versions with evidence and invalidation to existing application tools. All 1,041 JVM tests and one native test pass, including 12 correct native transformations across creation and reuse tasks. The script was pinned from an earlier experiment; no model calls or costs were added, and no autonomous benefit or overall model-cost saving is claimed.

## Architecture

```
promethe/
├── shared/          # Core KMP — agent engine, memory providers, tools, tracing
├── gateway/         # Ktor CIO server — REST + WebSocket + A2A + Memory API
├── composeApp/      # Compose Multiplatform (Desktop + Web/wasmJs) + unified entry point
├── androidApp/      # Experimental Android host; shared UI stays in composeApp
├── evals/           # Deterministic fixtures and bounded harness experiments
└── api/             # Shared API models (KMP: JVM + wasmJs)
```

| Module | Role |
|---|---|
| **shared/** | Agent engine (PRA loop), memory providers, executable tools, OpenTelemetry tracing |
| **gateway/** | Ktor CIO HTTP/WS server (port 8080). Exposes REST, WebSocket streaming, A2A protocol and the Memory API |
| **composeApp/** | Compose Multiplatform app: Desktop (Windows/macOS/Linux) and Web (wasmJs). Unified entry point with 4 launch modes (Desktop, CLI, Connect, Daemon) |
| **api/** | Kotlinx-Serialization models shared between gateway and composeApp (JVM + wasmJs targets) |

## Memory system

Prométhé embeds a pluggable 4-tier memory system (inspired by TencentDB Agent Memory):

| Level | Tier | Description |
|---|---|---|
| L0 | Conversation | Raw messages (`messages` table in the database) |
| L1 | Atomic | Extracted facts — preferences, constraints, context |
| L2 | Scenario | Scenario blocks aggregated from correlated facts |
| L3 | Persona | Long-term user profile |

### Available providers

| Provider | Backend | Dependencies |
|---|---|---|
| **EmbeddedMemoryProvider** | Embedded SQLite | None (default) |
| **HonchoMemoryProvider** | Honcho REST API | External service |
| **TencentMemoryProvider** | TencentDB Agent Memory REST API | Self-hosted or cloud |
| **FallbackMemoryProvider** | Fallback chain | Tries providers in order |

The provider is configured via the **Setup Screen** (Desktop app) or a CLI flag. No `.env` file changes required.

## Prerequisites

| Tool | Version | Check | Notes |
|---|---|---|---|
| **JDK** | 21+ | `java -version` | Required |
| **Gradle** | 9.5.1 | bundled via `gradlew` | Use the pinned wrapper |
| **Docker** | 24+ | `docker --version` | Optional — execution backend |
| **Git** | 2.x | `git --version` | Optional — GitTools tools |
| **FFmpeg** | 6+ | `ffmpeg -version` | Optional — VideoTools tools |
| **Chrome/Chromium** | 120+ | `chrome --version` | Optional — BrowserTools tools |
| **Nmap** | 7+ | `nmap --version` | Optional — SecurityTools tools |

## Quick start

### 1. Clone

```bash
git clone https://github.com/gregboero/promethe.git
cd promethe
```

### 2. Configuration

> **All configuration is done via the Setup Screen (Desktop app) or CLI flags.**
> No `.env` file to create or edit manually.

On first launch of the Desktop app, the **Setup Screen** appears and guides you through configuring:
- The **LLM provider** (OpenRouter, OpenAI, Anthropic, Google, DeepSeek, Ollama…)
- The **API key**
- The **model** to use
- The **local URL** (if Ollama or a proxy)

The `.env.example` file is kept as a reference for available variables, but **the primary method remains the app**.

### 3. Launch (pick a mode)

#### 🖥️ Desktop mode (recommended for getting started)

Launches the Compose graphical app with the embedded gateway:

```bash
./gradlew composeApp:run
```

The app opens. The Setup Screen appears on first launch. After configuration, click **+** to start a conversation.

#### 💻 CLI mode

```bash
./gradlew composeApp:run --args="--cli"
```

Interactive REPL in the terminal with an embedded gateway. Type your message and press Enter.

#### 🐳 Docker mode (local experimentation)

```bash
# Build the fat JAR first
./gradlew gateway:shadowJar

# Launch the standalone gateway (optional services use separate overlays)
docker compose up --build
```

Available services:
- **Promethe Gateway** → `http://localhost:8080`
- **LiteLLM Proxy** → `http://localhost:4000` only when the `compose.litellm.yaml` overlay is explicitly enabled.

### 4. Verify it works

```bash
# Health check
curl http://localhost:8080/health
# → {"status":"ok"}
```

## Using Ollama (100% local)

Zero API key required. [Install Ollama](https://ollama.ai) then:

```bash
# Download a model
ollama pull llama3.1:8b
```

In the **Setup Screen**, choose the `ollama` provider, the `llama3.1:8b` model and the URL `http://localhost:11434`. Or via CLI:

```bash
./gradlew composeApp:run --args="--cli"
# Then configure via the REPL
```

## User interface

The Desktop/Web app offers the following screens via the navigation bar:

| Screen | Description |
|---|---|
| **Chat** | Conversations with the agent (sessions, real-time streaming) |
| **Projects** | Active workspaces, project instructions, scoped memory and conversation grouping |
| **Agents** | Agent profile management |
| **Monitor** | Agent Monitor — real-time observability + tool approval panel |
| **Stats** | Usage statistics |
| **Memory** | MemoryScreen — memory facts visualization and management |
| **GEPA** | GEPA Dashboard — genetic prompt optimization |
| **Scheduler** | Scheduled cron task management (CRUD) |
| **MCP** | MCP server management (connect/disconnect, tools) |
| **Tools** | Read-only inventory of all available tools |
| **Channels** | Configuration of the 19 messaging channels |
| **Plugins** | Plugin management (installation, activation, status) |
| **Knowledge** | RAG knowledge base — document upload and indexing |
| **Orchestrator** | Multi-agent orchestration — workflows and delegation |
| **Settings** | Full settings: LLM, execution, context, observability, security, GEPA, integrations, personality |

## Context files

Prométhé uses **Markdown files** to customize the agent's behavior. Each file contains commented default values to guide you:

| File | Role | Location |
|---|---|---|
| **SOUL.md** | Agent personality, tone, values and principles | `promethe/SOUL.md` |
| **AGENTS.md** | Agent profile definitions (roles, models, tools) | `promethe/AGENTS.md` |
| **.promethe.md** | Project configuration (goals, tech stack, constraints) | `promethe/.promethe.md` |
| **MEMORY.md** | Persistent facts and user preferences | `profiles/developer/MEMORY.md` |
| **USER.md** | User profile (role, expertise, communication preferences) | `profiles/developer/USER.md` |

> **Tip**: These files are hot-reloaded — edit them while the agent is running and it adapts automatically.

## Advanced configuration

Configuration is primarily done via the **Setup Screen** or **CLI flags**. The `.env.example` file serves only as a reference.

### LLM Provider

| Variable | Description | Default |
|---|---|---|
| `LLM_PROVIDER` | Provider to use | `openrouter` |
| `LLM_MODEL` | Model name | depends on the provider |
| `LLM_TEMPERATURE` | Temperature (0.0–2.0) | `0.2` |
| `LLM_MAX_TOKENS` | Max tokens per response | `4096` |
| `LLM_BASE_URL` | Custom URL (proxy/self-hosted) | — |

### API Keys

| Variable | Provider |
|---|---|
| `OPENROUTER_API_KEY` | [OpenRouter](https://openrouter.ai) |
| `OPENAI_API_KEY` | [OpenAI](https://platform.openai.com) |
| `ANTHROPIC_API_KEY` | [Anthropic](https://console.anthropic.com) |
| `GOOGLE_API_KEY` | [Google AI Studio](https://aistudio.google.com) |
| `DEEPSEEK_API_KEY` | [DeepSeek](https://platform.deepseek.com) |
| `NVIDIA_NIM_API_KEY` | [NVIDIA NIM](https://build.nvidia.com) |
| `LITELLM_API_KEY` | [LiteLLM](https://www.litellm.ai) proxy (also used by `docker compose` service) |

### Server

| Variable | Description | Default |
|---|---|---|
| `PORT` | Gateway HTTP port | `8080` |
| `PROMETHE_DB_URL` | Database JDBC URL | `jdbc:sqlite:~/.promethe/promethe.db` |
| `EXEC_BACKEND` | Code execution backend (`local` uses the native sandbox) | `local` |
| `PROFILE_DIR` | Agent profiles folder | `./profiles/developer` |
| `SKILLS_DIR` | Skills folder | `./skills` |

> **Note**: as of the runtime-data centralization, skills, plugins, profiles and the database
> resolve under `~/.promethe/` by default (see [Data structure](#data-structure)) rather than the
> process working directory.

### Remote gateway (opt-in)

The gateway binds to `127.0.0.1` and Docker publishes only `127.0.0.1:8080` by default. To operate
it remotely, make the network exposure explicit and set `REMOTE_ACCESS_ENABLED=true`, a Base64-encoded
32-byte `PROMETHE_MASTER_KEY`, and an explicit `CORS_ALLOWED_ORIGINS` allow-list. Create the single
remote owner locally through the Desktop setup flow. Remote clients receive short-lived sessions; neither
the Desktop app, CLI, nor Web client persists them.

OAuth additionally requires `OAUTH_ENABLED=true`, `OAUTH_REDIRECT_URI`, and GitHub and/or Google OAuth
client credentials. See [Configuration](docs/CONFIGURATION.md) and [Security](docs/SECURITY.md).

### Supported providers

| Provider | `LLM_PROVIDER` | Example `LLM_MODEL` |
|---|---|---|
| OpenRouter | `openrouter` | Discovered from the account catalog |
| OpenAI | `openai` | `gpt-5.6-terra` |
| Anthropic | `anthropic` | `claude-sonnet-5` |
| Google | `google` | `gemini-3.5-flash` |
| DeepSeek | `deepseek` | `deepseek-v4-flash` |
| NVIDIA NIM | `nvidia` | Discovered from the account catalog |
| Ollama | `ollama` | `llama3.1:8b` |
| LiteLLM | `litellm` | Discovered from `/model/info` or `/v1/models` |

The gateway owns model discovery and caches it for six hours. Clients use
`GET /api/v1/providers` and `GET /api/v1/providers/{id}/models`; provider keys never leave the gateway.

## Data structure

Since the runtime-data centralization, **all runtime data lives under `~/.promethe/`** (see
`PrometheHome` in `shared/src/jvmMain/kotlin/dev/promethe/core/PrometheHome.kt`), independent of the
process working directory:

```
~/.promethe/
├── promethe.db           # SQLite database (default; override with PROMETHE_DB_URL)
├── credentials.json      # Local LLM/integration configuration (never commit)
├── mcp.json              # Legacy MCP input, imported once into encrypted SQLite
├── skills/                 # Dynamically loaded skills
├── plugins/                # Installed plugins
└── profiles/                # Agent profiles
```

The SQLite database is created automatically on first launch at `~/.promethe/promethe.db` (override
via `PROMETHE_DB_URL`). No manual migration required — `AgentBootstrap` also runs a one-shot migration
that copies data from the old relative paths (`./data/promethe.db`,
`./profiles/developer/skills`, `./profiles/developer/plugins`) into `~/.promethe/` if found.

## API Gateway — Routes

The gateway exposes the following endpoints:

| Group | Endpoints | Description |
|---|---|---|
| **Chat (A2A)** | `POST /agents/a2a` (JSON-RPC `message/send`) | Conversation via the A2A protocol |
| **Sessions** | `GET/POST /api/v1/sessions` | Session management and project assignment |
| **Projects** | `CRUD /api/v1/projects` | Durable work contexts and active workspace |
| **Agents** | `CRUD /api/agents` | Agent profiles |
| **Stats** | `GET /api/stats` | Usage statistics |
| **Memory** | `GET/DELETE /api/memory/facts` | Memory management |
| **GEPA** | `POST /api/gepa/optimize` | Genetic optimization |
| **MCP** | `CRUD /api/mcp/servers`, `GET /api/mcp/tools` | MCP servers |
| **Scheduler** | `CRUD /api/scheduler/tasks` | Cron tasks |
| **Channels** | `GET/PUT /api/channels`, `POST /api/channels/{name}/test` | Messaging channels |
| **Config Env** | `GET/PUT/DELETE /api/v1/config/env/{KEY}` | Registered runtime settings and provider credentials |
| **Approval** | `GET /api/v1/approval/pending`, `POST /api/v1/approval/{id}` | Tool approval |
| **A2A** | `POST /agents/a2a`, `GET /.well-known/agent.json` | Agent-to-Agent protocol |
| **Webhooks** | `POST /webhook/{channel}` | Incoming webhooks |
| **Health** | `GET /health` | Health check |

## Messaging channels

Prométhé supports **19 messaging channels**, configurable via the **Setup Screen**, the **ChannelsScreen** or the **Settings Screen**:

| Channel | Environment variables |
|---|---|
| **Telegram** | `TELEGRAM_BOT_TOKEN`, `TELEGRAM_SECRET_TOKEN` |
| **Discord** | `DISCORD_BOT_TOKEN` (`DISCORD_PUBLIC_KEY` is optional for slash commands) |
| **Slack** | `SLACK_BOT_TOKEN`, `SLACK_SIGNING_SECRET` |
| **WhatsApp** | `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN` |
| **Signal** | `SIGNAL_CLI_REST_URL`, `SIGNAL_PHONE_NUMBER` |
| **Matrix** | `MATRIX_HOMESERVER_URL`, `MATRIX_ACCESS_TOKEN` |
| **Email** | `EMAIL_SMTP_HOST`, `EMAIL_USERNAME`, `EMAIL_PASSWORD`, `EMAIL_FROM` |
| **SMS** | `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_PHONE_NUMBER` |
| **Microsoft Teams** | `TEAMS_APP_ID`, `TEAMS_APP_SECRET` |
| **Mattermost** | `MATTERMOST_URL`, `MATTERMOST_TOKEN` |
| **DingTalk** | `DINGTALK_APP_KEY`, `DINGTALK_APP_SECRET` |
| **Feishu/Lark** | `FEISHU_APP_ID`, `FEISHU_APP_SECRET` |
| **WeCom** | `WECOM_CORP_ID`, `WECOM_AGENT_ID`, `WECOM_SECRET` |
| **LINE** | `LINE_CHANNEL_ACCESS_TOKEN` |
| **QQ** | `QQ_APP_ID`, `QQ_TOKEN` |
| **Weixin (WeChat)** | `WEIXIN_APP_ID`, `WEIXIN_APP_SECRET`, `WEIXIN_TOKEN` |
| **BlueBubbles (iMessage)** | `BLUEBUBBLES_URL`, `BLUEBUBBLES_PASSWORD` |
| **Ntfy** | `NTFY_URL`, `NTFY_TOPIC` |
| **Home Assistant** | `HOMEASSISTANT_URL`, `HOMEASSISTANT_TOKEN` |

See [CHANNELS.md](docs/CHANNELS.md) for detailed setup guides per channel.

## External integrations

Configurable in the **Setup Screen** (step 4) or in **Settings > Integrations**:

| Service | Variables |
|---|---|
| **GitHub** | `GITHUB_TOKEN` |
| **Notion** | `NOTION_API_KEY` |
| **Jira** | `JIRA_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN` |
| **Email** | `EMAIL_API_KEY`, `EMAIL_PROVIDER` (resend/sendgrid/mailgun) |
| **Twilio** | `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_PHONE_NUMBER` |

Remote session tokens are never persisted in `credentials.json`. OAuth token payloads and UI-managed MCP
headers/environment values are encrypted in SQLite with `PROMETHE_MASTER_KEY`.

> ⚠️ Never commit `credentials.json` — it is excluded via `.gitignore`.

## Security

Prométhé applies a **secure-by-default** approach:

| Mechanism | Description | Default |
|---|---|---|
| **Approval Mode** | Human-in-the-loop for dangerous tools | `dangerous` — destructive operations always require approval, including when `APPROVAL_MODE=auto` |
| **Execution Backend** | Sandboxing of commands and code | `local` — the native helper applies the selected OS sandbox with network disabled and workspace-bounded access |
| **Development agents** | Local Codex and Claude Code delegation inside the Promethe agent loop | Detected from authenticated native CLIs; every delegation and provider action remains approval-gated |
| **Guardrail Presets** | Blocked command patterns | `dev-safe` — blocks fork bombs, `rm -rf /`, pipe-to-shell, etc. |

`EXEC_BACKEND=local` is not a permissive mode. Process execution remains subject
to the native sandbox and approvals; unsupported backends fail closed.

See the [security policy](https://github.com/gregboero/promethe/security/policy) to report vulnerabilities and the
[security guide](docs/SECURITY.md) for gateway, session, OAuth, MCP, and execution controls.

## Build

Promethe is developed in a private monorepo and exported to the public repository with
[Copybara](docs/REPOSITORY_SYNC.md). Maintainers can preview the exact standalone layout with
`pwsh scripts/export-public-repo.ps1 -Destination <empty-directory> -Ref <tag-or-commit>`.

```bash
# Compile everything
./gradlew build

# Unit, contract, security, and golden evaluation tests
./gradlew api:jvmTest evals:test shared:jvmTest gateway:test composeApp:desktopTest

# Fat JAR (standalone gateway)
./gradlew gateway:shadowJar
# → gateway/build/libs/gateway-all.jar

# Run the fat JAR directly
java -jar gateway/build/libs/gateway-all.jar
```

## Troubleshooting

### "Could not reach the server" in the Desktop app

The gateway isn't running. Open a separate terminal:
```bash
./gradlew gateway:run
```
Then click **Retry** in the app.

### "No LLM API key configured"

Check that the API key was properly entered in the **Setup Screen** on first launch. If needed, go to **Settings** to reconfigure. Alternatively, pass the key as a CLI flag.

### Port 8080 already in use

```bash
# Change the port
PORT=9090 ./gradlew gateway:run
```

### Ollama "connection refused"

Check that Ollama is running: `ollama list`. With Docker Compose, Ollama must be on the host machine (not inside Docker) — the URL is `http://host.docker.internal:11434`.

## License

Promethe is licensed under the [Apache License 2.0](LICENSE). See
[NOTICE](NOTICE) for third-party attribution.
