# Promethe v1.0 Manual Acceptance

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](../EXPERIMENTAL_STATUS.md).

> Executable M01-M24 release checklist. Run against the release-candidate build, record every result, and retain the evidence bundle. This document never assigns a capability maturity or availability value: the live registry at `GET /api/v1/capabilities` is the only status source of truth.

## Rules of execution

- Run every M01-M24 check on the matrix below. A check may be `N/A` only when its stated target is not in the release scope; record the reason, owner, and approving reviewer.
- Before and after each run, save the complete registry response from `GET /api/v1/capabilities`. Do not copy a status from this document or from `CAPABILITY_CERTIFICATION.md` into a product claim.
- `maturity` is the release claim (`STABLE`, `BETA`, `LAB`, `UNAVAILABLE`); `availability` is installation state (`AVAILABLE`, `MISSING_CONFIGURATION`, `DISABLED`, `NOT_IMPLEMENTED`). A configured provider is not thereby certified STABLE.
- A pass requires the observable result, request/trace ID, build, target, fixture, and artifact IDs in the evidence record. Screenshots alone do not prove persistence, backend, budget, or security behavior.

## Prerequisites and run record

- [ ] Release commit, version, build artifact, and clean disposable profile/database are recorded.
- [ ] Test credentials are disposable, least-privilege, and listed by configuration key only, never by secret value.
- [ ] Gateway is available in embedded desktop, CLI, remote CLI, and daemon/Docker modes as applicable.
- [ ] Browser automation/capture, desktop capture, terminal capture, Docker, and a resettable workspace are available.
- [ ] Provider fixtures are available for the selected certification scope; unconfigured or unimplemented entries are tested as negative cases.

| Field | Value |
|---|---|
| Release/version and commit/build | |
| Tester, reviewer, date/time/timezone | |
| OS, runtime, architecture | |
| Browser/version | |
| Launch mode and gateway URL/port | |
| LLM/media/voice provider and model IDs | |
| Budget preset and observed limits | |
| Registry response artifact before/after | |
| Evidence root and exception register | |

## Required target matrix

Record `PASS`, `FAIL`, or justified `N/A` for each applicable cell. The browser target is Wasm; desktop means the Compose desktop artifact; CLI includes embedded and remote CLI; Docker includes the gateway and sandbox execution image.

| Surface | Windows 11 x64 | macOS 14+ (Intel/Apple Silicon) | Ubuntu 22.04+ x64 | Browser/Wasm | CLI/daemon | Docker |
|---|---|---|---|---|---|---|
| Desktop launch/setup | Required | Required | Required | N/A | N/A | N/A |
| Core chat/session | Required | Required | Required | Required | Required | Required |
| File/tools/Git/approval | Required | Required | Required | UI/API equivalent | Required | Required sandbox |
| REST/WebSocket/auth | Required | Required | Required | Required | Required | Required |
| Channels/integrations | One configured fixture | One configured fixture | One configured fixture | N/A | Required | Required |
| Media/voice | One configured fixture per certified entry | One configured fixture per certified entry | One configured fixture per certified entry | N/A unless exposed | Required | Required |
| Upgrade/rollback | Required | Required | Required | N/A | Required | Required |

## Provider and channel fixture inventory

Use the exact provider IDs and configuration keys reported by the runtime registry. The following is the required v1.0 coverage inventory from the provider/channel registries; it is not a maturity assertion.

### LLM families

`openrouter`, `openai`, `anthropic`, `google`, `deepseek`, `nvidia`, `ollama`, `litellm`, `kimi`, `xai`. For each selected family record provider, model, base URL class (cloud, proxy, or local), key/configuration class, actual provider used, fallback behavior, token usage, and cost. Ollama is local and keyless; LiteLLM is OpenAI-compatible and requires an explicit model/base URL as configured. Kimi and xAI use Koog's OpenAI-compatible adapter; this does not exempt them from provider-specific request and tool-turn checks.

### Koog 1.1.1, Kimi K3, and xAI regression matrix

These checks are mandatory for the integration release. Record them as subcases of the named M check. Paid calls run only with disposable credentials and explicit tester approval; CI `providerLiveTest` without `PROMETHE_PROVIDER_LIVE_TESTS=true` is skip evidence, not provider certification.

