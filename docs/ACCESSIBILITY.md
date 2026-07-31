# Accessibility Guide

## Overview

Promethe uses **Compose Multiplatform semantics** to expose an accessibility tree to browsers and
screen readers. Because the WASM build renders entirely on a `<canvas>` element, standard DOM
accessibility APIs do not apply — instead, Compose publishes a **parallel semantics tree** that
Chrome's Accessibility engine can traverse.

## How It Works

### Compose WASM rendering pipeline

```
Compose UI → Canvas (pixels) → user sees app
         ↘ Semantics tree → browser / AT
```

- Every `Modifier.semantics { … }` block contributes a node to the parallel tree.
- Chrome DevTools → Accessibility panel reads this tree via `Accessibility.getFullAXTree`.
- Screen readers (NVDA, VoiceOver) receive the same tree via the OS accessibility bus.

### Key Compose APIs used

| API | Purpose |
|---|---|
| `Modifier.semantics { role = Role.Button }` | Announces the element as a button |
| `Modifier.semantics { contentDescription = "…" }` | Human-readable label for screen readers |
| `Modifier.semantics { stateDescription = "…" }` | Announced state for toggles/switches |
| `Modifier.testTag("…")` | Stable identifier for automated testing |
| `Icon(…, contentDescription = "…")` | Label for standalone icons |

## Patterns Used

### 1. Section Header (collapsible)

Every settings section header is a `Surface(onClick)` that expands/collapses content.

```kotlin
Surface(
    onClick = onClick,
    modifier = Modifier
        .fillMaxWidth()
        .semantics {
            role = Role.Button
            contentDescription = "$title - taper pour ${if (expanded) "Réduire" else "Ouvrir"}"
        },
) { … }
```

**Sections and their titles:**

| Section | contentDescription prefix |
|---|---|
| Profil Agent Actif | `Profil Agent Actif - taper pour …` |
| Serveur | `Serveur - taper pour …` |
| Fournisseur LLM | `Fournisseur LLM - taper pour …` |
| Paramètres agent | `Paramètres agent - taper pour …` |
| Mémoire | `Mémoire - taper pour …` |
| Base de Connaissances (RAG) | `Base de Connaissances (RAG) - taper pour …` |
| Exécution | `Exécution - taper pour …` |
| Observabilité | `Observabilité - taper pour …` |
| Sécurité | `Sécurité - taper pour …` |
| Accès distant | `Accès distant - taper pour …` |
| GEPA (Auto-évolution) | `GEPA (Auto-évolution) - taper pour …` |
| Intégrations & Channels | `Intégrations & Channels - taper pour …` |
| Avancé | `Avancé - taper pour …` |

### 2. Integration Card (collapsible within section)

```kotlin
Card(
    onClick = { expanded = !expanded },
    modifier = Modifier
        .fillMaxWidth()
        .semantics {
            role = Role.Button
            contentDescription = "$title - ${if (expanded) "Réduire" else "Déplier"}"
        },
) { … }
```

### 3. Password / API Key visibility toggle

```kotlin
IconButton(onClick = { keyVisible = !keyVisible }) {
    Icon(
        if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
        contentDescription = if (keyVisible) "Masquer la clé API" else "Afficher la clé API",
    )
}
```

### 4. Switch / Toggle

```kotlin
Switch(
    checked = state.memoryEnabled,
    onCheckedChange = { … },
    modifier = Modifier
        .testTag("settings_memory_toggle")
        .semantics {
            role = Role.Switch
            contentDescription = "Mémoire automatique"
            stateDescription = if (state.memoryEnabled) "Activé" else "Désactivé"
        },
)
```

**All switches in the app:**

| Switch | contentDescription |
|---|---|
| Auto Memory Extraction | `Mémoire automatique` |
| Enable GEPA | `Activer GEPA` |
| Auto-Apply Mutations | `Auto-application des mutations` |
| Activer le RAG | `Activer le RAG` |

### 5. Save / Action buttons

Buttons have both `testTag` (for testing) and `contentDescription` (for AT):

```kotlin
Button(
    modifier = Modifier
        .testTag("settings_save")
        .semantics { contentDescription = "Sauvegarder les paramètres" },
) { … }
```

### 6. Provider selection card (LLM)

```kotlin
Surface(
    modifier = Modifier
        .testTag("settings_provider_${info.key}")
        .semantics {
            role = Role.Button
            contentDescription = "Provider ${info.name}${if (isSelected) " - sélectionné" else ""}"
        }
        .clickable { … },
) { … }
```

## Testing

### Chrome DevTools (WASM)

