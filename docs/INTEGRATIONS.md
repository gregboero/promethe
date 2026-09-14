# External Service Integrations

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](EXPERIMENTAL_STATUS.md).

> Reference for the external service tools Promethe can expose to its agent loop: GitHub, Email,
> Google Calendar, Notion, Jira, Twilio, Slack, Discord, Signal, the web scraper, browser
> automation (CDP/Browserbase), and Home Assistant.

## Overview

Integration tools let the agent act on external services during its Perception–Raisonnement–Action
(PRA) loop — filing a GitHub issue, sending an email, querying a Jira board, posting to Slack,
driving a real browser, and so on. Unlike the 19 messaging **channels** documented in
`promethe/docs/CHANNELS.md` (which let *external users* talk to Promethe), integrations are
**tools the agent itself calls** to reach out to the world.

All integration tools are registered in one place:
`promethe/shared/src/jvmMain/kotlin/dev/promethe/core/IntegrationRegistrar.kt`, called from
`AgentBootstrap` at startup.

---

## Activation model

Registration is **conditional**: `IntegrationRegistrar.registerIntegrationTools()` reads config
keys via `ConfigProvider.get()` (backed by environment variables / runtime config) and only
registers a tool if its required key(s) are non-blank. If a key is missing, the tool is simply
never added to the `ToolRegistry` — the agent won't see it or attempt to call it, and no error is
raised at startup.

A few tools are **always registered** regardless of config, because they either need no
credentials or degrade gracefully on their own:

- `web_scrape` (`WebScraperTool`) — no API key required.
- `execute_code` (`CodeExecutionTool`) — registered only with
  `CODE_EXECUTION_ENABLED=true`, sandboxed via the configured exec backend, and
  approval-gated. Python, JavaScript and Kotlin are supported; shell scripts are
  intentionally unavailable.
- `signal` (`SignalTool`) and `discord` (`DiscordTool`) — always registered; they take their
  target URL/recipient as tool arguments (or, for Discord, fall back to `DISCORD_WEBHOOK_URL`) and
  fail per-call if unconfigured, rather than being gated at registration time.
- Media/AI tools (image generation, vision, TTS, video, embeddings, etc.) — always registered,
  routed through the multi-provider `CapabilityRouter`.

Everything else below is gated on the exact conditions quoted from `IntegrationRegistrar.kt`.

---

## GitHub

**Tool**: `github` (`GitHubTool`)
**Condition**: `GITHUB_TOKEN` is non-blank.

| Env var | Required |
|---|---|
| `GITHUB_TOKEN` | Yes |

**Capabilities** (`action` argument): `list_issues`, `create_issue`, `get_issue`, `list_prs`,
`create_pr`, `search_repos`, `search_code`, `list_actions`, `get_repo`. Calls the GitHub REST API
(`api.github.com`) with `Bearer` auth and API version `2022-11-28`.

---

## Email

**Tool**: `send_email` (`EmailTool`)
**Condition**: `EMAIL_API_KEY` and `EMAIL_FROM` are both non-blank.

| Env var | Required | Default |
|---|---|---|
| `EMAIL_API_KEY` | Yes | — |
| `EMAIL_FROM` | Yes | — |
| `EMAIL_PROVIDER` | No | `"resend"` |

**Important**: this is an **HTTP API** integration, not SMTP. `EmailTool` sends via one of three
provider APIs selected by `EMAIL_PROVIDER`: `resend` (default, `api.resend.com`), `sendgrid`
(`api.sendgrid.com`), or `mailgun` (`api.mailgun.net`, form-encoded with Basic auth). Any other
value falls back to Resend. This is distinct from the SMTP/IMAP-based email **channel**
(`EMAIL_SMTP_HOST`, etc.) described in `CHANNELS.md` — that's for receiving/replying to email as a
conversation channel; `send_email` is a one-shot outbound tool.

**Capabilities**: send plain-text or HTML email, with CC and reply-to.

---

## Google Calendar

**Tool**: `calendar` (`CalendarTool`)
**Condition**: `GOOGLE_CALENDAR_TOKEN` is non-blank.

| Env var | Required |
|---|---|
| `GOOGLE_CALENDAR_TOKEN` | Yes |

**Capabilities** (`action` argument): `list_events`, `create_event`, `get_event`, `delete_event`
against the Google Calendar v3 API (`www.googleapis.com/calendar/v3`). Supports attendees,
location, timezone, and a configurable `calendarId` (default `"primary"`).

---

## Notion

**Tool**: `notion` (`NotionTool`)
**Condition**: `NOTION_API_KEY` is non-blank.

| Env var | Required |
|---|---|
| `NOTION_API_KEY` | Yes |

**Capabilities** (`action` argument): `search`, `get_page`, `create_page`, `query_database`,
`get_database`, `append_blocks`. Uses the Notion API version `2022-06-28`.

