# Desktop Interface Guide

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](EXPERIMENTAL_STATUS.md).

> User guide to the screens of the Compose Multiplatform app (Desktop/Web/Android/iOS).

## Overview

The Promethe app (`composeApp/`) is a single Compose Multiplatform codebase targeting
Desktop, Web (wasmJs), Android, and iOS. It ships one unified entry point (`App.kt`) that
renders one of **18 screens** depending on navigation state, plus two full-screen flows
(Setup, Login) shown before the main app when needed.

## How Navigation Works

`App.kt` owns a single `MutableList<PrometheRoute>` back stack (Navigation 3 style — the
list itself *is* the navigation state, restored from and persisted to local storage on every
change). The visible screen is whatever route sits on top of the stack; `NavDisplay` renders
it with a slide + fade transition (300ms) in both directions.

Two layouts adapt to viewport width (breakpoint: 600.dp):

- **Wide layout** (desktop, tablet landscape) — a `NavigationRail` sidebar (`testTag: sidebar`)
  on the left with 14 top-level destinations, icon + label, grouped with subtle dividers after
  items 3, 7, and 11 (Chat/Agents/Channels/Monitor · Scheduler/MCP/Plugins/RAG ·
  Multi-Agent/Stats/Memory/GEPA · Skills/Settings).
- **Narrow layout** (mobile/small window) — a bottom `NavigationBar` showing only 5 key
  destinations: Chat, Agents, Monitor, Channels, Settings.

Clicking a nav item clears the back stack and pushes that route (`navigateToTab`), so nav
items behave like top-level tabs rather than a push stack; drilling into a session (Chat)
or a dialog still pushes normally and Escape/back pops it.

### Keyboard shortcuts (Desktop)

| Shortcut | Action |
|---|---|
| `Ctrl+1`…`Ctrl+9` | Jump to the Nth sidebar tab |
| `Ctrl+N` | New session (navigates to Chat/Sessions) |
| `Ctrl+R` | Refresh the current tab (re-navigate) |
| `Escape` | Go back (pop back stack) |
| `Ctrl+S` | Save (in Settings and the Agent Profile editor) |

### The 18 screens

| Group | Screens |
|---|---|
| Setup & Auth | SetupScreen, LoginScreen |
| Chat | ChatScreen, SessionsScreen, GoalScreen |
| Agents | AgentProfilesScreen, AgentMonitorScreen, OrchestratorScreen |
| Knowledge | MemoryScreen, KnowledgeScreen, SkillsScreen |
| Configuration | SettingsScreen, ToolsScreen, McpScreen, PluginsScreen, ChannelsScreen, SchedulerScreen, StatsScreen |
| Optimization | GepaDashboard |

Note: `GoalScreen` (Mode Autonome) exists in the codebase but is **not wired into the sidebar
or the `NavDisplay` route table** in `App.kt` — there is no `PrometheRoute` that renders it
today. `GepaDashboard` is the composable that actually backs the sidebar's "GEPA" tab
(`PrometheRoute.Gepa`); it is documented under Optimization below.

---

## Setup & Auth

### SetupScreen

First-run configuration wizard, shown when `~/.promethe/credentials.json` is missing or empty
(`isFirstRun = true`). A 6-step Crossfade wizard (`testTag: setup_step_0`…`setup_step_5`) with
a step indicator and Back/Next navigation:

0. **Provider** — pick the LLM provider.
1. **Credentials** — API key / local model URL for the chosen provider.
2. **Remote Access** — optional username + password for remote/browser login.
3. **Project Context** — context files to seed (`SOUL.md`, `AGENTS.md`, etc.).
4. **Integrations** — optional tokens for external services (saved best-effort).
5. **Confirm** — review before launch.

Main actions: **Retour** (`testTag: setup_back`, hidden on step 0), **Suivant / Lancer
Promethe** (`testTag: setup_next`, becomes "Lancer Promethe" with a rocket icon on the last
step). The Next button is disabled until `state.canProceed` is true for the current step.
Completing the wizard calls `onSetupComplete` with provider, key, model, local URL, context
files, and remote credentials, then hands off to the main app.

### LoginScreen

Shown instead of the main app when no API key is saved and it isn't first run — i.e. for
**remote devices** connecting to an existing gateway (a second desktop client, the Web/wasmJs
build, mobile). Fields: Gateway URL (`testTag: login_gateway_url`, defaults to
`http://localhost:8080`), Username (`login_username`), Password with show/hide toggle
(`login_password`). **Connect** button (`login_connect_btn`) or Enter key submits; a login
error banner (`login_error`) surfaces 401 (bad credentials), 403 (remote access not
configured on that gateway), or connection failures. On success, credentials are persisted
locally via `CredentialManager`.

