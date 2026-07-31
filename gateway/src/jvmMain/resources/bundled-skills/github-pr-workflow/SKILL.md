---
name: github-pr-workflow
description: "GitHub PR lifecycle: branch, commit, open, CI, merge."
source: BUNDLED
requires_cli: git,gh
platforms: jvm
---

# GitHub Pull Request Workflow

Complete guide for managing the PR lifecycle. Each section shows the `gh` way first, then the `git` + `curl`/`http_fetch` fallback.

## Prerequisites

- Authenticated with GitHub (see `github-auth` skill)
- Inside a git repository with a GitHub remote
- `GITHUB_TOKEN` environment variable set (for curl/http_fetch fallback)

### Quick Auth Detection

```bash
if command -v gh &>/dev/null && gh auth status &>/dev/null; then
  AUTH="gh"
else
  AUTH="git"
fi
echo "Using: $AUTH"
```

### Extracting Owner/Repo from the Git Remote

```bash
REMOTE_URL=$(git remote get-url origin)
OWNER_REPO=$(echo "$REMOTE_URL" | sed -E 's|.*github\.com[:/]||; s|\.git$||')
OWNER=$(echo "$OWNER_REPO" | cut -d/ -f1)
REPO=$(echo "$OWNER_REPO" | cut -d/ -f2)
echo "Owner: $OWNER, Repo: $REPO"
```

Run setup via `execute_command(command="bash", args=["-c", "<script>"])`.

---

## 1. Branch Creation

Pure `git` — identical either way:

```bash
git fetch origin
git checkout main && git pull origin main
git checkout -b feat/add-user-authentication
```

Branch naming conventions:
- `feat/description` — new features
- `fix/description` — bug fixes
- `refactor/description` — code restructuring
- `docs/description` — documentation
- `ci/description` — CI/CD changes

## 2. Making Commits

Use the agent's file tools (`file_write`, `file_read`) to make changes, then commit:

```bash
git add src/auth.py src/models/user.py tests/test_auth.py

git commit -m "feat: add JWT-based user authentication

- Add login/register endpoints
- Add User model with password hashing
- Add auth middleware for protected routes
- Add unit tests for auth flow"
```

Commit message format (Conventional Commits):
```
type(scope): short description

Longer explanation if needed. Wrap at 72 characters.
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `ci`, `chore`, `perf`

## 3. Pushing and Creating a PR

### Push the Branch

```bash
git push -u origin HEAD
```

### Create the PR

**With gh:**

```bash
gh pr create \
  --title "feat: add JWT-based user authentication" \
  --body "## Summary
- Adds login and register API endpoints
- JWT token generation and validation

## Test Plan
- [ ] Unit tests pass

Closes #42"
```

Options: `--draft`, `--reviewer user1,user2`, `--label "enhancement"`, `--base develop`

**With http_fetch:**

```
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}/pulls",
  method="POST",
  body={
    "title": "feat: add JWT-based user authentication",
    "body": "## Summary\nAdds login and register API endpoints.\n\nCloses #42",
    "head": "{branch}",
    "base": "main"
  }
)
```

To create as a draft, add `"draft": true` to the body.

## 4. Monitoring CI Status

**With gh:**

```bash
gh pr checks
gh pr checks --watch
```

**With http_fetch:**

```
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}/commits/{sha}/check-runs",
  method="GET"
)
```

## 5. Updating a PR

```bash
# Push more commits
git add .
git commit -m "fix: address review feedback"
git push

# Update PR title/body (gh)
gh pr edit 123 --title "new title" --body "new body"

# Add reviewers
gh pr edit 123 --add-reviewer user1,user2
```

## 6. Merging

**With gh:**

```bash
gh pr merge 123 --squash --delete-branch
gh pr merge 123 --merge
gh pr merge 123 --rebase
```

**With http_fetch:**

```
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}/pulls/123/merge",
  method="PUT",
  body={"merge_method": "squash"}
)
```

After merging, clean up:

```bash
git checkout main
git pull origin main
git branch -d feat/add-user-authentication
```

---

## Workflow Summary

1. Create branch → `execute_command(command="git", args=["checkout", "-b", "feat/..."])`
2. Make changes → `file_write(path=..., content=...)`
3. Commit → `execute_command(command="git", args=["commit", "-m", "..."])`
4. Push → `execute_command(command="git", args=["push", "-u", "origin", "HEAD"])`
5. Create PR → `gh pr create` or `http_fetch` to GitHub API
6. Monitor CI → `gh pr checks` or `http_fetch` check-runs endpoint
7. Merge → `gh pr merge` or `http_fetch` merge endpoint
