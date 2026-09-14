# Skill System

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](EXPERIMENTAL_STATUS.md).

## Overview

Skills are reusable instructions, procedures or conventions that Promethe can inject into an agent's context when relevant. A synthesized skill starts as a draft; its existence does not establish useful or validated knowledge. Managed promotion requires evaluation evidence for the exact revision and an owner review. The [evaluation lifecycle](reports/SKILL_EVALUATION_LIFECYCLE_2026-09-08.md) is implemented and locally validated: 1,071 tests pass, including reuse through the real agent loop with a deterministic provider, without a paid model call.

**Directory**: `~/.promethe/skills/`

### Main components

| Component | Role |
|---|---|
| `SkillLoader.kt` | Indexes skills and rereads content and evidence at each use; no retained `SKILL.md` content cache |
| `SkillWriter.kt` | Synthesizes new skills from successful trajectories |
| `SkillCurator.kt` | Scores skills and emits non-destructive review proposals |
| `SkillEvaluationService.kt` | Runs declared text cases and records exact-output results |
| `SkillGovernanceStore.kt` | Stores evaluation suites, revision evidence and version snapshots |

---

## Format of a skill

Each skill is a Markdown file with a YAML frontmatter:

```markdown
---
name: kotlin-coroutines-error-handling
description: Error handling patterns in Kotlin coroutines
lifecycle: ACTIVE
version: 1
content_hash: 7c5e…
triggers: coroutines, exception, supervisorScope
anti_triggers: callback-only code
required_tools: code_grep, read_file
eval_suite: kotlin-coroutines-smoke
---

## Context

Use `supervisorScope` to isolate child failures…

## Instructions

1. Always wrap critical `launch` calls with a handler…
2. …
```

### Frontmatter fields

| Field | Required | Description |
|---|---|---|
| `name` | yes | Unique identifier (kebab-case slug) |
| `description` | recommended | Short description (one sentence) |
| `lifecycle` | written by Promethe | `DRAFT`, `QUARANTINED`, `CANDIDATE`, `ACTIVE`, or `DEPRECATED` |
| `version` | written by Promethe | Contract version |
| `content_hash` | written by Promethe | SHA-256 of the normalized Markdown body |
| `triggers`, `anti_triggers` | optional | Declarative matching contract; matching still derives its index from description and body in this phase |
| `required_tools`, `required_skills` | optional | Declared dependencies |
| `eval_suite` | optional metadata | Declared evaluation references; executable cases are configured through the evaluation-suites API or skill screen |
| `provenance`, `owner` | optional | Origin and owner metadata |

Unchanged legacy and bundled skills remain `ACTIVE` for explicit backward compatibility, without newly generated evaluation evidence. Editing or configuring their suites enters the managed evaluation cycle, including system skills. A declared body-hash mismatch forces quarantine. Only `ACTIVE` skills are searchable, loadable, or injected into an agent prompt.

`content_hash` identifies the normalized Markdown body. The separate validation `revisionHash` covers the body, metadata, revision nonce and evaluation suites. An old passing run cannot authorize an edited or restored revision merely because its body matches an earlier one.

---

## Automatic synthesis

`SkillWriter` analyzes trajectories marked successful by the application. This signal is not necessarily a user review or a skill-specific test. The process:

1. **Detection** — A trajectory is marked as successful (positive feedback or error-free completion).
2. **Extraction** — The writer isolates the key steps, decisions, and code produced.
3. **Drafting** — A Markdown skill is generated with the appropriate frontmatter.
4. **Deduplication** — Checked against existing skills via the keyword index.
5. **Writing** — The file is saved to `~/.promethe/skills/` as `DRAFT`.

The owner promotes a skill through `DRAFT → QUARANTINED → CANDIDATE → ACTIVE`. Edits, evaluation-suite configuration and restoration quarantine the resulting revision, including system skills. GEPA produces `DRAFT` for a new skill and `QUARANTINED` for a modified one; it does not bypass evaluations by creating a promotable candidate. Deprecation removes a skill from agent-side discovery without deleting its file.

Promotions to `CANDIDATE` and `ACTIVE` require current `expectedContentHash` and `expectedRevisionHash`, a nonempty owner `reviewNote` (maximum 2,000 characters), and the **latest evaluation run marked `PASSED` for that exact revision**. A stale review or evaluation cannot authorize promotion. An interrupted run is not promotable; a new failing run invalidates an earlier pass. Tests and owner review are separate requirements.

