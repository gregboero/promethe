---
name: github-auth
description: "GitHub auth setup: HTTPS tokens, SSH keys, gh CLI login."
source: BUNDLED
requires_cli: git,gh
platforms: jvm
---

# GitHub Authentication Setup

This skill sets up authentication so the agent can work with GitHub repositories, PRs, issues, and CI. It covers two paths:

- **`git` (always available)** — uses HTTPS personal access tokens or SSH keys
- **`gh` CLI (if installed)** — richer GitHub API access with a simpler auth flow

## Detection Flow

When a user asks you to work with GitHub, run this check first:

```bash
git --version
gh --version 2>/dev/null || echo "gh not installed"
gh auth status 2>/dev/null || echo "gh not authenticated"
git config --global credential.helper 2>/dev/null || echo "no git credential helper"
```

Run via `execute_command(command="bash", args=["-c", "<script>"])`.

**Decision tree:**
1. If `gh auth status` shows authenticated → use `gh` for everything
2. If `gh` is installed but not authenticated → use "gh auth" method below
3. If `gh` is not installed → use "git-only" method below (no sudo needed)

---

## Method 1: Git-Only Authentication (No gh, No sudo)

Works on any machine with `git` installed. No root access needed.

### Option A: HTTPS with Personal Access Token (Recommended)

Most portable method — works everywhere, no SSH config needed.

**Step 1: Create a personal access token**

Tell the user to go to: **https://github.com/settings/tokens**

- Click "Generate new token (classic)"
- Give it a descriptive name
- Select scopes:
  - `repo` (full repository access — read, write, push, PRs)
  - `workflow` (trigger and manage GitHub Actions)
  - `read:org` (if working with organization repos)
- Set expiration (90 days is a good default)
- Copy the token — it won't be shown again

**Step 2: Configure git to store the token**

```bash
# Set up the credential helper to cache credentials
# "store" saves to ~/.git-credentials in plaintext (simple, persistent)
git config --global credential.helper store

# Test with a git operation that triggers auth
# Username: <their-github-username>
# Password: <paste the personal access token, NOT their GitHub password>
git ls-remote https://github.com/<username>/<any-repo>.git
```

After entering credentials once, they're saved and reused.

**Alternative: cache helper (credentials expire from memory)**

```bash
git config --global credential.helper 'cache --timeout=28800'
```

**Alternative: set the token directly in the remote URL (per-repo)**

```bash
git remote set-url origin https://<username>:<token>@github.com/<owner>/<repo>.git
```

**Step 3: Configure git identity**

```bash
git config --global user.name "Your Name"
git config --global user.email "your-email@example.com"
```

**Step 4: Set GITHUB_TOKEN for API calls**

For `http_fetch` and `curl` API calls, set the token as an environment variable:

```bash
export GITHUB_TOKEN="ghp_your_token_here"
```

### Option B: SSH Authentication

**Step 1: Generate an SSH key**

```bash
ssh-keygen -t ed25519 -C "your-email@example.com" -f ~/.ssh/github_key -N ""
```

**Step 2: Add the public key to GitHub**

Read the public key with `file_read(path="~/.ssh/github_key.pub")` and tell the user to add it at **https://github.com/settings/ssh/new**.

Or add via API:

```
http_fetch(
  url="https://api.github.com/user/keys",
  method="POST",
  body={"title": "agent-key", "key": "<public-key-content>"}
)
```

**Step 3: Configure SSH**

Use `file_write(path="~/.ssh/config", content=...)` to add:

```
Host github.com
  HostName github.com
  User git
  IdentityFile ~/.ssh/github_key
  IdentitiesOnly yes
```

**Step 4: Test**

```bash
ssh -T git@github.com
```

---

## Method 2: gh CLI Authentication

If `gh` is installed:

```bash
# Interactive login (opens browser)
gh auth login

# Non-interactive with token
echo "$GITHUB_TOKEN" | gh auth login --with-token

# Verify
gh auth status
```

### Install gh (if needed)

```bash
# macOS
brew install gh

# Ubuntu/Debian
curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | sudo dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null
sudo apt update && sudo apt install gh

# Windows
winget install GitHub.cli
```

---

## Verification

After setup, verify auth works:

```bash
# Test git push/pull access
git ls-remote https://github.com/<owner>/<repo>.git

# Test API access (gh)
gh api user --jq '.login'

# Test API access (curl)
curl -s -H "Authorization: token $GITHUB_TOKEN" https://api.github.com/user | python3 -c "import sys,json; print(json.load(sys.stdin)['login'])"
```

If any method fails, fall back to the next option in the decision tree.