1. Open `http://localhost:56708` in Chrome
2. Open DevTools → **Accessibility** tab
3. Check **Enable full-page accessibility tree**
4. Inspect any element — you will see `name`, `role`, and `description`

### Programmatic testing with testTags

All interactive elements have a `testTag`. Use these in end-to-end tests:

```kotlin
// Compose UI test
composeTestRule.onNodeWithTag("settings_save").assertIsEnabled().performClick()
composeTestRule.onNodeWithTag("settings_memory_toggle").assertIsToggleable()
```

## Full testTag Reference

### Settings Screen

| testTag | Element |
|---|---|
| `settings_save` | Global Save Settings button |
| `settings_approval_mode` | Tool approval mode segmented button |
| `settings_approval_timeout` | Approval timeout slider |
| `settings_remote_user` | Remote access username field |
| `settings_remote_password` | Remote access password field |
| `settings_remote_password_confirm` | Remote access password confirm field |
| `settings_remote_save` | Save remote access button |
| `settings_gateway_url` | Gateway URL field |
| `settings_llm_api_key` | LLM API key field |
| `settings_ollama_url` | Ollama/local URL field |
| `settings_test_connection` | Test LLM connection button |
| `settings_provider_{key}` | Provider card (openai, anthropic, google, etc.) |
| `settings_refresh_models` | Refresh model list button |
| `settings_temperature` | Temperature slider |
| `settings_max_tokens` | Max tokens field |
| `settings_max_iterations` | Max iterations slider |
| `settings_edit_profiles` | Edit agent profiles button |
| `settings_memory_toggle` | Memory auto-extraction switch |
| `settings_memory_provider` | Memory provider segmented button |
| `settings_honcho_url` | Honcho base URL field |
| `settings_honcho_api_key` | Honcho API key field |
| `settings_tencent_url` | Tencent Memory URL field |
| `settings_tencent_service_id` | Tencent Service ID field |
| `settings_tencent_api_key` | Tencent API key field |
| `settings_rag_toggle` | RAG enable switch |
| `settings_execution_backend` | Execution backend segmented button |
| `settings_execution_timeout` | Execution timeout slider |
| `settings_max_output_bytes` | Max output bytes field |
| `settings_docker_image` | Docker image field |
| `settings_ssh_host` | SSH host field |
| `settings_ssh_user` | SSH user field |
| `settings_ssh_port` | SSH port field |
| `settings_ssh_key_path` | SSH key path field |
| `settings_tracing_backend` | Tracing backend segmented button |
| `settings_langfuse_public_key` | Langfuse public key |
| `settings_langfuse_secret_key` | Langfuse secret key |
| `settings_langfuse_host` | Langfuse host |
| `settings_otlp_endpoint` | OTLP endpoint |
| `settings_gepa_toggle` | GEPA enable switch |
| `settings_gepa_interval` | GEPA interval slider |
| `settings_gepa_auto_apply` | GEPA auto-apply switch |
| `settings_github_token` | GitHub token field |
| `settings_jira_url` | Jira URL field |
| `settings_jira_email` | Jira email field |
| `settings_jira_token` | Jira token field |
| `settings_notion_key` | Notion API key field |
| `settings_email_api_key` | Email API key field |
| `settings_email_provider` | Email provider field |
| `settings_twilio_sid` | Twilio Account SID field |
| `settings_twilio_auth` | Twilio Auth Token field |
| `settings_twilio_phone` | Twilio phone number field |
| `settings_telegram_token` | Telegram bot token field |
| `settings_telegram_secret` | Telegram secret token field |
| `settings_discord_token` | Discord bot token field |
| `settings_discord_public_key` | Discord application public key |
| `settings_slack_token` | Slack bot token field |
| `settings_slack_signing_secret` | Slack signing secret field |
| `settings_whatsapp_phone_id` | WhatsApp phone number ID |
| `settings_whatsapp_token` | WhatsApp access token |
| `settings_signal_url` | Signal REST API URL |
| `settings_signal_phone` | Signal phone number |
| `settings_matrix_homeserver` | Matrix homeserver URL |
| `settings_matrix_token` | Matrix access token |
| `settings_theme` | Theme segmented button |
| `settings_profile_dropdown` | Active profile dropdown |
| `settings_manage_profiles` | Manage profiles link |

### Setup Wizard

| testTag | Element |
|---|---|
| `setup_step_0` … `setup_step_5` | Current step content area |
| `setup_back` | Back button |
| `setup_next` | Next / Launch button |
| `setup_api_key` | API key field (step 1) |
| `setup_remote_user` | Remote username (step 2) |
| `setup_remote_password` | Remote password (step 2) |
