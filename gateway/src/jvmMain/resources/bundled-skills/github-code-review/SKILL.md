---
name: github-code-review
description: "Review PRs: diffs, inline comments via gh or REST."
source: BUNDLED
requires_cli: git,gh
platforms: jvm
---

# GitHub Code Review

Perform code reviews on local changes before pushing, or review open PRs on GitHub. Most of this skill uses plain `git` — the `gh`/`curl` split only matters for PR-level interactions.

## Prerequisites

- Authenticated with GitHub (see `github-auth` skill)
- Inside a git repository
- `GITHUB_TOKEN` environment variable set (for curl fallback)

### Setup (for PR interactions)

```bash
if command -v gh &>/dev/null && gh auth status &>/dev/null; then
  AUTH="gh"
else
  AUTH="git"
fi

REMOTE_URL=$(git remote get-url origin)
OWNER_REPO=$(echo "$REMOTE_URL" | sed -E 's|.*github\.com[:/]||; s|\.git$||')
OWNER=$(echo "$OWNER_REPO" | cut -d/ -f1)
REPO=$(echo "$OWNER_REPO" | cut -d/ -f2)
```

Run setup via `execute_command(command="bash", args=["-c", "<script>"])`.

---

## 1. Reviewing Local Changes (Pre-Push)

Pure `git` — works everywhere, no API needed.

### Get the Diff

```bash
# Staged changes
git diff --staged

# All changes vs main (what a PR would contain)
git diff main...HEAD

# File names only
git diff main...HEAD --name-only

# Stat summary
git diff main...HEAD --stat
```

### Review Strategy

1. **Get the big picture first:**

```bash
git diff main...HEAD --stat
git log main..HEAD --oneline
```

2. **Review file by file** — use `file_read(path=...)` on changed files for full context, and the diff to see what changed:

```bash
git diff main...HEAD -- src/auth/login.py
```

3. **Check for common issues:**

```bash
# Debug statements, TODOs, console.logs left behind
git diff main...HEAD | grep -n "print(\|console\.log\|TODO\|FIXME\|HACK\|XXX\|debugger"

# Large files accidentally staged
git diff main...HEAD --stat | sort -t'|' -k2 -rn | head -10

# Secrets or credential patterns
git diff main...HEAD | grep -in "password\|secret\|api_key\|token.*=\|private_key"

# Merge conflict markers
git diff main...HEAD | grep -n "<<<<<<\|>>>>>>\|======="
```

4. **Present structured feedback** to the user.

### Review Output Format

```
## Code Review Summary

### Critical
- **src/auth.py:45** — SQL injection: user input passed directly to query.
  Suggestion: Use parameterized queries.

### Warnings
- **src/models/user.py:23** — Password stored in plaintext. Use bcrypt or argon2.
- **src/api/routes.py:112** — No rate limiting on login endpoint.

### Suggestions
- **src/utils/helpers.py:8** — Duplicates logic in `src/core/utils.py:34`. Consolidate.
- **tests/test_auth.py** — Missing edge case: expired token test.

### Looks Good
- Clean separation of concerns in the middleware layer
- Good test coverage for the happy path
```

---

## 2. Reviewing a Pull Request on GitHub

### View PR Details

**With gh:**

```bash
gh pr view 123
gh pr diff 123
gh pr diff 123 --name-only
```

**With curl (via `execute_command` or `http_fetch`):**

```bash
PR_NUMBER=123

# Get PR details
curl -s \
  -H "Authorization: token $GITHUB_TOKEN" \
  https://api.github.com/repos/$OWNER/$REPO/pulls/$PR_NUMBER
```

Or use `http_fetch(url="https://api.github.com/repos/{owner}/{repo}/pulls/{number}", method="GET")` with appropriate auth headers.

### Check Out PR Locally for Full Review

```bash
# Fetch the PR branch and check it out
git fetch origin pull/123/head:pr-123
git checkout pr-123

# Now use file_read, grep, run tests, etc.

# View diff against the base branch
git diff main...pr-123
```

**With gh (shortcut):**

```bash
gh pr checkout 123
```

### Leave Comments on a PR

**General PR comment — with gh:**

```bash
gh pr comment 123 --body "Overall looks good, a few suggestions below."
```

**General PR comment — with http_fetch:**

Use `http_fetch(url="https://api.github.com/repos/{owner}/{repo}/issues/{pr_number}/comments", method="POST")` with JSON body `{"body": "Overall looks good, a few suggestions below."}`.

### Inline Review Comments

**With gh:**

```bash
gh api repos/$OWNER/$REPO/pulls/$PR_NUMBER/reviews \
  --method POST \
  --field body="Review complete" \
  --field event="COMMENT"
```

**With http_fetch:**

Use `http_fetch(url="https://api.github.com/repos/{owner}/{repo}/pulls/{number}/reviews", method="POST")` with appropriate review payload.

---

## 3. Approve / Request Changes

**With gh:**

```bash
gh pr review 123 --approve --body "LGTM!"
gh pr review 123 --request-changes --body "Please fix the auth issue."
```

**With http_fetch:**

POST to `/repos/{owner}/{repo}/pulls/{number}/reviews` with `{"event": "APPROVE"}` or `{"event": "REQUEST_CHANGES", "body": "..."}`.
