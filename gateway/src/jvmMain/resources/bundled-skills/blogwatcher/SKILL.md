---
name: blogwatcher
description: "Monitor blogs and RSS/Atom feeds via blogwatcher-cli tool."
source: BUNDLED
requires_cli: blogwatcher-cli
platforms: jvm
---

# Blogwatcher

Track blog and RSS/Atom feed updates with the `blogwatcher-cli` tool. Supports automatic feed discovery, HTML scraping fallback, OPML import, and read/unread article management.

## When to Use

- User wants to monitor blog or RSS/Atom feed updates
- User asks to track new posts from specific websites
- User wants to import OPML feed lists
- User needs a summary of recent posts from followed blogs

## Installation

Pick one method (use `execute_command` to run):

- **Go:** `go install github.com/JulienTant/blogwatcher-cli/cmd/blogwatcher-cli@latest`
- **Docker:** `docker run --rm -v blogwatcher-cli:/data ghcr.io/julientant/blogwatcher-cli`
- **Binary (Linux amd64):** `curl -sL https://github.com/JulienTant/blogwatcher-cli/releases/latest/download/blogwatcher-cli_linux_amd64.tar.gz | tar xz -C /usr/local/bin blogwatcher-cli`
- **Binary (macOS Apple Silicon):** `curl -sL https://github.com/JulienTant/blogwatcher-cli/releases/latest/download/blogwatcher-cli_darwin_arm64.tar.gz | tar xz -C /usr/local/bin blogwatcher-cli`

All releases: https://github.com/JulienTant/blogwatcher-cli/releases

### Docker with persistent storage

By default the database lives at `~/.blogwatcher-cli/blogwatcher-cli.db`. In Docker this is lost on container restart. Use `BLOGWATCHER_DB` env var or a volume mount to persist it:

```bash
docker run --rm -v blogwatcher-data:/root/.blogwatcher-cli ghcr.io/julientant/blogwatcher-cli
```

## Core Commands

All commands are run via `execute_command(command="blogwatcher-cli", args=[...])`.

### Add a feed

```bash
blogwatcher-cli add https://example.com/blog
```

The tool auto-discovers RSS/Atom feeds from the URL. If no feed is found, it falls back to HTML scraping.

### List feeds

```bash
blogwatcher-cli list
```

### Check for new articles

```bash
blogwatcher-cli check
```

### Read unread articles

```bash
blogwatcher-cli unread
```

### Mark articles as read

```bash
blogwatcher-cli read <article-id>
```

### Import OPML

```bash
blogwatcher-cli import feeds.opml
```

## Workflow

1. Use `execute_command` to add feeds the user wants to track
2. Periodically run `blogwatcher-cli check` to fetch new articles
3. Use `blogwatcher-cli unread` to list new content
4. Use `http_fetch` to read full article content when the user wants details
5. Summarize new posts and report to the user