**Prerequisite**: the target gateway must have remote access configured (username/password set
via Setup step 2 or Settings > Accès distant) — otherwise login returns 403.

---

## Chat

### ChatScreen

The conversation view for a single session (`sessionId` passed via `PrometheRoute.Chat`).
Loads history on entry, then streams responses over `chatStream`, falling back to a REST
call if the stream fails.

Main actions:
- **Agent selector** (`chat_agent_selector`) — a dropdown of configured Agent Profiles;
  the choice is persisted per-session in local storage. System profiles show a 🔒 marker.
- **Message input** (`chat_input`) — multi-line field; Enter sends, Shift+Enter inserts a
  newline. **Send** button (`chat_send_btn`) is disabled while streaming or empty.
- **Retry** — a failed user message shows a Retry button that resends the exact original text.
- **Feedback bar** — thumbs-style feedback shown under agent responses, posted via
  `submitFeedback`.
- **Reasoning blocks** — consecutive `thought`/`action`/`observation` events are grouped
  into a collapsible `AgentReasoningBlock` instead of separate bubbles.
- **Scroll-to-bottom FAB** — appears once the user has scrolled away from the latest message.
- **Voice controls** (conditional on gateway config):
  - STT dictation mic (`voice_stt_btn`) — shown if `voice_stt_enabled`; dictated text is
    injected into the input field rather than sent as a message.
  - S2S "agent vocal" button (`voice_s2s_btn`, flame icon) — shown if `voice_s2s_enabled`;
    opens a live voice session overlay (mute, echo-suppression toggle, end session) instead
    of text chat.
  - TTS playback — if `voice_tts_enabled`, message bubbles can be spoken aloud via
    `postTtsSynthesize`.

### SessionsScreen

The session list / home screen (`PrometheRoute.Sessions`, default landing route). Main actions:
- **Search** — toggled via the search icon; filters sessions by title as you type
  (`sessions_search`).
- **New session** FAB (`sessions_new`) — creates a session and navigates straight into it.
- **Open a session** — tap any card (`sessions_card_{id}`) to open ChatScreen.
- **Delete a session** — swipe a card end-to-start to reveal a red delete background and
  remove it.
- **Refresh** — reloads the list from the gateway.

Empty states differentiate "no sessions yet" from "no matching sessions" (when a search
filter yields nothing), and a dedicated error state offers **Retry** if the server can't be
reached.

### GoalScreen

"Mode Autonome" — lets the user hand the agent a complex goal that it decomposes into tasks
and executes unattended. Main actions: a multi-line **Objectif** field, a **Budget** selector
(Minimal / Standard / Étendu chips), **Lancer** (starts the goal) / **Arrêter** (cancels a
running goal) buttons. While running, a status card shows the current phase (Décomposition,
Exécution, Terminé, Échoué, Arrêté, Budget dépassé), a progress bar, elapsed time, token count,
and per-task result cards with success/failure/skipped icons.

**Not currently reachable from the sidebar or bottom nav** — see the note under
"The 18 screens" above; the composable exists and is functional but has no route wired into
`App.kt`'s `NavDisplay`.

---

## Agents

### AgentProfilesScreen

CRUD for **Agent Profiles** — the named provider/model/prompt/tool/skill bundles selectable
from the Chat agent selector and used by Scheduler/Orchestrator. Main actions:
- **Nouveau Profil** (`agents_create_btn`) opens the profile editor dialog; **Actualiser**
  (`agents_refresh`) reloads the grid.
- Each card (`agents_card_{id}`) shows provider color dot, name, 🔒 SYSTÈME badge for
  built-in profiles, ÉPHÉMÈRE badge where applicable, provider/model/temperature/max-iterations
  chips, assigned tools and skills, and a system-prompt preview.
- Per-card actions: **Dupliquer** (copy icon), **Modifier** (edit icon, opens the editor),
  **Supprimer** (delete icon, `agents_delete_{id}` — hidden for system profiles, which cannot
  be deleted).
- **Profile editor dialog**: ID (new profiles only) and Name fields, Provider dropdown
  restricted to providers with a configured API key (or none required), a Model dropdown
  fetched live from the provider (with a manual **Rafraîchir les modèles** refresh), a
  System Prompt field with **Éditer / Aperçu MD** tabs (live Markdown preview), multi-select
  chip pickers for allowed Tools and assigned Skills, a Temperature slider (0–2), and a Max
  Iterations field. **Ctrl+S** inside the dialog saves; **Enregistrer/Créer**
  (`agents_save`) and **Annuler** (`agents_cancel`) buttons do the same.

