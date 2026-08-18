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
keywords:
  - coroutines
  - exception
  - supervisorScope
  - CoroutineExceptionHandler
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
| `name` | ✅ | Unique identifier (kebab-case slug) |
| `description` | ✅ | Short description (one sentence) |
| `keywords` | ✅ | List of keywords for matching |

---

## Automatic synthesis

`SkillWriter` analyzes conversation trajectories that resulted in a user-validated outcome. The process:

1. **Detection** — A trajectory is marked as successful (positive feedback or error-free completion).
2. **Extraction** — The writer isolates the key steps, decisions, and code produced.
3. **Drafting** — A Markdown skill is generated with the appropriate frontmatter.
4. **Deduplication** — Checked against existing skills via the keyword index.
5. **Writing** — The file is saved to `~/.promethe/skills/`.

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

`SkillLoader.findRelevantSkills()` compares the keywords of the current conversation against the skills' inverted index. Relevant skills are automatically injected into the agent's system prompt.

---

## CLI usage

```bash
# List skills
promethe skills list

# Search
promethe skills search "coroutines error"

# Show a skill
promethe skills show kotlin-coroutines-error-handling

# Create manually
promethe skills create my-new-skill.md

# Delete
promethe skills delete kotlin-coroutines-error-handling
```
