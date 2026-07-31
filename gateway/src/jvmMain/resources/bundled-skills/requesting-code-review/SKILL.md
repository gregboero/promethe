---
name: requesting-code-review
description: "Pre-commit review: security scan, quality gates, auto-fix."
source: BUNDLED
platforms: jvm
---

# Pre-Commit Code Verification

Automated verification pipeline before code lands. Static scans, baseline-aware
quality gates, an independent reviewer agent, and an auto-fix loop.

**Core principle:** No agent should verify its own work. Fresh context finds what you miss.

## When to Use

- After implementing a feature or bug fix, before `git commit` or `git push`
- When user says "commit", "push", "ship", "done", "verify", or "review before merge"
- After completing a task with 2+ file edits in a git repo
- After each delegated task (two-stage review)

**Skip for:** documentation-only changes, pure config tweaks, or when user says "skip verification".

## Step 1 — Get the diff

```bash
git diff --cached
```

If empty, try `git diff` then `git diff HEAD~1 HEAD`.

If `git diff --cached` is empty but `git diff` shows changes, tell the user to
`git add <files>` first. If still empty, run `git status` — nothing to verify.

If the diff exceeds 15,000 characters, split by file:
```bash
git diff --name-only
git diff HEAD -- specific_file.py
```

## Step 2 — Static security scan

Scan added lines only. Any match is a security concern fed into Step 5.

```bash
# Hardcoded secrets
git diff --cached | grep "^+" | grep -iE "(api_key|secret|password|token|passwd)\s*=\s*['\"][^'\"]{6,}['\"]"

# Shell injection
git diff --cached | grep "^+" | grep -E "os\.system\(|subprocess.*shell=True"

# Dangerous eval/exec
git diff --cached | grep "^+" | grep -E "\beval\(|\bexec\("

# Unsafe deserialization
git diff --cached | grep "^+" | grep -E "pickle\.loads?\("

# SQL injection (string formatting in queries)
git diff --cached | grep "^+" | grep -E "execute\(f\"|\.format\(.*SELECT|\.format\(.*INSERT"
```

## Step 3 — Baseline tests and linting

Detect the project language and run the appropriate tools. Capture the failure
count BEFORE your changes as **baseline_failures** (stash changes, run, pop).
Only NEW failures introduced by your changes block the commit.

**Test frameworks** (auto-detect by project files):
```bash
# Python (pytest)
python -m pytest --tb=no -q 2>&1 | tail -5

# Node (npm test)
npm test -- --passWithNoTests 2>&1 | tail -5

# Rust
cargo test 2>&1 | tail -5

# Go
go test ./... 2>&1 | tail -5

# JVM (Gradle)
./gradlew test 2>&1 | tail -5
```

**Linting and type checking** (run only if installed):
```bash
# Python
which ruff && ruff check . 2>&1 | tail -10
which mypy && mypy . --ignore-missing-imports 2>&1 | tail -10

# Node
which npx && npx eslint . 2>&1 | tail -10
which npx && npx tsc --noEmit 2>&1 | tail -10

# Rust
cargo clippy -- -D warnings 2>&1 | tail -10

# Go
which go && go vet ./... 2>&1 | tail -10

# Kotlin
./gradlew detekt 2>&1 | tail -10
```

**Baseline comparison:** If baseline was clean and your changes introduce failures,
that's a regression. If baseline already had failures, only count NEW ones.

## Step 4 — Self-review checklist

Quick scan before dispatching the reviewer:

- [ ] No hardcoded secrets, API keys, or credentials
- [ ] Input validation on user-provided data
- [ ] SQL queries use parameterized statements
- [ ] File operations validate paths (no traversal)
- [ ] External calls have error handling (try/catch)
- [ ] No debug print/console.log left behind
- [ ] No commented-out code
- [ ] New code has tests (if test suite exists)
- [ ] Error messages are helpful, not leaking internals

## Step 5 — Delegate independent review

Use `delegate_task` to spawn a fresh reviewer agent. The reviewer has no
context from the implementation — that's the point.

Provide the reviewer with:
1. The full diff
2. Any security findings from Step 2
3. The test/lint results from Step 3

The reviewer should:
- Check for logic errors, edge cases, and off-by-one mistakes
- Verify error handling covers failure paths
- Confirm naming is clear and consistent
- Flag any concerns with severity: `BLOCK`, `WARN`, `NIT`

## Step 6 — Auto-fix loop

For `BLOCK` and `WARN` findings:
1. Fix the issue
2. Re-run the relevant test/lint check
3. Verify the fix doesn't introduce regressions

For `NIT` findings:
- Apply if trivial, skip if subjective

After all fixes, re-run the full test suite one final time via `execute_command`.

## Step 7 — Report

Present a summary to the user:

```markdown
## Verification Complete

**Security:** ✅ No issues (or list findings)
**Tests:** ✅ All passing (N tests)
**Lint:** ✅ Clean (or N new warnings)
**Review:** ✅ No blockers (or list BLOCK items)

**Applied fixes:** (list what was auto-fixed)
**Needs human review:** (list RISKY items, if any)

Ready to commit? y/n
```