| ID | Surface | Procedure | Pass criteria |
|---|---|---|---|
| `M03-K01` | Version and discovery | Attach `verifyKoogResolvedVersions`; reload settings; query provider/model endpoints for `kimi` and `xai`. | Koog resolves to `1.1.1`; `kimi-k3` and `grok-4.5` are selectable when configured; discovered IDs are preserved; secrets never appear in responses or logs. |
| `M03-K02` | Kimi reasoning | Run the same deterministic prompt with `AUTO`, `LOW`, and `HIGH`; inspect the UI and attempt persisted/API `MEDIUM`. | All valid efforts complete; `AUTO` uses the provider default; `MEDIUM` is absent from the UI and rejected before any provider call; no temperature control is offered or sent. |
| `M03-K03` | Kimi tool turn | Through `/agents/a2a`, ask Kimi to call one deterministic read-only tool and synthesize its result; continue in the same session. | The tool call and result retain their IDs/types, `reasoning_content` survives the continuation, the final answer is natural, and no raw observation is rendered as the answer. |
| `M03-K04` | xAI Responses | Set `XAI_API_MODE=responses`; run `AUTO`, `MEDIUM`, and `HIGH`, then one deterministic tool call. | Requests use `/v1/responses`, omit effort for `AUTO`, set `store=false`, request encrypted reasoning content, disable parallel tool calls, and complete the post-tool synthesis. |
| `M03-K05` | xAI Chat Completions | Restart with `XAI_API_MODE=chat_completions`; repeat a prompt and deterministic tool call. | Requests use `/v1/chat/completions`, omit temperature, apply selected reasoning effort, disable parallel tool calls, and return the same agent-visible behavior as Responses mode. |
| `M04-K06` | Profile persistence | Save Kimi/xAI provider, model, and reasoning effort; restart; duplicate the profile; switch from xAI `MEDIUM` to Kimi. | Values persist and duplicate correctly; invalid Kimi `MEDIUM` becomes `AUTO`; no unrelated profile or skill assignment changes. |
| `M12-K07` | Provider failures | Test invalid key, unavailable model, timeout, 429, malformed discovery response, and an unknown provider ID. | Errors are structured and sanitized; the last valid catalog remains usable; unknown providers fail closed; no silent OpenRouter fallback or duplicate tool execution occurs. |
| `M15-K08` | Catalog reload | Change `MOONSHOT_BASE_URL`/`XAI_BASE_URL`, save, reload, then remove each key. | Discovery uses exactly `<base>/v1/models`; reload updates availability without exposing keys; removal produces `MISSING_CONFIGURATION`; undocumented `KIMI_BASE_URL` has no effect. |
| `M17-K09` | Unified agent path | Invoke equivalent deterministic tasks through A2A and `/v1/chat/completions`, then through one webhook/channel fixture and delegated voice `promethe_agent`. | Every task emits an agent trajectory attributable to `AgentExecutionService` and `AIAgent.executeLoop`; approvals and tool results are consistent; no internal JSON-RPC loopback is required. |
| `M17-K10` | Observation synthesis | Reproduce the weather/search scenario with both providers and one additional OpenAI-compatible provider. | Search output remains an internal observation; the user receives a synthesized answer with useful facts, never the raw numbered observation payload. |
| `M21-K11` | Secret and request hygiene | Inspect sanitized request capture, logs, database, settings responses, and error paths for both providers. | API keys, encrypted reasoning, prompt cache keys, and raw authorization headers are absent from user-visible output and ordinary logs; base URLs are not accepted from user prompts. |
| `M23-K12` | Migration and compatibility | Upgrade a database containing provider `gemini` and profiles without `reasoning_effort`; inspect Flyway history and restart twice. | V4 is recorded once, `reasoning_effort=AUTO`, `gemini` becomes canonical `google`, unknown providers remain rejected, and the second startup is idempotent. |

### Media families

| Capability | Required provider families/IDs to enumerate and test when configured |
|---|---|
| Image generation | OpenAI `openai-dalle3`; Google `google-imagen3`; Stability AI `stability-ai`; Replicate `replicate-flux` |
| Vision | OpenAI `openai-vision`; Google `google-gemini-vision`; Anthropic `anthropic-claude-vision` |
| Embeddings | OpenAI `openai-embed`; Google `google-embed`; Ollama `ollama-embed`; Cohere `cohere-embed`; Voyage AI `voyage-embed`; Mistral `mistral-embed` |
| Video generation | Google `google-veo3`; Replicate `replicate-luma`; fal.ai `fal-minimax`; Runway `runway-gen3` |
| Video analysis | Google `google-gemini-video`; OpenAI `openai-vision-frames` |

