---
name: simplify-code
description: "Parallel 3-agent cleanup of recent code changes."
source: BUNDLED
platforms: jvm
---

# Simplify Code — Parallel Review & Cleanup

Review your recent code changes with three focused reviewers running in
parallel, aggregate their findings, and apply the fixes worth applying.

**Core principle:** Three narrow reviewers beat one broad reviewer. Each one
deeply searches the codebase for a single class of problem — reuse, quality,
efficiency — without diluting its attention across all three. They run
concurrently, so you pay the latency of one review, not three.

## When to Use

Trigger this skill when the user says any of:

- "simplify" / "simplify my changes" / "simplify these changes"
- "review my code" / "review my recent changes" / "clean up my changes"

Optional modifiers the user may add — honor them:

- **Focus:** "simplify focus on efficiency" → run only the efficiency reviewer
  (or weight the aggregation toward it). Recognized focuses: `reuse`,
  `quality`, `efficiency`.
- **Dry run:** "simplify but don't change anything" / "just report" → run the
  three reviewers, present findings, apply NOTHING. Ask before applying.
- **Scope:** "simplify the last commit" / "simplify staged" / "simplify
  src/foo.py" → narrow the diff source accordingly (see Phase 1).

Do NOT auto-run this after every edit. It costs three delegated agents' worth of
tokens — invoke it only when the user explicitly asks.

## The Process

### Phase 1 — Identify the changes

Capture the diff to review. Pick the source by what the user asked for, in
this default order:

```bash
# 1. Default: uncommitted working-tree changes (tracked files)
git diff

# 2. If that's empty, include staged changes
git diff HEAD

# 3. Scoped variants the user may request:
git diff --staged                 # "staged changes"
git diff HEAD~1                    # "the last commit"
git diff main...HEAD              # "this branch" / "my PR"
git diff -- src/foo.py            # specific file(s)
```

If `git diff` and `git diff HEAD` are both empty and there's no git repo or no
changes, fall back to the files the user explicitly named or that were
recently created/edited in this session. If you genuinely can't find any
changed code, say so and stop — there's nothing to simplify.

Capture the full diff text. Note its size: if it's very large (say >2000
changed lines), warn the user that three delegated agents each carrying the full diff
will be token-heavy, and offer to scope it down (per-directory, per-commit)
before proceeding.

### Phase 2 — Launch three reviewers in parallel

Use `delegate_task` to dispatch all three tasks concurrently.

Give **every** reviewer the **complete diff** (not fragments — cross-file
issues hide in the gaps) plus the absolute repo path so they can search the
wider codebase. Each reviewer should use `execute_command`, `file_read`, and
`file_search` tools to investigate.

Tell each reviewer to:
- Search the existing codebase for evidence (don't reason from the diff alone).
- **Apply Chesterton's Fence:** before flagging anything for removal, run
  `git blame` on the line to understand why it exists. If you can't determine
  the original purpose, mark it `confidence: low` — don't guess.
- Report findings as structured output with confidence and risk:
  ```
  file:line → problem → suggested fix | confidence: high/medium/low | risk: SAFE/CAREFUL/RISKY
  ```
  - **SAFE** = proven not to affect behavior (unused imports, commented-out
    code, pass-through wrappers). Auto-apply these.
  - **CAREFUL** = improves without changing semantics (rename local variable,
    flatten nested ternary, extract helper). Apply with test verification.
  - **RISKY** = may change behavior or breaks public contracts (N+1
    restructuring, public API rename, memory lifecycle change). Flag for
    human review — do NOT auto-apply.
- Skip nits and style-only churn. Only flag things that materially improve
  the code.

Pass these three goals (drop any the user's focus excludes):

**Reviewer 1 — Code Reuse**
> Review this diff for code that duplicates functionality already in the
> codebase. Search utility modules, shared helpers, and adjacent files
> (use file_search / grep) for existing functions, constants, or patterns
> the new code could call instead of reimplementing. Flag: new functions
> that duplicate existing ones; hand-rolled logic that an existing utility
> already does.

**Reviewer 2 — Code Quality**
> Review this diff for clarity, naming, structure, and maintainability.
> Flag: unclear variable names, overly complex conditionals, missing error
> handling, dead code, misleading comments, functions doing too many things.

**Reviewer 3 — Efficiency**
> Review this diff for performance issues. Flag: unnecessary allocations,
> N+1 query patterns, redundant I/O, unbounded loops, missing caching
> opportunities, expensive operations in hot paths.

### Phase 3 — Aggregate findings

Collect all three reviewer reports. Deduplicate overlapping findings.
Present a unified table sorted by risk (SAFE first, RISKY last):

| # | File:Line | Problem | Fix | Confidence | Risk | Reviewer |
|---|-----------|---------|-----|------------|------|----------|

### Phase 4 — Apply fixes

- **SAFE** items: apply automatically without asking.
- **CAREFUL** items: apply, then run tests to verify no regressions.
- **RISKY** items: present to user, do NOT apply without explicit approval.

After applying, run the project's test suite via `execute_command` to confirm
nothing broke.
