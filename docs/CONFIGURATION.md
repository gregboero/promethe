# Configuration Reference

> All Prométhé configuration variables, organized by domain.

## Configuration methods

| Method | Priority | When to use |
|---|---|---|
| **Setup Screen** (Desktop app) | 1st | First launch |
| **Settings Screen** (Desktop app) | 2nd | Change config while in use |
| **REST API** (`/api/v1/config/env`) | 3rd | Automation, scripts |
| **`.env` file** | 4th | Docker, CI/CD |
| **System environment variables** | 5th | Production |

---

## 1. LLM Provider

| Variable | Type | Default | Description |
|---|---|---|---|
| `LLM_PROVIDER` | string | `openrouter` | LLM provider: `openrouter`, `openai`, `anthropic`, `google`, `deepseek`, `nvidia`, `ollama`, `litellm` |
| `LLM_MODEL` | string | — | Model name (provider-specific) |
| `LLM_TEMPERATURE` | float | `0.2` | Sampling temperature (0.0–2.0) |
| `LLM_MAX_TOKENS` | int | `4096` | Max tokens per response |
| `LLM_BASE_URL` | string | — | Custom URL (proxy, self-hosted, Ollama) |

### API keys per provider

| Variable | Provider | Format |
|---|---|---|
| `OPENROUTER_API_KEY` | OpenRouter | `sk-or-...` |
| `OPENAI_API_KEY` | OpenAI | `sk-...` |
| `ANTHROPIC_API_KEY` | Anthropic | `sk-ant-...` |
| `GOOGLE_API_KEY` | Google AI Studio | `AIza...` |
| `DEEPSEEK_API_KEY` | DeepSeek | `sk-...` |
| `NVIDIA_NIM_API_KEY` | NVIDIA NIM | `nvapi-...` |
| `LITELLM_API_KEY` | LiteLLM proxy | *(depends on proxy config, e.g. `sk-promethe-dev`)* |
| `MOONSHOT_API_KEY` | Kimi | `sk-...` |
| `XAI_API_KEY` | xAI (Grok) | `xai-...` |


### Provider details

| Provider | `LLM_PROVIDER` | Client | Default model (`MultiModelRouter`) | Notes |
|---|---|---|---|---|
| OpenAI | `openai` | Native `OpenAILLMClient`; GPT-5.6 uses the Responses API | `gpt-5.6-terra` candidate | Only account-returned models are selectable |
| Anthropic | `anthropic` | Native `AnthropicLLMClient` with pass-through model IDs | `claude-sonnet-5` candidate | Current Claude models omit unsupported sampling parameters |
| Google | `google` | Native `GoogleLLMClient` | `gemini-3.5-flash` candidate | Every request must include at least one content message |
| DeepSeek | `deepseek` | Native `DeepSeekLLMClient` | `deepseek-v4-flash` candidate | Never routed through OpenAI |
| NVIDIA NIM | `nvidia` | `OpenAILLMClient` with `https://integrate.api.nvidia.com` | *(none)* | Models are discovered per account |
| LiteLLM | `litellm` | `OpenAILLMClient` with the configured proxy URL | *(none)* | Aliases are opaque; discovery uses `/model/info`, then `/v1/models` |
| OpenRouter | `openrouter` | Native `OpenRouterLLMClient` | *(none)* | Capabilities come from `supported_parameters` and modalities |
| Ollama | `ollama` | `simpleOllamaAIExecutor` | `llama3.1` | URL from `credentials.json`'s `ollamaUrl` (key `ollama_url`), defaulting to `http://localhost:11434` |
| Kimi | `kimi` | Koog `OpenAILLMClient` with `https://api.moonshot.ai` | `kimi-k3` | OpenAI-compatible Chat Completions; `AUTO` uses K3's default maximum reasoning |
| xAI | `xai` | Koog `OpenAILLMClient` with `https://api.x.ai` | `grok-4.5` | Responses by default; Chat Completions is selectable with `XAI_API_MODE` |


---

## 2. Server