Record model and output format. An entry reported `NOT_IMPLEMENTED` must not be called a successful provider test; the expected result is a clear unavailable/not-implemented response.

### Voice families

| Voice capability | Required provider families/IDs to enumerate |
|---|---|
| TTS | Google Gemini `gemini_tts`; ElevenLabs `elevenlabs`; Cartesia `cartesia`; OpenAI `openai_tts` |
| STT | Deepgram `deepgram`; AssemblyAI `assemblyai`; OpenAI `openai_stt`; self-hosted Whisper `whisper`; Google Cloud `google_stt` (expected `UNAVAILABLE` in v1) |
| Real-time S2S | Google Gemini Live `gemini_live`; OpenAI Realtime `openai_realtime`; Moshi `moshi` |
| Translate | Google Gemini Translate `gemini_translate`; OpenAI Realtime Translate `openai_translate` |

Test the gateway voice registry separately from the agent STT tool. Record audio format, sample rate, model/voice, transcript/output hash, and whether the provider is registry-visible, configured, implemented, and actually selected.

### Messaging channels: all 19

For every row, configure a disposable endpoint where feasible, send one inbound and one outbound deterministic message, verify signature/challenge behavior, and capture `GET /api/v1/channels` plus the matching `channel.<id>` registry descriptor. If a required channel/provider descriptor is absent from `GET /api/v1/capabilities`, record a registry defect and fail the applicable check; never synthesize a status in this document. A missing credential must produce `MISSING_CONFIGURATION`, not a false pass.

| # | Channel | Registry/configuration fixture |
|---:|---|---|
| 1 | Telegram | bot token + secret token |
| 2 | Discord | bot token + public key |
| 3 | Slack | bot token + signing secret |
| 4 | WhatsApp | access token + app/verification secret |
| 5 | Signal | signal-cli REST URL + webhook token/phone |
| 6 | Matrix | homeserver + access/webhook token |
| 7 | Email | SMTP/SendGrid/Mailgun credentials and inbound mode |
| 8 | SMS | Twilio SID/token/from number |
| 9 | Microsoft Teams | app ID + app secret |
| 10 | Mattermost | server URL + access token |
| 11 | DingTalk | webhook URL + signing secret/keyword mode |
| 12 | Feishu/Lark | app ID + app secret |
| 13 | WeCom/WeChat Work | webhook key or corp ID/secret/agent ID |
| 14 | LINE | channel access token |
| 15 | QQ Bot | app ID + token |
| 16 | Weixin Official Account | app ID + app secret |
| 17 | BlueBubbles/iMessage | Mac server URL + password |
| 18 | ntfy | server URL + topic |
| 19 | Home Assistant | URL + long-lived access token |

## M01-M24 executable checks

Each item below must include the evidence fields in the schema at the end of this document.

### Launch, setup, and conversation

#### M01 - Desktop launch
- [ ] Launch the artifact on each required desktop OS. Pass: window renders without crash, navigation is usable, and registry endpoint responds.

#### M02 - First-run setup
- [ ] In a fresh profile, configure one LLM family and restart. Pass: usable state and configuration persist; entered secrets are absent from UI, URLs, ordinary logs, and evidence.

#### M03 - Basic chat response
- [ ] Send `Reply with exactly: v1-chat-ok`. Pass: exact response appears, session remains usable, and gateway has no unhandled failure.
- [ ] Execute `M03-K01` through `M03-K05` for Kimi K3 and xAI when they are in the release scope. Pass: provider transport, reasoning, tool continuation, and model discovery meet the regression matrix above.

#### M04 - Conversation persistence and resume
- [ ] Send two prompts, close/disconnect, reopen, resume, and send a third. Pass: ordered history and continuation survive restart.
- [ ] Execute `M04-K06`. Pass: provider/model/reasoning configuration survives restart and provider switching never leaves an unsupported effort selected.

#### M05 - Profile and context behavior
- [ ] Set a harmless preference in one disposable profile, use it in a new turn, then test a fresh profile. Pass: context applies only to the owning profile.

#### M06 - Skills discovery and use
- [ ] Add a disposable distinctive skill and invoke it. Pass: it is discoverable and affects the run, or registry/UI reports its unavailable state and reason.