---

## Jira

**Tool**: `jira` (`JiraTool`)
**Condition**: `JIRA_URL` and `JIRA_API_TOKEN` are both non-blank (`JIRA_EMAIL` is read but not
gated on).

| Env var | Required |
|---|---|
| `JIRA_URL` | Yes |
| `JIRA_EMAIL` | Read, used for Basic auth alongside the token — not itself gated |
| `JIRA_API_TOKEN` | Yes |

**Capabilities** (`action` argument): `search` (JQL), `get_issue`, `create_issue`, `transition`,
`add_comment`, `list_projects`. Uses Jira REST API v3 (`{JIRA_URL}/rest/api/3`) with Basic auth
(`email:token`, base64-encoded).

---

## Twilio (SMS / WhatsApp)

**Tool**: `twilio` (`TwilioTool`)
**Condition**: always registered; credentials are resolved for every invocation so values saved
from Settings apply without restarting the gateway.

| Env var | Required |
|---|---|
| `TWILIO_ACCOUNT_SID` | Yes |
| `TWILIO_AUTH_TOKEN` | Yes |
| `TWILIO_PHONE_NUMBER` | Yes for sending; used as the `From` number |

**Capabilities** (`action` argument): `send_sms`, `send_whatsapp`, `list_messages`, `get_message`.
WhatsApp messages are sent by prefixing both `From`/`To` numbers with `whatsapp:`. Supports
`mediaUrl` for MMS/WhatsApp image attachments.

Incoming SMS messages use the signed `POST /webhook/sms` endpoint and are routed through the
A2A agent loop. Configure the exact public HTTPS origin through `PUBLIC_BASE_URL`.

---

## Slack

**Tool**: `slack` (`SlackTool`)
**Condition**: `SLACK_TOKEN` is non-blank. (Registered in `registerExtendedTools`, alongside the
always-on `SignalTool` and `DiscordTool`.)

| Env var | Required |
|---|---|
| `SLACK_TOKEN` | Yes |

**Capabilities** (`action` argument): `send` (post a message to a channel), `list_channels`. Uses
the Slack Web API (`chat.postMessage`, `conversations.list`) with `Bearer` auth.

---

## Discord

**Tool**: `discord` (`DiscordTool`) — **always registered**, no gating condition in
`IntegrationRegistrar`.

**Capabilities**: post a message via a Discord webhook. The webhook URL is passed per-call as a
tool argument, or falls back to the `DISCORD_WEBHOOK_URL` config key if the argument is blank.
Supports a custom display `username` (default `"Promethe"`).

---

## Signal

**Tool**: `signal` (`SignalTool`) — **always registered**, no gating condition in
`IntegrationRegistrar`.

