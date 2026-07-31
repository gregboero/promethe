# Promethe v1.0 Capability Certification

> Certification record and evidence index for the v1.0 release. Runtime `GET /api/v1/capabilities` is authoritative for maturity and installation status. This file records scope, evidence, and review; it must never manually claim a contradictory status.

## Source of truth

The gateway endpoint returns:

```json
{
  "version": "<release version>",
  "capabilities": [
    {
      "id": "<stable capability id>",
      "name": "<display name>",
      "category": "<category>",
      "maturity": "STABLE|BETA|LAB|UNAVAILABLE",
      "availability": "AVAILABLE|MISSING_CONFIGURATION|DISABLED|NOT_IMPLEMENTED",
      "platforms": ["gateway"],
      "risk": "READ|WRITE|DESTRUCTIVE|EXTERNAL",
      "requiredConfiguration": ["<configuration key names>"],
      "limitations": ["<runtime limitation>"]
    }
  ]
}
```

At each certification run, attach the raw response, endpoint URL, build, timestamp, and target. Match records by exact `id`. `maturity` is the public support claim; `availability` is installation/configuration state. Configuration presence does not promote maturity. A required capability absent from the response is a registry defect and cannot be filled in by this document. The table below intentionally contains no hand-authored status values.

## Maturity rules

| Runtime `maturity` | Evidence required | Public rule |
|---|---|---|
| `STABLE` | Passing applicable M01-M24 checks, two independent repeatable runs/environments, documented failure and security behavior, owner, current review date, independent review | May be advertised for the exact `platforms` and scope in the descriptor |
| `BETA` | One end-to-end manual run, known limitations, failure evidence, owner, current review date | Must carry a beta warning and no reliability guarantee |
| `LAB` | Reproducible proof of life, explicit scope/risk, owner | Experimental only; excluded from stable promise |
| `UNAVAILABLE` | Absence/blocker evidence, expected user-visible unavailable behavior, next review date | Must not be advertised as available |

The endpoint is re-read before promotion, after upgrade, after provider/configuration changes, and after a regression. If evidence requires a higher maturity than the endpoint reports, the result is `CONDITIONAL` or `REJECT`, never a manual promotion. Demote or stop advertising when evidence is invalidated, a security/data-integrity defect appears, a provider contract changes, or review expires.

## Runtime certification inventory

Populate one row per descriptor returned by the endpoint. Keep the raw response as the value for `Runtime descriptor`; do not replace it with `STABLE`, `BETA`, `LAB`, or `UNAVAILABLE` typed by hand.

| Runtime descriptor ID | Runtime descriptor (`maturity` + `availability`) | Scope/platforms/risk | Required M checks | Evidence IDs/artifacts | Provider/model/backend | Limitations/failure behavior | Owner | Review/next review |
|---|---|---|---|---|---|---|---|---|
| `core.agent` | From live endpoint | | M01-M05, M15-M16, M24 | | | | | |
| `protocol.a2a` / `protocol.mcp` / `protocol.openai` | From live endpoint | | M15, M17-M18 | | | | | |
| `oauth.github` / `oauth.google` | From live endpoint | | M19, M21-M22 | | | | | |
| `client.android` / `client.ios` | From live endpoint | | Matrix only; M01-M24 as scoped | | | | | |
| `tool.<name>` | One row per live descriptor | | M07-M14, M21-M22 | | | | | |
| `provider.<id>` | One row per live descriptor | | M02-M04, M12, M15, M17, M20-M23 | | | | | |
| `channel.<id>` | One row per live descriptor; reconcile all 19 channel fixtures | | M19-M22 | | | | | |

The required channel fixture set is: Telegram, Discord, Slack, WhatsApp, Signal, Matrix, Email, SMS, Microsoft Teams, Mattermost, DingTalk, Feishu/Lark, WeCom/WeChat Work, LINE, QQ Bot, Weixin Official Account, BlueBubbles/iMessage, ntfy, and Home Assistant.

## Provider family coverage

These are required inventory rows/fixtures, not status claims. IDs must be reconciled with the live endpoint.