### AgentMonitorScreen

Real-time, three-pane view into running/idle agent execution (`PrometheRoute.Monitor`):
- **Left pane** — agent list (`monitor_list`) with a status dot (running/idle/error); click
  to select.
- **Center pane** — execution timeline for the selected agent: tool calls, observations,
  and errors, each with an icon, timestamp, tool name, and content preview.
- **Right pane** — three stacked panels:
  - **Pending Approvals** — shown when the security gate has queued a dangerous tool call
    for human review; each entry shows the tool name and args with **Approve**/**Reject**
    buttons. This is the human-in-the-loop control point for the `APPROVAL_MODE=dangerous`
    default (see `docs/SECURITY.md`) — approving/rejecting here is the intended way to let
    guarded actions (e.g. `execute_code`, `write_file`) proceed or be blocked.
  - **Provider Choice** — shown when the agent needs the user to pick which configured LLM
    provider should handle a capability (e.g. vision); presents a suggested provider plus
    alternatives, or **Annuler**.
  - **Agent Details** — name, ID, status, tokens used, current step, start/last-activity
    timestamps for the selected agent.

### OrchestratorScreen

Multi-agent delegation dashboard (`PrometheRoute.Orchestrator`, sidebar label "Multi-Agent").
Shows Actifs/Terminés/Total counters and a list of sub-agents spawned via delegation. Main
actions:
- **Déléguer** (`orchestrator_create_btn`) opens a dialog to enter a **Tâche** (required) and
  an optional **Profil** id, then **Déléguer** (`orchestrator_delegate_confirm`) launches the
  sub-agent.
- Toggle auto-refresh via the icon button (`orchestrator_refresh_toggle`).
- Each sub-agent card (`orchestrator_agent_{sessionId}`) shows profile id, truncated session
  id, status badge (Running/Success/Failed), duration, task text, and — once finished — a
  response preview. Running sub-agents expose a **Cancel** button
  (`orchestrator_cancel_{sessionId}`).

---

## Knowledge

### MemoryScreen

Browse and manage the agent's 4-tier memory facts (`PrometheRoute.Memory`). Header shows the
active memory provider name and total fact count. Main actions:
- **Search** field (`memory_search`) + **Search** button (`memory_search_btn`) to query facts
  by content.
- Each fact card (`memory_fact_{id}`) shows a category chip (preference/project/personal/
  technical), a confidence percentage, the fact text, its memory tier, and a **delete**
  button (`memory_delete_{id}`).
- Empty state prompts the user to chat with the agent to build up memory.

See `docs/CONFIGURATION.md` for configuring which memory provider (Embedded/Honcho/Tencent)
backs this screen.

### KnowledgeScreen

The RAG (Retrieval-Augmented Generation) knowledge base (`PrometheRoute.Knowledge`, sidebar
label "RAG"). Header shows document/chunk counts and two connectivity badges (Embedding,
Stockage) reflecting whether the embedding provider and vector store are reachable. Main
actions:
- **Recherche sémantique** search bar (`rag_search`) + **Chercher** button (`rag_upload_btn`
  — despite the tag name, this triggers search, not upload) returns scored passages
  (`SearchResultCard`, source file + heading + relevance %).
- A **Configuration** card (`rag_config`) reports the embedding provider/model, vector store
  type, and total chunk count.
- **Documents ingérés** list — each document card (`rag_document_{id}`) shows filename, chunk
  count, approximate token count, and a **Supprimer** button (`rag_delete_{id}`).

Note: this screen has no visible "add/upload document" action in the composable itself —
ingestion happens elsewhere (API/CLI); the search bar's button only searches.

### SkillsScreen

Two-pane skill library manager (`PrometheRoute.Skills`). Left pane: a searchable-by-scroll
list (`skills_list`) of skills with name, 🔒 SYSTEM badge where applicable, and description
preview; a **+** FAB (`skills_create_btn`) opens the Architect dialog to create a new skill.
Right pane (when a skill is selected, `skills_editor`):
- Read-only view renders the skill's Markdown content.
- **Architecte** button re-opens the AI-assisted Architect dialog scoped to *improving* the
  selected skill.
- **Edit** button switches to a raw Markdown text editor with **Cancel**/**Save**
  (`skills_save_btn`).
- **Delete** (trash icon on the list card, disabled for system skills) opens a confirmation
  dialog before removing the skill.

