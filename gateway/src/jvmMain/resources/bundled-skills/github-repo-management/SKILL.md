---
name: github-repo-management
description: "Clone/create/fork repos; manage remotes, releases."
source: BUNDLED
requires_cli: git,gh
platforms: jvm
---

# GitHub Repository Management

Create, clone, fork, configure, and manage GitHub repositories. Each section shows `gh` first, then the `git` + `curl`/`http_fetch` fallback.

## Prerequisites

- Authenticated with GitHub (see `github-auth` skill)
- `GITHUB_TOKEN` environment variable set (for curl/http_fetch fallback)

### Setup

```bash
if command -v gh &>/dev/null && gh auth status &>/dev/null; then
  AUTH="gh"
else
  AUTH="git"
fi

# Get your GitHub username
if [ "$AUTH" = "gh" ]; then
  GH_USER=$(gh api user --jq '.login')
else
  GH_USER=$(curl -s -H "Authorization: token $GITHUB_TOKEN" https://api.github.com/user | python3 -c "import sys,json; print(json.load(sys.stdin)['login'])")
fi
```

If inside a repo already:

```bash
REMOTE_URL=$(git remote get-url origin)
OWNER_REPO=$(echo "$REMOTE_URL" | sed -E 's|.*github\.com[:/]||; s|\.git$||')
OWNER=$(echo "$OWNER_REPO" | cut -d/ -f1)
REPO=$(echo "$OWNER_REPO" | cut -d/ -f2)
```

Run via `execute_command(command="bash", args=["-c", "<script>"])`.

---

## 1. Cloning Repositories

Cloning is pure `git`:

```bash
# Clone via HTTPS
git clone https://github.com/owner/repo-name.git

# Clone into a specific directory
git clone https://github.com/owner/repo-name.git ./my-local-dir

# Shallow clone (faster for large repos)
git clone --depth 1 https://github.com/owner/repo-name.git

# Clone a specific branch
git clone --branch develop https://github.com/owner/repo-name.git

# Clone via SSH
git clone git@github.com:owner/repo-name.git
```

**With gh (shorthand):**

```bash
gh repo clone owner/repo-name
gh repo clone owner/repo-name -- --depth 1
```

## 2. Creating Repositories

**With gh:**

```bash
gh repo create my-new-project --public --clone
gh repo create my-new-project --private --description "A useful tool" --license MIT --clone
gh repo create my-org/my-new-project --public --clone

# From existing local directory
cd /path/to/existing/project
gh repo create my-project --source . --public --push
```

**With http_fetch:**

```
http_fetch(
  url="https://api.github.com/user/repos",
  method="POST",
  body={
    "name": "my-new-project",
    "description": "A useful tool",
    "private": false,
    "auto_init": true,
    "license_template": "mit"
  }
)
```

Then clone and set up locally:

```bash
git clone https://github.com/$GH_USER/my-new-project.git
cd my-new-project
```

To create under an organization:

```
http_fetch(
  url="https://api.github.com/orgs/{org}/repos",
  method="POST",
  body={"name": "my-new-project", "private": false}
)
```

### From a Template

**With gh:**

```bash
gh repo create my-new-app --template owner/template-repo --public --clone
```

**With http_fetch:**

```
http_fetch(
  url="https://api.github.com/repos/{owner}/{template-repo}/generate",
  method="POST",
  body={"owner": "{username}", "name": "my-new-app", "private": false}
)
```

## 3. Forking Repositories

**With gh:**

```bash
gh repo fork owner/repo-name --clone
```

**With http_fetch:**

```
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}/forks",
  method="POST"
)
```

Then clone:

```bash
git clone https://github.com/$GH_USER/repo-name.git
cd repo-name
git remote add upstream https://github.com/owner/repo-name.git
```

## 4. Managing Remotes

```bash
# List remotes
git remote -v

# Add upstream
git remote add upstream https://github.com/original-owner/repo.git

# Sync fork with upstream
git fetch upstream
git checkout main
git merge upstream/main
git push origin main
```

## 5. Releases

**With gh:**

```bash
gh release create v1.0.0 --title "v1.0.0" --notes "First stable release"
gh release create v1.0.0 --generate-notes
gh release list
```

**With http_fetch:**

```
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}/releases",
  method="POST",
  body={
    "tag_name": "v1.0.0",
    "name": "v1.0.0",
    "body": "First stable release",
    "draft": false,
    "prerelease": false
  }
)
```

## 6. Repository Settings

**With http_fetch:**

```
# Update repo settings
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}",
  method="PATCH",
  body={"description": "Updated description", "has_issues": true, "has_wiki": false}
)

# Set branch protection
http_fetch(
  url="https://api.github.com/repos/{owner}/{repo}/branches/main/protection",
  method="PUT",
  body={
    "required_status_checks": {"strict": true, "contexts": ["ci/build"]},
    "enforce_admins": true,
    "required_pull_request_reviews": {"required_approving_review_count": 1}
  }
)
```