| Family | IDs to enumerate |
|---|---|
| LLM | `openrouter`, `openai`, `anthropic`, `google`, `deepseek`, `nvidia`, `ollama`, `litellm`, `kimi`, `xai` |
| Image | `openai-dalle3`, `google-imagen3`, `stability-ai`, `replicate-flux` |
| Vision | `openai-vision`, `google-gemini-vision`, `anthropic-claude-vision` |
| Embeddings | `openai-embed`, `google-embed`, `ollama-embed`, `cohere-embed`, `voyage-embed`, `mistral-embed` |
| Video generation | `google-veo3`, `replicate-luma`, `fal-minimax`, `runway-gen3` |
| Video analysis | `google-gemini-video`, `openai-vision-frames` |
| TTS | `gemini_tts`, `elevenlabs`, `cartesia`, `openai_tts` |
| STT | `deepgram`, `assemblyai`, `openai_stt`, `whisper`, `google_stt` (`UNAVAILABLE` in v1) |
| Real-time S2S | `gemini_live`, `openai_realtime`, `moshi` |
| Translate | `gemini_translate`, `openai_translate` |

For each configured entry retain provider/model/backend, configuration class, fixture, actual selected provider, output hash/format, cost/token usage, registry descriptor, and failure case. Registry-visible but unimplemented providers must retain their unavailable/not-implemented evidence.

For `kimi`, attach the `M03-K02`, `M03-K03`, `M12-K07`, `M15-K08`, `M17-K09`, `M17-K10`, and `M21-K11` evidence for `kimi-k3`. For `xai`, attach the same coverage for `grok-4.5` plus separate `M03-K04` Responses and `M03-K05` Chat Completions evidence. Both remain subject to the runtime maturity returned by the registry; passing these tests does not manually promote them beyond `BETA`.

## Budget certification

Attach a `token_budget` trace to M23 and any autonomous capability record:

| Preset | Tokens | Cost USD | Iterations | Duration | Retries/task |
|---|---:|---:|---:|---:|---:|
| `MINIMAL` | 10,000 | 0.10 | 10 | 5 min | 1 |
| `STANDARD` | 100,000 | 1.00 | 50 | 30 min | 2 |
| `EXTENDED` | 500,000 | 5.00 | 200 | 2 h | 3 |
| `UNLIMITED` | 0 | 0 | 0 | 0 | 5 |

Zero means unlimited. `STANDARD` is the default; `UNLIMITED` requires explicit selection and reviewer approval. Evidence must show the first enforced limit, clean stop, and no post-limit unsafe duplicate action.

## Evidence record template

Copy once per capability, target, provider/channel, and negative case:

| Field | Record |
|---|---|
| Capability ID and runtime descriptor | |
| Registry endpoint, raw snapshot, version, timestamp | |
| Runtime maturity and availability | |
| Exact scope, entry point, platforms, risk | |
| Build/commit, OS/arch, runtime, browser, Docker image | |
| Provider/model/backend and configuration class | |
| Input fixture/request and expected observable result | |
| Actual result, status/error class, output hash/format | |
| Manual check ID and PASS/FAIL/N/A disposition | |
| Budget preset, usage, and enforced limit | |
| Security negative case and authorization/origin decision | |
| Artifact IDs/location: request, response, log, screenshot, media, diff, backup | |
| Known limitations/exclusions and exception ID/expiry | |
| Owner, independent reviewer, decision | |
| Review date and next review date | |

## Publication gate and sign-off

Publish only when every live descriptor in scope has an evidence record, owner, current review date, and a status snapshot; every applicable M01-M24 check passes; all 19 channel fixtures and listed LLM/media/voice families are accounted for; budget and security negatives pass; and clean install, upgrade, migration, rollback, and recovery evidence is attached. `STABLE` requires independent review. `BETA` and `LAB` retain warnings. `UNAVAILABLE`, `MISSING_CONFIGURATION`, `DISABLED`, and `NOT_IMPLEMENTED` entries are excluded from availability claims.

| Role | Name | Scope reviewed | Decision | Signature/date |
|---|---|---|---|---|
| Capability owner | | | `ACCEPT / CONDITIONAL / REJECT` | |
| Security reviewer | | | `ACCEPT / CONDITIONAL / REJECT` | |
| Independent reviewer | | | `ACCEPT / CONDITIONAL / REJECT` | |
| Release owner | | | `ACCEPT / CONDITIONAL / REJECT` | |
