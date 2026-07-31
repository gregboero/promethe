---
name: huggingface-hub
description: "HuggingFace hf CLI: search/download/upload models, datasets."
source: BUNDLED
requires_cli: hf
platforms: jvm
---

# Hugging Face CLI (`hf`) Reference Guide

The `hf` command is the modern command-line interface for interacting with the Hugging Face Hub, providing tools to manage repositories, models, datasets, and Spaces.

> **IMPORTANT:** The `hf` command replaces the now deprecated `huggingface-cli` command.

## Quick Start

- **Installation:**
  ```
  execute_command(command="sh", args=["-c", "curl -LsSf https://hf.co/cli/install.sh | bash -s"])
  ```
- **Help:** `execute_command(command="hf", args=["--help"])`
- **Authentication:** Recommended via `HF_TOKEN` environment variable or the `--token` flag.

---

## Core Commands

### General Operations

- **Download files:**
  ```
  execute_command(command="hf", args=["download", "REPO_ID"])
  execute_command(command="hf", args=["download", "REPO_ID", "--include", "*.safetensors"])
  ```
- **Upload files/folders (single-commit):**
  ```
  execute_command(command="hf", args=["upload", "REPO_ID", "LOCAL_PATH"])
  ```
- **Resumable upload for large directories:**
  ```
  execute_command(command="hf", args=["upload-large-folder", "REPO_ID", "LOCAL_PATH"])
  ```
- **Sync files between local dir and bucket:**
  ```
  execute_command(command="hf", args=["sync"])
  ```
- **Environment and version:**
  ```
  execute_command(command="hf", args=["env"])
  execute_command(command="hf", args=["version"])
  ```

### Authentication (`hf auth`)

- **Login / Logout:**
  ```
  execute_command(command="hf", args=["auth", "login"])
  execute_command(command="hf", args=["auth", "logout"])
  ```
  Manage sessions using tokens from [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens).
- **List / Switch tokens:**
  ```
  execute_command(command="hf", args=["auth", "list"])
  execute_command(command="hf", args=["auth", "switch"])
  ```
- **Identify current account:**
  ```
  execute_command(command="hf", args=["auth", "whoami"])
  ```

### Repository Management (`hf repos`)

- **Create a repository:**
  ```
  execute_command(command="hf", args=["repos", "create", "my-model", "--type", "model"])
  ```
- **Delete a repository:**
  ```
  execute_command(command="hf", args=["repos", "delete", "user/my-model"])
  ```
- **Duplicate (clone to a new ID):**
  ```
  execute_command(command="hf", args=["repos", "duplicate", "source/repo", "--to", "user/new-repo"])
  ```
- **Move between namespaces:**
  ```
  execute_command(command="hf", args=["repos", "move", "old-name", "new-name"])
  ```
- **Branch and tag management:**
  ```
  execute_command(command="hf", args=["repos", "branch", "create", "REPO_ID", "my-branch"])
  execute_command(command="hf", args=["repos", "tag", "create", "REPO_ID", "v1.0"])
  ```

### Search and Discovery

- **Search models:**
  ```
  execute_command(command="hf", args=["search", "models", "--query", "text-generation", "--sort", "downloads"])
  ```
- **Search datasets:**
  ```
  execute_command(command="hf", args=["search", "datasets", "--query", "sentiment"])
  ```

### Model and Dataset Inspection

- **View repo info:**
  ```
  execute_command(command="hf", args=["repo", "info", "REPO_ID"])
  ```
- **List repo files:**
  ```
  execute_command(command="hf", args=["repo", "ls", "REPO_ID"])
  ```

## API Fallback

When `hf` CLI is unavailable, use `http_fetch` against the Hub API:

```
http_fetch(
  url="https://huggingface.co/api/models?search=text-generation&sort=downloads",
  method="GET",
  headers={"Authorization": "Bearer $HF_TOKEN"}
)

http_fetch(
  url="https://huggingface.co/api/repos/create",
  method="POST",
  headers={"Authorization": "Bearer $HF_TOKEN"},
  body={"type": "model", "name": "my-model"}
)
```