### Tools, workspace, and agent behavior

#### M07 - Read-only file operation
- [ ] Read a fixture and compare hash before/after. Pass: content matches and file is unchanged.

#### M08 - Approved file write and patch
- [ ] Request a one-line create/patch. Pass: exact diff is present, unrelated content is preserved, and approval/audit evidence is captured.

#### M09 - Command execution boundary
- [ ] Run a harmless literal command, then attempt a shell interpreter, pipe/redirection, path outside workspace, and unapproved execution. Pass: Docker sandbox succeeds for the safe case; each negative case is rejected, contained, or approval-gated; no network escape or host mutation occurs.

#### M10 - Git workflow
- [ ] Inspect status/diff, create a disposable change, request supported Git operation, and attempt remote/destructive operation. Pass: reality is reflected and destructive/remote actions require approval.

#### M11 - Memory round trip
- [ ] Store, recall in a later turn, delete, and recall again. Pass: deletion is effective and cross-profile isolation holds.

#### M12 - Resilience and clean failure
- [ ] Exercise timeout, invalid provider key, provider 5xx, tool failure, and exhausted retry budget. Pass: retry/replan behavior follows configuration, error is clear, no duplicate unsafe action occurs, and session/database remain usable.
- [ ] Execute `M12-K07`. Pass: Kimi/xAI failures remain explicit, sanitized, fail closed, and do not trigger an unintended provider fallback.

#### M13 - Multi-agent delegation
- [ ] List agents, delegate a deterministic task, retrieve result, and cancel one run. Pass: child identity/session, result attribution, isolation, and cancellation are clear.

#### M14 - Scheduled task lifecycle
- [ ] Create, list, execute once, disable, delete, then verify no post-delete execution. Pass: CRUD and execution history agree.

### API, UI, integrations, and media

#### M15 - REST, WebSocket, and capability registry
- [ ] Authenticate as documented; call health/status, `GET /api/v1/capabilities`, session creation, and streaming/WebSocket routes. Pass: documented status codes and the complete response schema are valid; registry snapshot is saved and drives all later capability claims.
- [ ] Execute `M15-K08` and save provider/model endpoint responses before and after reload. Pass: configured base URLs, catalogs, availability, and secret masking agree.

#### M16 - Browser UI flow
- [ ] Sign in in Wasm browser UI, navigate, chat, refresh, and test an unlisted origin. Pass: session behavior is documented, controls work, no URL secret appears, and disallowed origin is rejected.

#### M17 - A2A/ACP interoperability
- [ ] Fetch agent card and invoke one supported local A2A/ACP capability. Pass: advertised capability and completed/explicitly failed response agree with the registry.
- [ ] Execute `M17-K09` and `M17-K10`. Pass: every user task enters the shared agent loop, typed tool turns survive, and raw observations never become final UI messages.

#### M18 - MCP lifecycle
- [ ] Add disposable MCP server, list tools, invoke a safe tool, remove server, then retry. Pass: provenance is visible, secrets are protected, and removed tool cannot run.

#### M19 - Integrations and all channels
- [ ] Run the 19-channel inventory above plus one configured external integration read and invalid/revoked credential case. Pass: valid traffic works, signature/challenge checks reject tampering, invalid credentials fail clearly, and unrelated features remain usable.

#### M20 - Certified media and voice capability matrix
- [ ] For every registry entry selected for certification, run its minimal fixture across required targets and provider families. Pass: output format/hash, actual provider/model, registry descriptor, cost, and failure behavior match; missing configuration and `NOT_IMPLEMENTED` are reported as unavailable.

### Security, budget, upgrade, and handoff

#### M21 - Authentication and authorization negative matrix
- [ ] Test no auth, malformed bearer, expired/revoked session, wrong owner, owner action, logout, token reuse, remote access without master key, invalid master key, owner bootstrap from non-loopback, and OAuth callback with bad/replayed state. Pass: each is denied with the documented status, no protected data leaks, and audit entries contain no secrets.
- [ ] Execute `M21-K11`. Pass: provider credentials and reasoning continuity data remain server-side and absent from logs, responses, browser storage, and URLs.

