# Browser Agent Testing Guide

How to navigate and test the Promethe WASM app with a browser agent (Playwright, Puppeteer,
Chrome DevTools Protocol, etc.).

---

## Quick Start

### 1. Start the app

```bash
# From the project root
./gradlew :composeApp:wasmJsBrowserRun
# App available at http://localhost:56708
```

### 2. Login via JS bridge

```javascript
// In browser console or via CDP
await window.prometheLogin('http://localhost:8080', 'admin', 'yourpassword')
// Page reloads automatically — wait for page to finish loading
```

### 3. Navigate to a screen

```javascript
window.prometheNavigate('agents')
```

### 4. Check current state

```javascript
window.prometheStatus()
// { loggedIn: true, route: "agents", apiKey: "...", gatewayUrl: "..." }
```

---

## Screen Navigation

| Route | URL hash | Description |
|---|---|---|
| `sessions` | `#sessions` | Chat session list — create and resume conversations |
| `agents` | `#agents` | Agent profiles — configure provider, model, tools per agent |
| `monitor` | `#monitor` | Real-time agent execution monitor |
| `stats` | `#stats` | Usage statistics, token counts, cost charts |
| `memory` | `#memory` | Conversation memory — facts extracted automatically |
| `gepa` | `#gepa` | GEPA dashboard — autonomous prompt evolution |
| `settings` | `#settings` | All configuration sections |
| `tools` | `#tools` | Tools catalogue — available functions per agent |
| `channels` | `#channels` | Messaging channels — Telegram, Slack, Discord, etc. |
| `scheduler` | `#scheduler` | Scheduled task management |
| `mcp` | `#mcp` | MCP server management (Model Context Protocol) |
| `plugins` | `#plugins` | Plugin management |
| `knowledge` | `#knowledge` | RAG knowledge base — upload and index documents |
| `orchestrator` | `#orchestrator` | Multi-agent orchestration workflows |

---

## Scrolling

Compose WASM renders on a `<canvas>` element — there are no native DOM scrollbars.
Use the JS bridge scroll functions instead of `window.scrollBy()` or DOM wheel events.

### `prometheScroll(deltaY, x?, y?)`

Dispatches a `WheelEvent` directly to the Compose root. Instant, single event.

```javascript
window.prometheScroll(300)    // scroll down 300px
window.prometheScroll(-200)   // scroll up 200px
```

### `prometheScrollTo(totalDeltaY, steps?, delayMs?)`

Smooth scroll — splits the distance into steps so Compose can re-render between frames.

```javascript
// Scroll down 600px smoothly (6 steps × 100ms)
await window.prometheScrollTo(600, 6, 100)

// Scroll back to top
await window.prometheScrollTo(-2000)
```

### Practical example — Settings screen

```javascript
window.prometheNavigate('settings')
await new Promise(r => setTimeout(r, 1000))   // wait for render

// Scroll down to find "Sécurité" section
await window.prometheScrollTo(400)

// Scroll down to find "Accès distant" section
await window.prometheScrollTo(600)

// Scroll back to top
await window.prometheScrollTo(-2000, 10, 80)
```

---


## Accessibility Tree

Promethe uses Compose Multiplatform semantics to expose a parallel accessibility tree.
Because the app renders on `<canvas>`, standard CSS selectors do not work.
**Use the accessibility tree** for reliable element targeting.

### Read the tree in Chrome DevTools

1. Open DevTools → **Accessibility** tab
2. Enable **Full-page accessibility tree**
3. Hover over elements in the tree to highlight them on canvas

### Query via CDP (Playwright example)

```python
import asyncio
from playwright.async_api import async_playwright

async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page()
        await page.goto('http://localhost:56708')

        # Login
        await page.evaluate("""
            window.prometheLogin('http://localhost:8080', 'admin', 'pass')
        """)
        await page.wait_for_load_state('load')

        # Navigate to settings
        await page.evaluate("window.prometheNavigate('settings')")
        await page.wait_for_timeout(500)

        # Get accessibility tree
        snapshot = await page.accessibility.snapshot()
        print(snapshot)

asyncio.run(main())
```

---

## Test Tags Reference

All interactive elements in the Promethe WASM app have a stable `testTag` that appears in the
accessibility tree as the element's `name` attribute. Use these to target elements reliably.

### Authentication (Login Screen)

| testTag | Element |
|---|---|
| *(no testTags — use JS bridge to login)* | — |

### Setup Wizard (First Run)

| testTag | Element |
|---|---|
| `setup_step_0` | Provider selection step |
| `setup_step_1` | Credentials step |
| `setup_step_2` | Remote access step |
| `setup_step_3` | Project context step |
| `setup_step_4` | Integrations step |
| `setup_step_5` | Confirm step |
| `setup_back` | Back button |
| `setup_next` | Next / Launch button |
| `setup_api_key` | API key input field |
| `setup_remote_user` | Remote access username |
| `setup_remote_password` | Remote access password |

### Settings — Server

| testTag | Element |
|---|---|
| `settings_gateway_url` | Gateway URL field |

### Settings — LLM Provider

| testTag | Element |
|---|---|
| `settings_provider_openai` | OpenAI provider card |
| `settings_provider_anthropic` | Anthropic provider card |
| `settings_provider_google` | Google provider card |
| `settings_provider_deepseek` | DeepSeek provider card |
| `settings_provider_openrouter` | OpenRouter provider card |
| `settings_provider_litellm` | LiteLLM provider card |
| `settings_provider_nvidia` | NVIDIA provider card |
| `settings_provider_ollama` | Ollama provider card |
| `settings_llm_api_key` | LLM API key field |
| `settings_ollama_url` | Local provider URL field |
| `settings_test_connection` | Test connection button |

