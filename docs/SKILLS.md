# Skill System

## Overview

Skills are reusable blocks of knowledge that Promethe accumulates over the course of conversations. Each skill captures validated know-how (code pattern, procedure, convention) and can be automatically injected into an agent's context when it handles a similar request.

**Directory**: `~/.promethe/skills/`

### Main components

| Component | Role |
|---|---|
| `SkillLoader.kt` | Loads skills, builds a keyword index, manages the cache |
| `SkillWriter.kt` | Synthesizes new skills from successful trajectories |
| `SkillCurator.kt` | Scores skills and emits non-destructive review proposals |

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
| `eval_suite` | optional | Evaluation suite identifiers required by future automated promotion |
| `provenance`, `owner` | optional | Origin and owner metadata |

Files without lifecycle metadata remain `ACTIVE` for backward compatibility. A declared hash mismatch forces
the skill to `QUARANTINED`. Only `ACTIVE` skills are searchable, loadable, or injected into an agent prompt.

---

## Automatic synthesis

`SkillWriter` analyzes conversation trajectories that resulted in a user-validated outcome. The process:

1. **Detection** — A trajectory is marked as successful (positive feedback or error-free completion).
2. **Extraction** — The writer isolates the key steps, decisions, and code produced.
3. **Drafting** — A Markdown skill is generated with the appropriate frontmatter.
4. **Deduplication** — Checked against existing skills via the keyword index.
5. **Writing** — The file is saved to `~/.promethe/skills/` as `DRAFT`.

The owner promotes a skill through `DRAFT → QUARANTINED → CANDIDATE → ACTIVE`. Content edited by an
agent or through the management API is quarantined; a GEPA result is stored as a candidate. Deprecation
removes a skill from agent-side discovery without deleting its file.

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