| Variable | Type | Default | Description |
|---|---|---|---|
| `PORT` | int | `8080` | Gateway HTTP port |
| `PROMETHE_DB_URL` | string | `jdbc:sqlite:~/.promethe/promethe.db` | Database JDBC URL. Resolution order: explicit value passed to `DatabaseFactory.create()` → this env var → default under [`~/.promethe/`](#19-promethe-home-directory-resolution) |
| `GATEWAY_BIND_HOST` | string | `127.0.0.1` | Interface used by the gateway. Keep loopback for a local installation; Docker overrides it internally while publishing the host port only on loopback. |
| `REMOTE_ACCESS_ENABLED` | bool | `false` | Enables remote owner sessions. Requires `PROMETHE_MASTER_KEY`. |
| `PUBLIC_BASE_URL` | URL | — | Public base URL for remote access and OAuth. When remote access is enabled, it must be an absolute HTTPS URL without query or fragment; TLS may terminate at the reverse proxy. |
| `CORS_ALLOWED_ORIGINS` | string | — | Comma-separated explicit HTTP(S) origins for browser and MCP requests. No wildcard support. |
| `REMOTE_SESSION_TTL_MINUTES` | long | `60` | Owner-session lifetime, from 15 to 1440 minutes. |
| `PROMETHE_MASTER_KEY` | Base64 | — | Exactly 32 decoded bytes. Read only from the process environment; mandatory for remote access, OAuth, or persisted MCP secrets. |
| `PROMETHE_MASTER_KEY_ID` | string | `primary` | Active key identifier for encrypted persisted secrets and signatures. Allowed characters: letters, digits, `.`, `_`, `-`; maximum 32 characters. |
| `PROMETHE_PREVIOUS_MASTER_KEYS` | `id=base64,...` | — | Comma-separated previous master keys accepted for decryption and signature verification during rotation. The active key must not be repeated. |

---

## 3. Execution backend

| Variable | Type | Default | Description |
|---|---|---|---|
| `EXEC_BACKEND` | string | `local` | Code backend. `local` uses the native Promethe sandbox; `docker`, `singularity`, `modal` and `daytona` still launch through that sandbox. `ssh` is refused for code execution. |
| `ALLOW_LOCAL_EXEC` | bool | `false` | Deprecated compatibility alias that selects `EXEC_BACKEND=local`. It does not bypass the native sandbox. |
| `DOCKER_IMAGE` | string | *(per language)* | Docker image for `execute_code` — defaults: `python:3.12-slim` and `node:22-slim`; required for Kotlin. Shell-script languages are unavailable. |
| `EXEC_TIMEOUT_MS` | long | `30000` | Execution timeout (ms) |
| `EXEC_MAX_OUTPUT_BYTES` | int | `50000` | Max output size (≈ 50 KB) |
| `CODE_EXECUTION_ENABLED` | bool | `false` | Registers the code-execution tool only when explicitly enabled. |
| `PROCESS_TOOL_ENABLED` | bool | `false` | Registers the process tool only when explicitly enabled. |
| `DOCKER_TOOL_ENABLED` | bool | `false` | Registers the Docker tool only when explicitly enabled. |
| `PROFILE_DIR` | string | `./profiles/developer` | Agent profile folder |
| `SKILLS_DIR` | string | `./skills` | Skills folder |

### SSH (if EXEC_BACKEND=ssh)

Executes commands on a remote machine via SSH.

| Variable | Type | Default | Description |
|---|---|---|---|
| `SSH_HOST` | string | — | Remote server hostname or IP |
| `SSH_USER` | string | — | SSH user |
| `SSH_KEY_PATH` | string | — | Path to the SSH private key (e.g.: `~/.ssh/id_ed25519`) |
| `SSH_PORT` | int | `22` | SSH port |

### Singularity / Apptainer (if EXEC_BACKEND=singularity)

HPC execution via Singularity/Apptainer containers. Used in academic environments where Docker is not available.

| Variable | Type | Default | Description |
|---|---|---|---|
| `SINGULARITY_IMAGE` | string | `promethe.sif` | Path to the Singularity image |
| `SINGULARITY_BIND` | string | — | Mount paths (comma-separated, e.g.: `/data,/scratch`) |

### Modal (if EXEC_BACKEND=modal)

Serverless execution via [Modal](https://modal.com). Ephemeral CPU/GPU containers.

| Variable | Type | Default | Description |
|---|---|---|---|
| `MODAL_TOKEN_ID` | string | — | Modal token ID |
| `MODAL_TOKEN_SECRET` | string | — | Modal token secret |
| `MODAL_APP_NAME` | string | `promethe-sandbox` | Modal application name |

### Daytona (if EXEC_BACKEND=daytona)

Execution in a [Daytona](https://www.daytona.io/) development workspace.

| Variable | Type | Default | Description |
|---|---|---|---|
| `DAYTONA_API_KEY` | string | — | Daytona API key |
| `DAYTONA_SERVER_URL` | string | `https://app.daytona.io` | Daytona server URL |
| `DAYTONA_WORKSPACE` | string | `promethe-workspace` | Daytona workspace name |

---

## 3A. Native sandbox

Process tools use the native `promethe-sandbox` helper when it is packaged or
selected explicitly. The helper speaks JSONL protocol v1. Missing, untrusted,
unsupported or failed helpers disable process tools; they do not enable an
unsandboxed fallback.

| Variable | Type | Default | Description |
|---|---|---|---|
| `SANDBOX_BACKEND` | string | `auto` | Native backend selection. Linux uses Bubblewrap/seccomp. Windows uses the elevated restricted-identity backend after its one-time UAC setup and self-test. macOS remains unavailable. Docker and WSL2 are not automatic fallbacks yet. |
| `SANDBOX_WORKSPACE` | path | `~/.promethe/workspace` | Canonical root exposed to process and workspace file tools. Runtime data, credentials, plugins and the database remain outside this root. |
| `SANDBOX_MODE` | string | `WORKSPACE_WRITE` | `READ_ONLY`, `WORKSPACE_WRITE`, or local-only `FULL_ACCESS` for file tools. Commands and code execution remain confined to `SANDBOX_WORKSPACE`. |
| `SANDBOX_READABLE_ROOTS` | JSON array | `[]` | Additional absolute directories readable by file tools, for example `["C:\\Users\\me\\Documents"]`. The workspace root is managed automatically. |
| `SANDBOX_WRITABLE_ROOTS` | JSON array | `[]` | Additional absolute directories writable by file tools, for example `["C:\\Users\\me\\Documents\\output"]`. Every writable root must also be readable. |
| `SANDBOX_APPROVAL_POLICY` | string | `ON_REQUEST` | Approval policy for process effects. |
| `SANDBOX_NETWORK_MODE` | string | `OFF` | Network remains disabled. `ALLOWLIST` is rejected until an authenticated proxy is implemented; selected roots and `FULL_ACCESS` do not change this. |
| `SANDBOX_ALLOWED_DOMAINS` | comma-separated strings | empty | Reserved for the future authenticated proxy; it does not enable network access today. |
| `PROMETHE_SANDBOX_HELPER` | path | packaged helper | Absolute path to the native helper executable. |
| `PROMETHE_SANDBOX_HELPER_SHA256` | hex string | — | Required 64-character SHA-256 checksum when `PROMETHE_SANDBOX_HELPER` is configured. |
| `PROMETHE_ENABLE_LAB_PLUGINS` | bool | `false` | Local process opt-in for LAB plugins. It cannot be changed through the remote API. Executable hooks still require approval and the native sandbox. |

The native helper enforces bounded execution, workspace roots, protected
metadata paths (`.git`, `.promethe`, `.codex`, `.agents`) and OS-specific
process isolation. The workspace may legitimately be empty; no starter files
are created in it. Selected roots and local `FULL_ACCESS` apply only to file
tools. `FULL_ACCESS` does not expand command, code-execution or MCP stdio
roots, and approvals remain mandatory for every unconfined file operation.
MCP stdio is unavailable until IPC v2 adds persistent bidirectional sessions.

`SANDBOX_READABLE_ROOTS` and `SANDBOX_WRITABLE_ROOTS` must be valid JSON
arrays, not comma-separated strings. For example:

```dotenv
SANDBOX_READABLE_ROOTS=["C:\\Users\\me\\Documents","D:\\datasets"]
SANDBOX_WRITABLE_ROOTS=["C:\\Users\\me\\Documents\\output"]
```

These roots are intended for local configuration. Remote sessions cannot
enable `FULL_ACCESS` or alter the local file-access boundary. File operations
still pass through path canonicalization, protected-path checks and approval.

On Windows, use **Settings > Sandbox > Install sandbox**. The loopback-only
setup route launches the packaged setup script, requests UAC, installs a
protected runner and executes a behavioral self-test. Setup cancellation or
self-test failure keeps process tools unavailable. Windows setup leaves the
owner profile ACL unchanged, grants process access only to the registered
workspace, and disables network access. Selected roots are used by file tools
only. Use `sandbox-native/windows/uninstall.ps1`
from an elevated terminal for removal. See
[`SANDBOX.md`](SANDBOX.md) and the [manual test matrix](release/SANDBOX_MANUAL_TESTS.md).

---

## 3B. Local development agents

Promethe can expose authenticated local Codex and Claude Code installations as
specialized tools inside the normal agent loop. They are not A2A agents and do
not add separate REST lifecycle routes. Detection is visible through
`GET /api/v1/capabilities` and can be refreshed with the existing settings
reload action.

| Variable | Type | Default | Description |
|---|---|---|---|
| `CODEX_CLI_PATH` | absolute path | PATH discovery | Optional path to a native `codex` executable. |
| `CLAUDE_CODE_CLI_PATH` | absolute path | PATH discovery | Optional path to a native `claude` executable. |

The CLI must already be authenticated by the current OS user. Promethe never
copies or reads the provider credential files. It records only the executable
path, version and SHA-256 identity in memory, and persists only the external
thread/session id in Promethe session metadata.

`codex_delegate` uses `codex app-server` over JSONL stdio and requires a Codex
version with named permission profiles. Promethe installs a network-disabled
permission profile on every thread and turn, disables discovered Codex MCP and
plugin/app entries for that delegation, and never inherits a resumed thread's
wider permissions. Existing user-level Codex hooks remain part of the trusted
local CLI installation and are reported as a BETA limitation. `claude_code_delegate`
uses Claude Code streaming JSON with user and project setting sources disabled.
In write mode, its only MCP server is an
ephemeral permission tool connected to Promethe's local approval gate; automatic
memory is disabled for the delegated process.

`READ_ONLY` and `WORKSPACE_WRITE` are the only access modes. Full-access,
skipped-permission and automatic-approval flags are rejected. Cloud Codex and
Claude Agent SDK execution are not included in this release.

---

## 4. Memory

| Variable | Type | Default | Description |
|---|---|---|---|
| `MEMORY_PROVIDER` | string | `embedded` | Provider: `embedded`, `honcho`, `tencent`, `fallback` |

### Honcho (if MEMORY_PROVIDER=honcho)

| Variable | Type | Description |
|---|---|---|
| `HONCHO_URL` | string | Honcho server URL |
| `HONCHO_API_KEY` | string | Honcho API key |

### TencentDB (if MEMORY_PROVIDER=tencent)

| Variable | Type | Description |
|---|---|---|
| `TENCENT_MEMORY_URL` | string | TencentDB Agent Memory server URL |
| `TENCENT_MEMORY_SERVICE_ID` | string | TencentDB service ID |
| `TENCENT_MEMORY_API_KEY` | string | TencentDB API key |

---

## 5. Context window

| Variable | Type | Default | Description |
|---|---|---|---|
| `MAX_CONTEXT_TOKENS` | int | `100000` | Max tokens for the model's context window |
| `COMPRESSION_THRESHOLD` | float | `0.8` | Compression threshold (80% = compress once 80% of context is used) |

---

## 6. Observability

| Variable | Type | Default | Description |
|---|---|---|---|
| `TRACING_BACKEND` | string | `console` | Backend: `console`, `langfuse`, `otlp` |

### Langfuse (if TRACING_BACKEND=langfuse)

| Variable | Type | Description |
|---|---|---|
| `LANGFUSE_PUBLIC_KEY` | string | Langfuse public key |
| `LANGFUSE_SECRET_KEY` | string | Langfuse secret key |
| `LANGFUSE_HOST` | string | URL (default: `https://cloud.langfuse.com`) |

### OTLP (if TRACING_BACKEND=otlp)

| Variable | Type | Description |
|---|---|---|
| `OTLP_ENDPOINT` | string | OTLP endpoint (e.g.: `http://localhost:4317`) |

---

## 7. Security

| Variable | Type | Default | Description |
|---|---|---|---|
| `APPROVAL_MODE` | string | `dangerous` | Tool approval mode: `auto` (safe tools only), `dangerous` (ask for destructive tools), `all` (ask for every tool). The mandatory security set always asks. |
| `APPROVAL_TIMEOUT_MS` | long | `120000` | Approval timeout (2 min) — auto-reject after this delay |
| `FALLBACK_CHAIN_ENABLED` | bool | `false` | Opt-in LLM fallback chain: on model failure, retry a known-good model of the same provider, then a cross-provider safety net. Disabled = failures propagate as errors |
| `OAUTH_ENABLED` | bool | `false` | Enables GitHub and Google Calendar OAuth. Requires remote access, a master key, a fixed redirect URI, and at least one provider pair below. |
| `OAUTH_REDIRECT_URI` | URL | — | Fixed public callback URI, for example `https://gateway.example/auth/oauth/callback`. Client-provided redirect URIs are rejected. |
| `OAUTH_GITHUB_CLIENT_ID` / `OAUTH_GITHUB_CLIENT_SECRET` | string | — | GitHub OAuth app credentials |
| `OAUTH_GOOGLE_CLIENT_ID` / `OAUTH_GOOGLE_CLIENT_SECRET` | string | — | Google OAuth app credentials (offline access for Calendar) |
| `STT_PROVIDER` | string | `openai` | speech_to_text provider — OpenAI-compatible endpoints only (`openai`, `whisper`, `litellm`); anything else is refused explicitly |
| `STT_BASE_URL` | string | `https://api.openai.com` | Base URL of the OpenAI-compatible transcription endpoint |
| `STT_API_KEY` | string | — | API key for the STT endpoint (falls back to `OPENAI_API_KEY`) |

---

## 8. GEPA (Self-evolution)

| Variable | Type | Default | Description |
|---|---|---|---|
| `GEPA_ENABLED` | bool | `false` | Enable genetic prompt optimization |
| `GEPA_INTERVAL_MINUTES` | int | `60` | Interval between optimizations (minutes) |
| `GEPA_AUTO_APPLY` | bool | `false` | Automatically apply improving mutations |

---

## 9. Messaging channels

| Channel | Required | Optional / secondary |
|---|---|---|
| **Telegram** | `TELEGRAM_BOT_TOKEN` | `TELEGRAM_SECRET_TOKEN` |
| **Discord** | `DISCORD_BOT_TOKEN` | `DISCORD_PUBLIC_KEY` (slash commands), `DISCORD_MESSAGE_CONTENT_ENABLED` (default `false`), `DISCORD_ALLOWED_USER_IDS`, `DISCORD_KNOWLEDGE_CHANNEL_IDS` |
| **Slack** | `SLACK_BOT_TOKEN` | `SLACK_SIGNING_SECRET` |
| **WhatsApp** | `WHATSAPP_PHONE_NUMBER_ID` | `WHATSAPP_ACCESS_TOKEN` |
| **Signal** | `SIGNAL_CLI_REST_URL` | `SIGNAL_PHONE_NUMBER` |
| **Matrix** | `MATRIX_HOMESERVER_URL` | `MATRIX_ACCESS_TOKEN` |

Inbound webhook verification secrets read by the gateway are:

| Webhook | Variable | Use |
|---|---|---|
| Telegram | `TELEGRAM_SECRET_TOKEN` | Secret header token |
| WhatsApp | `WHATSAPP_VERIFY_TOKEN` | Subscription verification token |
| WhatsApp | `WHATSAPP_APP_SECRET` | HMAC request signature secret |
| Discord | `DISCORD_PUBLIC_KEY` | Interaction signature verification key |
| Slack | `SLACK_SIGNING_SECRET` | Request signature secret |
| Signal | `SIGNAL_WEBHOOK_TOKEN` | Shared webhook token |
| Matrix | `MATRIX_WEBHOOK_TOKEN` | Shared webhook token |

See [CHANNELS.md](CHANNELS.md) for detailed setup guides.

Discord ID lists accept values separated by commas, semicolons, spaces, or newlines. An empty
`DISCORD_ALLOWED_USER_IDS` preserves unrestricted access for users who can reach the bot. Once the
setting is non-empty, only valid numeric IDs in the list can invoke Promethe; a malformed non-empty
list denies every user. `DISCORD_KNOWLEDGE_CHANNEL_IDS` is empty by default and therefore archives
nothing. These variables remain the static bootstrap policy. Authenticated owner conversations and the
`/api/v1/channels/discord/policy` API can add structured SQLite-backed overrides at runtime: scoped
allow/deny rules, deterministic subject phrases, channel capture and project association. Dynamic
`DENY` rules take precedence and policy changes do not require a process restart.

---

## 10. Integrations

| Service | Variable(s) |
|---|---|
| **GitHub** | `GITHUB_TOKEN` |
| **Notion** | `NOTION_API_KEY` |
| **Jira** | `JIRA_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN` |
| **Email** | `EMAIL_API_KEY`, `EMAIL_PROVIDER` (`resend`/`sendgrid`/`mailgun`) |
| **Twilio** | `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_PHONE_NUMBER` |
| **Google Calendar** | `GOOGLE_CALENDAR_TOKEN` |

---

## 11. LiteLLM Proxy (Docker Compose)

| Variable | Type | Default | Description |
|---|---|---|---|
| `LITELLM_MASTER_KEY` | string | `sk-promethe-dev` | LiteLLM proxy master key |
| `LITELLM_API_KEY` | string | — | Key to connect to the proxy |

---

## 12. RAG / Knowledge Base

RAG configuration is done via the **Settings Screen** (Knowledge tab) or via the `PUT /api/v1/rag/config` API. Values are persisted in `credentials.json`.

| Variable | Type | Default | Description |
|---|---|---|---|
| `ragEnabled` | bool | `false` | Enables/disables the RAG system |
| `ragEmbeddingProvider` | string | `ollama` | Embedding provider: `ollama`, `openai`, `litellm`, `mistral`, `gemini`, `voyage`, `cohere` |
| `ragEmbeddingModel` | string | `nomic-embed-text` | Embedding model (provider-specific) |
| `ragEmbeddingBaseUrl` | string | — | Embedding service URL (auto for Ollama: `localhost:11434`) |
| `ragEmbeddingApiKey` | string | — | Embedding service API key |
| `ragEmbeddingDimensions` | int | `768` | Embedding vector dimensions |
| `ragVectorStoreType` | string | `sqlite_vec` | Vector store type: `sqlite_vec`, `chroma`, `qdrant`, `pinecone`, `milvus` |
| `ragVectorStoreUrl` | string | — | Vector store URL (e.g.: `http://localhost:6333` for Qdrant) |
| `ragVectorStoreApiKey` | string | — | Vector store API key (Pinecone, Qdrant Cloud) |
| `ragVectorStorePath` | string | `vectors.db` | Path to the SQLite-vec file (embedded mode) |
| `ragChunkSize` | int | `512` | Maximum chunk size in tokens |
| `ragChunkOverlap` | int | `50` | Overlap between consecutive chunks |

### Embedding models per provider

| Provider | Default model | Dimensions | Notes |
|---|---|---|---|
| Ollama | `nomic-embed-text` | 768 | Local, free |
| OpenAI | `text-embedding-3-small` | 1536 | Cloud, paid |
| Mistral | `mistral-embed` | 1024 | Cloud |
| Gemini | `text-embedding-004` | 768 | Cloud, Google AI |
| Voyage | `voyage-3` | 1024 | Cloud |
| Cohere | `embed-v4.0` | 1024 | Cloud |
| LiteLLM | *(configurable)* | *(variable)* | Unified proxy |

### Supported vector stores

| Store | Default URL | Required config |
|---|---|---|
| SQLite-vec | *(local file)* | Zero config (embedded) |
| Chroma | `http://localhost:8000` | Docker: `docker run -p 8000:8000 chromadb/chroma` |
| Qdrant | `http://localhost:6333` | Docker: `docker run -p 6333:6333 qdrant/qdrant` |
| Pinecone | *(cloud)* | API key + index URL required |
| Milvus | `http://localhost:19530` | Docker: `docker-compose` recommended |

---

## 13. Voice & Media Generation

| Variable | Type | Default | Description |
|---|---|---|---|
| `STABILITY_API_KEY` | string | — | Stable Diffusion (image generation) |
| `REPLICATE_API_TOKEN` | string | — | Replicate (various ML models, Flux) |
| `FAL_KEY` | string | — | fal.ai (fast generation) |
| `RUNWAY_API_KEY` | string | — | Runway Gen-3 (video generation) |
| `ELEVENLABS_API_KEY` | string | — | ElevenLabs (high-quality TTS) |
| `DEEPGRAM_API_KEY` | string | — | Deepgram (real-time STT) |
| `COHERE_API_KEY` | string | — | Cohere embeddings |
| `VOYAGE_API_KEY` | string | — | Voyage AI embeddings |
| `MISTRAL_API_KEY` | string | — | Mistral embeddings |

The canonical provider-secret registry also includes `OPENAI_API_KEY`, `GOOGLE_API_KEY`,
`ANTHROPIC_API_KEY`, `DEEPSEEK_API_KEY`, `NVIDIA_NIM_API_KEY`, `LITELLM_API_KEY`,
`OPENROUTER_API_KEY`, `MOONSHOT_API_KEY`, and `XAI_API_KEY`.
`PUT /api/v1/config/env/{KEY}` accepts only registered provider or runtime keys and returns
`400 Bad Request` for an unknown key.

### Image generation providers

| Provider | Required variable | Models |
|---|---|---|
| OpenAI (DALL-E) | `OPENAI_API_KEY` | DALL-E 3, DALL-E 2 |
| Google Imagen | `GOOGLE_API_KEY` | Imagen 3 |
| Stability AI | `STABILITY_API_KEY` | SDXL, SD3 |
| Replicate | `REPLICATE_API_TOKEN` | Flux, SDXL |
| fal.ai | `FAL_KEY` | Flux.1, Fast SDXL |

### Video generation providers

| Provider | Required variable | Models |
|---|---|---|
| Google Veo | `GOOGLE_API_KEY` | Veo 3 (known but unavailable in this release) |
| Runway | `RUNWAY_API_KEY` | Gen-3 Alpha |
| Luma | *(config via Settings)* | Ray |
| MiniMax | *(config via Settings)* | Video-01 |

---

## 14. Browser Automation

| Variable | Type | Default | Description |
|---|---|---|---|
| `BROWSER_BACKEND` | string | `cdp` | Browser backend: `cdp` (local Chrome), `browserbase` (cloud) |
| `BROWSERBASE_API_KEY` | string | — | Browserbase API key (cloud mode) |
| `BROWSERBASE_PROJECT_ID` | string | — | Browserbase project ID |

> **CDP mode prerequisite**: Chrome or Chromium installed locally (120+).

---

## 15. Home Assistant (Home Automation)

| Variable | Type | Default | Description |
|---|---|---|---|
| `HOMEASSISTANT_URL` | string | — | Home Assistant instance URL (e.g.: `http://homeassistant.local:8123`) |
| `HOMEASSISTANT_TOKEN` | string | — | HA Long-Lived Access Token |

---

## 16. Autonomous Goals

| Variable | Type | Default | Description |
|---|---|---|---|
| Budget preset `MINIMAL` | — | — | Max 3 tasks, 10K tokens, 5 min |
| Budget preset `STANDARD` | — | — | Max 10 tasks, 100K tokens, 30 min |
| Budget preset `EXTENDED` | — | — | Max 25 tasks, 500K tokens, 2h |
| Budget preset `UNLIMITED` | — | — | No limits (⚠️ costly) |

Budgets are configured via the API (`POST /api/v1/goal`) with the `budgetPreset` field.

---

## 17. Additional channels

The 13 additional channels (beyond the 6 main ones in the Channels section) are configured via **constructor parameters** injected by the gateway. See [CHANNELS.md](CHANNELS.md) for detailed setup guides.

| Channel | Constructor parameters |
|---|---|
| **Email** | `smtpHost`, `smtpPort`, `username`, `password`, `fromAddress`, `apiMode` (SENDGRID/MAILGUN/SMTP_RAW) |
| **SMS** (Twilio) | `accountSid`, `authToken`, `phoneNumber` |
| **Microsoft Teams** | `appId`, `appSecret`, `tenantId` |
| **Mattermost** | `serverUrl`, `botToken` |
| **DingTalk** | `appKey`, `appSecret` |
| **Feishu/Lark** | `appId`, `appSecret` |
| **WeCom** | `corpId`, `agentId`, `corpSecret`, `aesKey`, `token` |
| **LINE** | `channelAccessToken` |
| **QQ** | `appId`, `token` |
| **Weixin (WeChat)** | `appId`, `appSecret`, `token`, `aesKey` |
| **BlueBubbles** | `serverUrl`, `password` |
| **Ntfy** | `serverUrl`, `topic`, `accessToken` |
| **Home Assistant** | `baseUrl`, `accessToken` |

---

## Runtime configuration API

Variables can be managed via the REST API:

```bash
# List all variables (values masked)
curl http://localhost:8080/api/v1/config/env

# Set a variable
curl -X PUT http://localhost:8080/api/v1/config/env/GITHUB_TOKEN \
  -H "Content-Type: application/json" \
  -d '{"value": "ghp_abc123..."}'

# Delete a variable
curl -X DELETE http://localhost:8080/api/v1/config/env/GITHUB_TOKEN
```

Variables are merged into `~/.promethe/credentials.json`. Updates use a synchronized atomic
replacement, preserve unrelated settings and secrets, and apply best-effort owner-only permissions.
Masked values returned by `GET` are display placeholders and are never written back by the Settings UI.

---

## 18. Context files and customization

The agent automatically loads Markdown files at each conversation to customize its behavior. These files are injected into the system prompt.

### Project files (loaded by `ContextFileLoader`)

Place these files **at the root of your project** (next to `README.md`):

| File | Role | Template provided |
|---|---|---|
| `.promethe.md` | Project instructions and constraints | ✅ Yes |
| `SOUL.md` | Agent personality, tone and values | ✅ Yes |
| `AGENTS.md` | Multi-agent configuration and delegation | ✅ Yes |
| `CONTEXT.md` | Additional context (sprint, decisions, notes) | ✅ Yes |
| `.promethe/context.md` | Alternative to CONTEXT.md (in a folder) | ❌ Create if needed |

> **Limit**: each file must be under 50 KB to avoid overloading the prompt.

### Profile files (loaded by `ProfileManager`)

Located in the active profile folder (default `profiles/developer/`):

| File | Role | Template provided |
|---|---|---|
| `USER.md` | User identity, expertise and preferences | ✅ Yes |
| `MEMORY.md` | Long-term file memory (facts, decisions, history) | ✅ Yes |
| `skills/*.md` | Agent's specialized skills | ✅ 1 example |

### Customization

All templates are provided with `<!-- INSTRUCTION -->` comments to guide modification:

```bash
# Open the personality template
code SOUL.md

# Open the user profile
code profiles/developer/USER.md
```

Files are automatically reloaded at each new conversation. If `HotReloadWatcher` is active, changes are picked up **without restart**.

---

## 19. Promethe home directory resolution

All runtime data (database, skills, plugins, credentials, MCP config) is centralized under
`~/.promethe/`, resolved by the `PrometheHome` object
(`shared/src/jvmMain/kotlin/dev/promethe/core/PrometheHome.kt`) so that paths stay stable regardless
of the process working directory:

| Path | Purpose |
|---|---|
| `~/.promethe/` (`PrometheHome.dir`) | Root directory — `File(System.getProperty("user.home"), ".promethe")` |
| `~/.promethe/skills/` (`PrometheHome.skillsDir`) | Dynamically loaded skills |
| `~/.promethe/plugins/` (`PrometheHome.pluginsDir`) | Installed plugins |
| `~/.promethe/workspace/` (`PrometheHome.workspaceDir`) | Default sandbox-visible agent workspace |
| `~/.promethe/productivity/` (`PrometheHome.productivityDataDir`) | Internal todo and notes storage, never mounted into the sandbox |
| `~/.promethe/promethe.db` (`PrometheHome.dbFile`) | Default SQLite database file (override with `PROMETHE_DB_URL`) |
| `~/.promethe/credentials.json` | Atomic source of truth for LLM, integrations and runtime settings; values remain plaintext with best-effort owner-only permissions |
| `~/.promethe/mcp.json` | Legacy MCP input imported once into encrypted SQLite when a master key is configured; ignored afterward. |

`PrometheHome.ensureDirectories()` creates the root, skills, plugins, workspace and productivity
directories if missing (idempotent).

### One-shot migration

On startup, `AgentBootstrap` migrates data from the old relative, working-directory-based paths into
`~/.promethe/` if the old paths exist and the new ones don't yet:

| From (legacy) | To (`~/.promethe/`) |
|---|---|
| `./data/promethe.db` | `promethe.db` |
| `./profiles/developer/skills/` | `skills/` |
| `./profiles/developer/plugins/` | `plugins/` |

This migration is safe to run repeatedly — it is a no-op once the new paths already contain data.