#### M22 - Secret, transport, origin, and sandbox hygiene
- [ ] Inspect URLs, browser storage, cookies, request captures, logs, DB/config files, Docker mounts, network, host filesystem, CORS, and MCP origins. Pass: no secret in URL/body logs/browser-readable storage; HTTPS/secure HttpOnly SameSite cookie rules hold; wildcard origins, shell metacharacters, path traversal, network access, privileged Docker, and host writes are rejected.

#### M23 - Budget, clean install, migration, upgrade, and rollback
- [ ] Run deterministic goal fixtures under `MINIMAL` (10,000 tokens, $0.10, 10 iterations, 5 minutes, 1 retry), `STANDARD` (100,000, $1.00, 50, 30 minutes, 2 retries), and `EXTENDED` (500,000, $5.00, 200, 2 hours, 3 retries). Verify `UNLIMITED` is explicit and never the default. Pass: token/cost/iteration/time/retry ceilings stop work cleanly and `token_budget` reports usage.
- [ ] Install clean, migrate a disposable pre-release DB/profile, back up, upgrade, verify required data, roll back to the prior artifact/database, and recover after an interrupted migration. Pass: clean install starts, migration is either lossless or explicitly reports its supported limitation, rollback/recovery starts, and backup is restorable.
- [ ] Execute `M23-K12`. Pass: the reasoning migration is idempotent and only the verified historical `gemini` provider alias is canonicalized.

#### M24 - Documentation and supportability handoff
- [ ] Run the docs verifier, follow quick start on one fresh target, attach the final registry snapshots and evidence index, and reconcile every public claim to a descriptor. Pass: all applicable M01-M24 checks pass, no missing owner/review date/evidence exists, and no manual status contradicts `GET /api/v1/capabilities`.

## Evidence schema

One record per check, target, channel, provider, and negative case:

| Field | Required value |
|---|---|
| `check_id` | `M01`-`M24` plus subcase ID |
| `result` | `PASS`, `FAIL`, or `N/A` with reason/owner/reviewer |
| `build` | version, commit, artifact hash |
| `target` | OS/arch, surface, launch mode, runtime, browser, Docker image |
| `fixture` | sanitized input/request, channel/provider/model ID |
| `expected` / `observed` | exact outcome, status code, error class, output format/hash |
| `registry_snapshot` | raw `GET /api/v1/capabilities` response before and after |
| `budget` | preset and token/cost/iteration/time/retry usage/limit |
| `security_case` | actor, auth/origin, boundary, negative input, decision |
| `artifacts` | screenshot/log/trace/request/response/audio/media/diff/DB-backup IDs and paths |
| `owner`, `reviewer`, `timestamp` | named accountable people and timezone-aware time |
| `limitations`, `exception_id` | known gap, mitigation, expiry, and disposition if any |

## Release decision and maturity rules

- Release candidate: every applicable M01-M24 result is `PASS`; every `N/A` is justified; no unresolved security, auth, data-loss, budget, installation, migration, or rollback blocker remains.
- Public v1.0: release-candidate rule plus independent review, complete evidence, and registry response attached.
- `STABLE`: may be claimed only when the live descriptor says `maturity=STABLE` and the named scope has passing acceptance, two independent repeatable runs/environments, failure evidence, owner, and current review date.
- `BETA`: claim only with the live descriptor saying `BETA`, a beta warning, one end-to-end run, known limitations, and failure evidence.
- `LAB`: claim only with live `LAB`, experimental warning, reproducible proof of life, explicit risk/scope, and owner; it is outside the stable promise.
- `UNAVAILABLE`: claim only with live `UNAVAILABLE` or an unavailable `availability`; exclude it from availability claims and show the blocking reason. `MISSING_CONFIGURATION`, `DISABLED`, and `NOT_IMPLEMENTED` are never successful availability evidence.
- A failed check, missing evidence, expired review, security/data-integrity regression, or changed provider contract blocks promotion and requires a new registry snapshot. Certification documents may summarize the snapshot but may not override it.

## Sign-off

| Role | Name | Decision | Signature/date | Exception ID |
|---|---|---|---|---|
| Test lead | | `ACCEPT / CONDITIONAL / REJECT` | | |
| Security reviewer | | `ACCEPT / CONDITIONAL / REJECT` | | |
| Release owner | | `ACCEPT / CONDITIONAL / REJECT` | | |
| Independent reviewer | | `ACCEPT / CONDITIONAL / REJECT` | | |

Final release decision: `________________`  Evidence bundle: `________________`