**Skill Architect dialog** — a mini-chat flow for authoring or revising a skill
conversationally: prompts for name and description on creation (`ArchitectPhase.CollectName`
/ `CollectDescription`), then free-form refinement messages (`architect_input`,
`architect_send`). When the draft is ready (`ArchitectPhase.ReadyToValidate`), edits show a
line-by-line diff viewer (additions/removals color-coded) before **Valider et sauvegarder**
(`architect_validate`) commits it. See `docs/SKILLS.md` for the skill file format and the
`SkillWriter`/`SkillCurator` background processes this UI front-ends.

---

## Configuration

### SettingsScreen

A single scrollable form (`PrometheRoute.Settings`) composed of ~18 collapsible sections
(each a `SectionHeader` that expands/collapses — see `docs/ACCESSIBILITY.md` for the full
`contentDescription`/testTag reference): Serveur, Fournisseur LLM, Mémoire, RAG, Exécution,
Fenêtre de contexte, Observabilité, Sécurité, GEPA, Intégrations & Channels, Recherche Web,
Automatisation Navigateur, Home Assistant, Personnalité, Fichiers de contexte, Avancé. Notable
sections:

- **Fournisseur LLM** — provider selection cards, API key field (with visibility toggle),
  Ollama/local URL, model dropdown with **Rafraîchir les modèles**, temperature and max-tokens
  controls, and **Test Connection** (`settings_test_connection`).
- **Sécurité** — Tool Approval Mode segmented control (`settings_approval_mode`: Auto /
  Dangerous Only / All Tools) with an inline explanation of the selected mode, an Approval
  Timeout slider (`settings_approval_timeout`, auto-rejects unanswered approvals). This is the control surface for the approval gate
  described in `docs/SECURITY.md` — the shipped default (`dangerous`) requires human sign-off
  for tools like `execute_code`/`write_file`/`send_email`; the mandatory security set remains gated
  even in `auto` mode. It also hosts **Accès distant** (single remote-owner username/password for Login/WASM clients — `settings_remote_user`,
  `settings_remote_password`, `settings_remote_save`).
- **Exécution** — execution backend selector (`settings_execution_backend`: e.g. `docker` vs
  `local`), timeout, max output bytes, Docker image, and SSH backend fields. The sandboxed
  `docker` backend is the intended default per `docs/SECURITY.md`.
- **Intégrations & Channels** — per-integration credential fields (GitHub, Jira, Notion, email,
  Twilio) and per-channel credential fields for all 19 messaging channels (Telegram, Discord,
  Slack, WhatsApp, Signal, Matrix, …) — see `docs/CHANNELS.md` for what each channel's fields
  mean and how to obtain them.
- **GEPA** — enable switch (`settings_gepa_toggle`), optimization interval slider, and
  auto-apply-mutations switch.

Global actions: **Save Settings** (`settings_save`, or `Ctrl+S` anywhere in the form) persists
everything to `~/.promethe/credentials.json`; a confirmation ("Saved ✓") or error message is
shown inline. The Remote Access sub-section has its own independent **Sauvegarder l'accès
distant** save action.

### ToolsScreen