## Evaluate, review and restore

The skill screen provides **Cas de test**, **Lancer tests**, and **Historique et restauration**, with a visible cost notice. Configure between one and eight suites, at least two distinct inputs per suite, and at most 20 cases overall. Each case has `id`, `input` and `expectedOutput`; texts are limited to 16,384 characters. The oracle compares output and expected output **exactly after trimming surrounding whitespace**.

Each case sends a fresh text-only request through Koog using the gateway's configured model, without tools and without the expected answer. Each case has a 60-second timeout. Running tests in the application can therefore incur provider charges; the local implementation tests use simulated providers. This evaluates the declared text task, not tool execution, ancillary scripts or persistent memory.

1. Edit the skill and configure meaningful cases, including distinct inputs; the revision enters quarantine.
2. Launch tests and inspect the latest run for the current `revisionHash`.
3. If all required cases pass, review the exact revision and provide the owner note before promotion.
4. To restore, select a saved version. Restoration creates a quarantined revision that requires **new tests and review**, without silently restoring active status.

Local evidence is stored under the skills directory in `.skillops/{slug}/`: `suites.json`, `latest.json`, `runs/` and `versions/`. `latest.json` records `RUNNING` before evaluation calls. Version entries are snapshots captured before publication, not a transactional commit journal. The local history is unsigned and does not prove the owner's identity cryptographically or protect against the owner modifying files on disk. Writers and evaluations are serialized within one process; no cross-process writer guarantee is established.

Content and evidence are reread at every skill use, including to detect direct same-size file edits. The revision fingerprint does not hash the transitive contents of declared dependencies. Tests cover the declared text cases, not every dependency, tool effect or memory behavior. The Desktop code and test suite pass, but the new dialog has not been exercised manually on a running desktop.

---

## Curation

`SkillCurator` performs a proposal-only quality pass:

- **Scoring** — An LLM grades utility, clarity, specificity, and freshness from 1 to 5.
- **Low quality** — A score at or below the threshold creates a `REVIEW_LOW_QUALITY` proposal.
- **Duplicates** — High keyword overlap creates a `REVIEW_DUPLICATE` proposal.
- **Quarantine** — Proposals are returned with `QUARANTINED` status for explicit review.

The curator never deletes, merges, rewrites, or invalidates a skill. The `merged` and `deleted` API counters remain at zero for backward compatibility.

---

## API Reference

Base: `/api/v1/skills`

| Method | Route | Description |
|---|---|---|
| `GET` | `/api/v1/skills` | Lists all skills |
| `GET` | `/api/v1/skills/:name` | Retrieves a skill by name |
| `POST` | `/api/v1/skills` | Creates a new skill |
| `PUT` | `/api/v1/skills/:name` | Updates an existing skill |
| `PUT` | `/api/v1/skills/:name/lifecycle` | Applies one valid lifecycle transition |
| `GET` | `/api/v1/skills/:name/validation` | Current revision hash, suites, latest run, promotion readiness, versions and runs |
| `PUT` | `/api/v1/skills/:name/evaluation-suites` | Configures cases against `expectedRevisionHash`; quarantines the resulting revision |
| `POST` | `/api/v1/skills/:name/evaluations` | Evaluates the current revision; may call the configured provider |
| `POST` | `/api/v1/skills/:name/restore` | Restores a version as quarantined; requires new tests and review |
| `DELETE` | `/api/v1/skills/:name` | Deletes a skill |
| `GET` | `/api/v1/skills/search?q=…` | Searches by keywords |
| `POST` | `/api/v1/skills/curate` | Scores skills and returns quarantined review proposals |

---

## Agent tools

These tools are available to agents during a conversation:

| Tool | Description |
|---|---|
| `skill_search` | Searches skills by keywords |
| `skill_load` | Loads the full content of a skill into the context |
| `skill_list` | Lists available skills |
| `skill_create` | Creates a new skill manually |
| `skill_improve` | Improves an existing skill with new information |

### Automatic matching

`SkillLoader.findRelevantSkills()` compares query terms against an inverted index derived from each active
skill's description and body. Relevant `ACTIVE` skills are automatically injected into the agent's system prompt.

---

The standalone CLI does not currently expose skill-management commands. Use the authenticated REST API or
the Desktop/Web skill screen.