### Settings — Agent Parameters

| testTag | Element |
|---|---|
| `settings_refresh_models` | Refresh model list button |
| `settings_temperature` | Temperature slider |
| `settings_max_tokens` | Max tokens field |
| `settings_max_iterations` | Max iterations slider |
| `settings_edit_profiles` | Edit agent profiles button |

### Settings — Memory

| testTag | Element |
|---|---|
| `settings_memory_toggle` | Memory auto-extraction switch |
| `settings_memory_provider` | Memory provider selector |
| `settings_honcho_url` | Honcho base URL |
| `settings_honcho_api_key` | Honcho API key |
| `settings_tencent_url` | Tencent Memory URL |
| `settings_tencent_service_id` | Tencent Service ID |
| `settings_tencent_api_key` | Tencent API key |

### Settings — RAG

| testTag | Element |
|---|---|
| `settings_rag_toggle` | RAG enable switch |

### Settings — Execution

| testTag | Element |
|---|---|
| `settings_execution_backend` | Backend selector (Local/Docker/SSH) |
| `settings_execution_timeout` | Execution timeout slider |
| `settings_max_output_bytes` | Max output bytes field |
| `settings_docker_image` | Docker image field |
| `settings_ssh_host` | SSH host |
| `settings_ssh_user` | SSH user |
| `settings_ssh_port` | SSH port |
| `settings_ssh_key_path` | SSH key path |

### Settings — Security

| testTag | Element |
|---|---|
| `settings_approval_mode` | Tool approval mode selector |
| `settings_approval_timeout` | Approval timeout slider |
| `settings_remote_user` | Remote access username |
| `settings_remote_password` | Remote access password |
| `settings_remote_password_confirm` | Password confirmation |
| `settings_remote_save` | Save remote access button |

### Settings — Observability

| testTag | Element |
|---|---|
| `settings_tracing_backend` | Tracing backend selector |
| `settings_langfuse_public_key` | Langfuse public key |
| `settings_langfuse_secret_key` | Langfuse secret key |
| `settings_langfuse_host` | Langfuse host URL |
| `settings_otlp_endpoint` | OTLP endpoint |

### Settings — GEPA

| testTag | Element |
|---|---|
| `settings_gepa_toggle` | GEPA enable switch |
| `settings_gepa_interval` | Optimization interval slider |
| `settings_gepa_auto_apply` | Auto-apply mutations switch |

### Settings — Integrations

| testTag | Element |
|---|---|
| `settings_github_token` | GitHub personal access token |
| `settings_jira_url` | Jira/Atlassian URL |
| `settings_jira_email` | Jira account email |
| `settings_jira_token` | Jira API token |
| `settings_notion_key` | Notion API key |
| `settings_email_api_key` | Email provider API key |
| `settings_email_provider` | Email provider name |
| `settings_twilio_sid` | Twilio Account SID |
| `settings_twilio_auth` | Twilio Auth Token |
| `settings_twilio_phone` | Twilio phone number |
| `settings_telegram_token` | Telegram bot token |
| `settings_telegram_secret` | Telegram webhook secret |
| `settings_discord_token` | Discord bot token |
| `settings_discord_public_key` | Discord application public key |
| `settings_slack_token` | Slack bot token |
| `settings_slack_signing_secret` | Slack signing secret |
| `settings_whatsapp_phone_id` | WhatsApp Cloud phone number ID |
| `settings_whatsapp_token` | WhatsApp Cloud access token |
| `settings_signal_url` | Signal REST API URL |
| `settings_signal_phone` | Signal phone number |
| `settings_matrix_homeserver` | Matrix homeserver URL |
| `settings_matrix_token` | Matrix access token |

### Settings — Advanced & Global

| testTag | Element |
|---|---|
| `settings_theme` | Theme selector (Auto/Dark/Light) |
| `settings_profile_dropdown` | Active agent profile dropdown |
| `settings_manage_profiles` | Manage profiles link |
| `settings_save` | **Global** Save Settings button |

---

## Common Testing Patterns

### Wait for the app to be ready

```javascript
// Poll until bridge is available
async function waitForApp(timeout = 10000) {
    const start = Date.now();
    while (Date.now() - start < timeout) {
        if (typeof window.prometheLogin === 'function') return true;
        await new Promise(r => setTimeout(r, 200));
    }
    throw new Error('App not ready');
}
```

### Navigate and verify a screen

```javascript
window.prometheNavigate('settings');
// Allow Compose animation to complete
await new Promise(r => setTimeout(r, 500));
const status = window.prometheStatus();
console.assert(status.route === 'settings');
```

### Full test session skeleton

```javascript
// 1. Ensure app is loaded
await waitForApp();

// 2. Login
await window.prometheLogin('http://localhost:8080', 'admin', 'pass');
await page.waitForLoadState('load'); // Playwright

// 3. Verify login
const s = window.prometheStatus();
console.assert(s.loggedIn);

// 4. Test settings
window.prometheNavigate('settings');
await page.waitForTimeout(500);

// 5. Interact via accessibility tree (Playwright)
const saveBtn = page.getByRole('button', { name: /Sauvegarder les paramètres/ });
await saveBtn.click();

// 6. Cleanup
window.prometheLogout();
```
