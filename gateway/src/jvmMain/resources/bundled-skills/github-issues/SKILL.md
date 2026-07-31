---
name: github-issues
description: "Create, triage, label, assign GitHub issues via gh or REST."
source: BUNDLED
requires_cli: git,gh
platforms: jvm
---

# GitHub Issues Management

Create, search, triage, and manage GitHub issues. Each section shows `gh` first, then the `curl`/`http_fetch` fallback.

## Prerequisites

- Authenticated with GitHub (see `github-auth` skill)
- Inside a git repo with a GitHub remote, or specify the repo explicitly
- `GITHUB_TOKEN` environment variable set (for curl/http_fetch fallback)

### Setup

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

Run via `execute_command(command="bash", args=["-c", "<script>"])`.

---

## 1. Viewing Issues

**With gh:**

```bash
gh issue list
gh issue list --state open --label "bug"
gh issue list --assignee @me
gh issue list --search "authentication error" --state all
gh issue view 42
```

**With http_fetch:**

```
# List open issues
http_fetch(url="https://api.github.com/repos/{owner}/{repo}/issues?state=open&per_page=20", method="GET")

# Filter by label
http_fetch(url="https://api.github.com/repos/{owner}/{repo}/issues?state=open&labels=bug&per_page=20", method="GET")

# View a specific issue
http_fetch(url="https://api.github.com/repos/{owner}/{repo}/issues/42", method="GET")
```

Note: The GitHub API returns PRs in `/issues` too — filter by checking that `pull_request` key is absent.

**Search issues:**

```
http_fetch(url="https://api.github.com/search/issues?q=repo:{owner}/{repo}+authentication+error", method="GET")
```

---

## 2. Creating Issues

**With gh:**

```bash
gh issue create \
  --title "Login fails with expired tokens" \
  --body "## Steps to Reproduce
1. Log in with valid credentials
2. Wait for token to expire
3. Try to refresh

## Expected
Auto-refresh should work

## Actual
500 error returned" \
  --label "bug,auth" \
  --assignee "@me"
```

**With http_fetch:**

```
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}/issues",
  method="POST",
  body={"title": "Login fails with expired tokens", "body": "...", "labels": ["bug", "auth"], "assignees": ["username"]}
)
```

---

## 3. Updating Issues

**With gh:**

```bash
# Add labels
gh issue edit 42 --add-label "priority:high,needs-triage"

# Assign
gh issue edit 42 --add-assignee "developer1"

# Close with comment
gh issue close 42 --comment "Fixed in #55"

# Reopen
gh issue reopen 42
```

**With http_fetch:**

```
# Update labels/assignees
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}/issues/42",
  method="PATCH",
  body={"labels": ["bug", "priority:high"], "assignees": ["developer1"]}
)

# Close
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}/issues/42",
  method="PATCH",
  body={"state": "closed"}
)

# Add a comment
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}/issues/42/comments",
  method="POST",
  body={"body": "Fixed in #55"}
)
```

---

## 4. Issue Comments

**With gh:**

```bash
gh issue comment 42 --body "Investigating — looks related to #38."
```

**With http_fetch:**

```
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}/issues/42/comments",
  method="POST",
  body={"body": "Investigating — looks related to #38."}
)
```

---

## 5. Bulk Operations

Use `delegate_task(task=..., agent=...)` to process multiple issues in parallel — e.g., triaging, labeling, or closing stale issues.

Example workflow:
1. List all issues with a specific label
2. For each issue, delegate a sub-task to analyze and categorize
3. Aggregate results and apply bulk updates

---

## Tips

- Always check for duplicates before creating a new issue
- Use labels consistently: `bug`, `enhancement`, `question`, `documentation`
- Link related issues with `#number` references in the body
- Use milestones to track release progress
