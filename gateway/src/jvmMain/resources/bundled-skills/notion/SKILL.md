---
name: notion
description: "Notion API + ntn CLI: pages, databases, markdown sync."
source: BUNDLED
requires_cli: ntn
platforms: jvm
---

# Notion

Talk to Notion two ways. Same integration token works for both — pick by what's available.

◆ **`ntn` CLI** — Notion's official CLI. Shorter syntax, one-line file uploads. macOS + Linux only as of May 2026. **Default when installed.**
◆ **HTTP API** — works everywhere including Windows. **Default fallback** when `ntn` isn't installed.

## Setup

### 1. Get an integration token (required for both paths)

1. Create an integration at https://notion.so/my-integrations
2. Copy the API key (starts with `ntn_` or `secret_`)
3. Store as environment variable: `NOTION_API_KEY=ntn_your_key_here`
4. **Share target pages/databases with the integration** in Notion: page menu `...` → `Connect to` → your integration name. Without this, the API returns 404 for that page even though it exists.

### 2. Install `ntn` (preferred path on macOS / Linux)

```bash
curl -fsSL https://ntn.dev | bash
# Or via npm (needs Node 22+, npm 10+)
npm install --global ntn
ntn --version    # verify
```

**Skip `ntn login` — use the integration token instead.** This works headlessly:

```bash
export NOTION_API_TOKEN=$NOTION_API_KEY      # ntn reads NOTION_API_TOKEN
export NOTION_KEYRING=0                       # don't try to use the OS keychain
```

### 3. Choose path at runtime

Use `execute_command(command="which", args=["ntn"])` to detect availability. Fall back to `http_fetch` if `ntn` is not installed.

## API Basics

`Notion-Version: 2025-09-03` is required on all HTTP requests. `ntn` handles this for you. In this version, what users call "databases" are called **data sources** in the API.

## Path A — `ntn` CLI (preferred, macOS / Linux)

### Raw API calls
```
execute_command(command="ntn", args=["api", "v1/users"])                         # GET
execute_command(command="ntn", args=["api", "v1/pages", "parent[page_id]=abc123",
  "properties[title][0][text][content]=Notes"])                                  # POST
execute_command(command="ntn", args=["api", "v1/pages/abc123", "-X", "PATCH",
  "archived:=true"])                                                             # PATCH
```

Syntax notes:
- `key=value` — string fields
- `key[nested]=value` — nested object fields
- `key:=value` — typed assignment (booleans, numbers, null, arrays)

### Search
```
execute_command(command="ntn", args=["api", "v1/search", "query=page title"])
```

### Read page metadata
```
execute_command(command="ntn", args=["api", "v1/pages/{page_id}"])
```

### Read page as Markdown (agent-friendly)
```
execute_command(command="ntn", args=["api", "v1/pages/{page_id}/markdown"])
```

### Read page content as blocks
```
execute_command(command="ntn", args=["api", "v1/blocks/{page_id}/children"])
```

### Create page from Markdown
```
execute_command(command="ntn", args=["api", "v1/pages",
  "parent[page_id]=xxx",
  "properties[title][0][text][content]=Notes from meeting",
  "markdown=# Agenda\n\n- Q3 roadmap\n- Hiring"])
```

### Patch a page with Markdown
```
execute_command(command="ntn", args=["api", "v1/pages/{page_id}/markdown", "-X", "PATCH",
  "markdown=## Update\n\nShipped the prototype."])
```

### Query a database (data source)
```
execute_command(command="ntn", args=["api", "v1/data_sources/{data_source_id}/query",
  "-X", "POST", "filter[property]=Status", "filter[select][equals]=Active"])
```

For complex queries with `sorts`, multiple filter clauses, or compound logic, pipe JSON in via stdin.

### File uploads (one-liner — biggest CLI win)
```
execute_command(command="ntn", args=["files", "create", "<", "photo.png"])
execute_command(command="ntn", args=["files", "create", "--external-url", "https://example.com/photo.png"])
execute_command(command="ntn", args=["files", "list"])
```

### Useful env vars
| Var | Effect |
|---|---|
| `NOTION_API_TOKEN` | Auth token — set this to your integration token |
| `NOTION_KEYRING=0` | File-based creds instead of OS keychain |
| `NOTION_WORKSPACE_ID` | Skip the workspace picker prompt |

## Path B — HTTP API (cross-platform, default on Windows)

All requests use `http_fetch` with standard headers:

```
http_fetch(
  url="https://api.notion.com/v1/...",
  method="GET",
  headers={
    "Authorization": "Bearer $NOTION_API_KEY",
    "Notion-Version": "2025-09-03",
    "Content-Type": "application/json"
  }
)
```

### Search
```
http_fetch(
  url="https://api.notion.com/v1/search",
  method="POST",
  body={"query": "page title"}
)
```

### Read page metadata
```
http_fetch(url="https://api.notion.com/v1/pages/{page_id}", method="GET")
```

### Read page as Markdown (agent-friendly)
```
http_fetch(url="https://api.notion.com/v1/pages/{page_id}/markdown", method="GET")
```

### Create page
```
http_fetch(
  url="https://api.notion.com/v1/pages",
  method="POST",
  body={
    "parent": {"page_id": "xxx"},
    "properties": {"title": [{"text": {"content": "New Page"}}]},
    "markdown": "# Content here"
  }
)
```

### Query a data source
```
http_fetch(
  url="https://api.notion.com/v1/data_sources/{data_source_id}/query",
  method="POST",
  body={
    "filter": {"property": "Status", "select": {"equals": "Active"}},
    "sorts": [{"property": "Date", "direction": "descending"}]
  }
)
```