**Capabilities** (`action` argument): `send`, `receive`, via a
[`signal-cli-rest-api`](https://github.com/bbernhard/signal-cli-rest-api) instance. All connection
details (`apiUrl`, `sender`, `recipient`) are passed as tool arguments per call rather than fixed
env vars — the tool fails per-call with an `[ERROR]` string if a required argument is blank. See
`CHANNELS.md` §5 for setting up the underlying `signal-cli-rest-api` container.

---

## Web scraper

**Tool**: `web_scrape` (`WebScraperTool`) — **always registered**, no credentials needed.

**Capabilities** (`extract` argument): `text` (cleaned main content), `links`, `metadata`
(title/description/Open Graph tags), `full` (all of the above), `raw` (raw HTML). Works via plain
HTTP GET + regex-based HTML cleanup (script/style/nav/header/footer stripping, entity decoding) —
it does not execute JavaScript. For JS-rendered pages, use the browser automation tools instead.

---

## Home Assistant

**Tools**: `ha_list_entities`, `ha_get_state`, `ha_list_services`, `ha_call_service` (4 tools)
**Condition**: `HA_URL` is non-blank (checked in `IntegrationRegistrar`; the tools themselves also
require `HA_TOKEN` at call time via a shared `haConfig()` helper).

| Env var | Required |
|---|---|
| `HA_URL` | Yes (gates registration) |
| `HA_TOKEN` | Yes (checked per-call; a Long-Lived Access Token) |

**Capabilities**: list entities (optionally filtered by domain, e.g. `light`, `climate`), get an
entity's state/attributes, list available services, and call a service (e.g. `light.turn_on`)
against the Home Assistant Conversation/REST API.

---

## Browser automation

**Tools**: 11 registered `browser_*` tools, created by `BrowserTools.create(backend)` in
`promethe/shared/src/jvmMain/kotlin/dev/promethe/core/tools/builtin/BrowserTools.kt`:

Core 6 — `browser_navigate`, `browser_click`, `browser_type`, `browser_extract`,
`browser_screenshot`, `browser_eval`.
Extended 5 — `browser_scroll`, `browser_back`, `browser_press`, `browser_get_images`,
`browser_dialog`.

`browser_vision` is unavailable and intentionally absent from the tool registry until a configured VLM actually analyzes the screenshot.

**Condition**: a `BrowserBackend` must be constructed successfully. `IntegrationRegistrar` picks
one of two backends based on `BROWSER_BACKEND`:

```kotlin
val browserBackendType = ConfigProvider.get().get("BROWSER_BACKEND", "")
val browserBackend = when {
    browserBackendType == "browserbase" -> {
        val bbApiKey = ConfigProvider.get().get("BROWSERBASE_API_KEY", "")
        val bbProjectId = ConfigProvider.get().get("BROWSERBASE_PROJECT_ID", "")
        if (bbApiKey.isNotBlank() && bbProjectId.isNotBlank()) {
            BrowserbaseBrowserBackend(httpClient, bbApiKey, bbProjectId)
        } else null
    }
    else -> {
        val cdpPort = ConfigProvider.get().get("BROWSER_CDP_PORT", "")
        if (cdpPort.isNotBlank()) {
            val cdpHost = ConfigProvider.get().get("BROWSER_CDP_HOST", "localhost")
            CdpBrowserBackend(httpClient, cdpHost, cdpPort.toIntOrNull() ?: 9222)
        } else null
    }
}
browserBackend?.let { backend -> BrowserTools.create(backend).forEach { ToolRegistry.register(it) } }
```

| Backend | Env var | Required | Default |
|---|---|---|---|
| CDP (local, default path) | `BROWSER_CDP_PORT` | Yes (else no backend) | — |
| CDP (local) | `BROWSER_CDP_HOST` | No | `"localhost"` |
| Browserbase (cloud) | `BROWSER_BACKEND=browserbase` | Selects this backend | — |
| Browserbase (cloud) | `BROWSERBASE_API_KEY` | Yes | — |
| Browserbase (cloud) | `BROWSERBASE_PROJECT_ID` | Yes | — |

If `BROWSER_BACKEND` is anything other than `"browserbase"` (including unset), the registrar falls
through to the CDP path and requires `BROWSER_CDP_PORT`. If neither path yields a usable backend,
none of the 11 tools are registered.

For driving the Promethe Compose/WASM app itself with a browser agent (Playwright/CDP,
accessibility tree test tags, JS bridge functions like `prometheLogin`/`prometheNavigate`), see
`promethe/docs/BROWSER_TESTING.md` — that guide covers testing the app's own UI, not the
`browser_*` agent tools described here.

---

## Credentials storage

Configured integration secrets are persisted to **`~/.promethe/credentials.json`, in plaintext**,
alongside the rest of Promethe's runtime configuration (see `refactor: centralize all runtime data
in ~/.promethe/`). This file is:

- **Never checked into git** — it lives outside the repo, under the user's home directory.
- **Not encrypted at rest.** Anyone with filesystem access to the user's account can read every
  configured token in the clear.

If you are scripting around this file (backups, sync, container mounts), treat it exactly like a
`.env` full of secrets: **do not commit it, do not log its contents, and do not echo it in tool
output or error messages.**

---

## Limitations

- **No SMTP for `send_email`.** `EmailTool` only supports HTTP-API providers (Resend, SendGrid,
  Mailgun). SMTP/IMAP is a separate, channel-level concern (see `CHANNELS.md` §7) unrelated to this
  tool.
- **Registration is all-or-nothing per integration.** There's no partial/read-only mode — e.g. Jira
  requires both `JIRA_URL` and `JIRA_API_TOKEN` before the tool exists at all; a misconfigured
  integration is invisible to the agent rather than surfaced as a degraded capability.
- **`web_scrape` cannot execute JavaScript.** It's a regex-based HTML extractor over a plain HTTP
  GET. Client-side-rendered pages require the `browser_*` tools instead.
- **Signal and Discord take connection details as call-time arguments**, not fixed env-gated
  config, so misconfiguration surfaces as a per-call `[ERROR]` string rather than a missing tool.
- **Dangerous tool calls are gated by the approval system**, not by this registrar. Registration
  only controls *availability*; `write_file`, `execute_code`, `browser_eval`, `send_email`,
  `twilio`, etc. still go through `ApprovalGate` under `APPROVAL_MODE=dangerous` (the secure-by-
  default setting — see the root `CLAUDE.md`). This document does not cover how to loosen that
  gate, and you should not loosen it without an explicit, separate request.
- **No token refresh built in.** Tools like `calendar` (Google) and `signal`/HA expect long-lived
  or pre-refreshed tokens (`GOOGLE_CALENDAR_TOKEN`, `HA_TOKEN`); there's no OAuth refresh-flow
  logic in these tool classes.