Read-only catalog of every tool available to agents (`PrometheRoute.Tools` — reachable from
the MCP screen's "Outils" tab, not from the sidebar directly). Main actions: **Search** toggle
(`tools_search`) filters by name/description; tools are grouped by source ("Built-in" vs an
MCP server name) with a Badge per card showing that source. Empty state suggests connecting
MCP servers to discover more tools.

### McpScreen

Tabbed screen (`PrometheRoute.Mcp`) combining MCP server management and the tool catalog:
- **Serveurs MCP** tab — lists registered servers (`mcp_list`) with connection status
  (Connected/Disconnected badge), transport, and tool count; expandable per-server tool list.
  **Connect**/**Disconnect** icon buttons (`mcp_connect_{id}` / `mcp_disconnect_{id}`) toggle
  a server's connection. **+** FAB (`mcp_add_btn`) opens **Register MCP Server**: name,
  transport choice (stdio vs SSE), and either a Command (stdio) or URL (SSE) field.
- **Outils** tab — the same tool catalog as ToolsScreen, with its own search toggle.

### PluginsScreen

Read-only listing of installed plugins (`PrometheRoute.Plugins`) discovered from the
`plugins/` folder. Each card shows name, version chip, Actif/Inactif status dot, description,
author, and count chips for tools/hooks/prompts contributed by the plugin. **Refresh**
(`plugins_refresh`) reloads the list. No enable/disable or install action is exposed in this
screen — see `docs/PLUGINS.md` for how plugins are installed and structured.

### ChannelsScreen

Status and configuration for the 19 supported messaging channels (`PrometheRoute.Channels`).
Each channel card (`channels_card_{name}`) shows an icon, webhook path (or "Not configured"),
and a Configured/Not configured badge. Main actions:
- Tap a card to **Configure** it — opens a dialog with one text field per required
  credential key for that channel (values pre-filled from any existing config); **Save**
  persists them.
- **Test** button (send icon, shown only once a channel is configured) fires a test message
  and surfaces the result in a snackbar.
- **Refresh** (`channels_refresh`) reloads channel status.

See `docs/CHANNELS.md` for the exact credentials each of the 19 channels needs and how to
obtain them (bot tokens, webhook URLs, signing secrets, etc.) — this screen is one of the
three places channels can be configured (the others being the Setup wizard step 3 and
Settings > Intégrations & Channels).

### SchedulerScreen

CRUD for cron-style scheduled tasks (`PrometheRoute.Scheduler`, sidebar label "Scheduler").
Main actions:
- **+** FAB (`scheduler_create_btn`) opens **Nouvelle tâche planifiée**: Nom, an Agent
  dropdown (defaults to "Agent par défaut" or any configured profile), a Message/Instructions
  field, quick-pick cron templates (Toutes les heures, Chaque matin 8h, …), an Hour selector,
  a Récurrent toggle (`scheduler_toggle_recurring`) plus interval/period pickers (minutes/
  heures/jours/mois) when recurring, a live human-readable summary, and a "Prochaines
  exécutions" preview of the next 3 run times before **Créer** (`scheduler_save`).
- Each task card (`scheduler_task_{id}`) shows the human-readable schedule, assigned profile
  (if any), last-run status chip, cron expression, and a countdown to the next run.
  **Pause/Resume** (`scheduler_toggle_{id}`) and **Delete** (`scheduler_delete_{id}`) icon
  buttons act per task. **Refresh** (`scheduler_refresh`) reloads the list.

See `docs/SCHEDULER.md` for the cron engine and task execution semantics behind this screen.

### StatsScreen

Read-only operational dashboard (`PrometheRoute.Stats`). Header shows an En ligne/Hors ligne
health indicator. Sections:
- **KPI grid** — total tokens, total requests, estimated cost, average feedback score.
- **Système** — runtime info (uptime, model, provider, execution backend, version), LLM and
  Cache stat cards, Planificateur/Plugins/Mémoire summary cards, a Fournisseurs LLM chip row
  (configured vs `no_keys` status per provider), and a Hooks chip row.
- **Optimisation de Prompt (GEPA)** — an **Optimiser le Prompt** button
  (`stats_gepa_optimize`) that runs a GEPA optimization pass in place and displays the
  resulting accuracy/improvement/generations/candidates plus a best-prompt preview. This is a
  shortcut into the same GEPA engine that GepaDashboard drives in more depth.

**Refresh** (`stats_refresh`) reloads all stats.

---

## Optimization

### GepaDashboard (GEPA)

Genetic prompt optimization control panel (`PrometheRoute.Gepa`, sidebar label "GEPA"). Main
actions:
- **Tune** icon toggles the **Configuration** panel: Generations slider (1–20), Population
  slider (2–16), and an **Optimization Target** chip row (System Prompt / Skill / Profile) —
  choosing Skill or Profile reveals a dropdown to pick which one to optimize.
- **Launch** button (`gepa_run_btn`) starts an optimization run; while running, a progress
  bar shows the current generation and completion percentage, polled from the server.
- **Best Result** card — highest-accuracy run so far, with accuracy/improvement/generations/
  candidates-evaluated metric pills, the optimized prompt preview, and a **Copy** button.
- **Run History** — a list of past runs with target label, generation/population config, and
  accuracy (highlighted green when it improved).

Empty state prompts launching GEPA to evolve the system prompt for the first time.

---

## Cross-references

- `docs/SKILLS.md` — skill file format, `SkillWriter`/`SkillCurator`, and the skills API that
  SkillsScreen fronts.
- `docs/PLUGINS.md` — plugin structure and discovery behind PluginsScreen.
- `docs/CHANNELS.md` — per-channel setup steps and credentials for ChannelsScreen and the
  Settings > Intégrations & Channels section.
- `docs/CONFIGURATION.md` — full environment/credential reference behind SettingsScreen.
- `docs/SCHEDULER.md` — cron engine details behind SchedulerScreen.
- `docs/SECURITY.md` — the approval-gate and sandboxed-execution defaults referenced in
  Settings > Sécurité and AgentMonitorScreen's Pending Approvals panel.
- `docs/ACCESSIBILITY.md` — full semantics/testTag reference for Settings and Setup, useful
  for automated UI testing against any screen in this guide.
