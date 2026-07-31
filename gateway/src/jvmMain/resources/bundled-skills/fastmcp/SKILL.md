---
name: fastmcp
description: "Create MCP servers for users via chat — Python FastMCP framework for rapid tool/resource creation."
source: BUNDLED
requires_cli: python,uv
platforms: jvm
---

# FastMCP — Create MCP Servers in Chat

[FastMCP](https://github.com/jlowin/fastmcp) is the fastest way to build MCP (Model Context Protocol) servers in Python. Use this skill when a user asks to create a custom MCP server for their tools, APIs, or data sources.

## When to Use

- User says "crée-moi un MCP pour [service X]"
- User wants to expose a REST API, database, or local tool as an MCP server
- User needs a custom tool that Promethe can call via `McpBridge`
- User wants to prototype a new integration quickly

## Install

```
execute_command(command="uv", args=["pip", "install", "fastmcp"])
```

## Minimal MCP Server (5 lines)

```python
# my_server.py
from fastmcp import FastMCP

mcp = FastMCP("My Tools")

@mcp.tool()
def greet(name: str) -> str:
    """Greet someone by name."""
    return f"Hello, {name}!"
```

Run:
```
execute_command(command="uv", args=["run", "--with", "fastmcp", "fastmcp", "run", "my_server.py"])
```

## Common Patterns

### REST API Wrapper
```python
from fastmcp import FastMCP
import httpx

mcp = FastMCP("Jira Tools")

@mcp.tool()
async def get_issue(issue_key: str) -> dict:
    """Get a Jira issue by key (e.g. PROJ-123)."""
    async with httpx.AsyncClient() as client:
        r = await client.get(
            f"https://mycompany.atlassian.net/rest/api/3/issue/{issue_key}",
            headers={"Authorization": f"Bearer {os.environ['JIRA_TOKEN']}"}
        )
        return r.json()

@mcp.tool()
async def create_issue(project: str, summary: str, description: str = "") -> dict:
    """Create a new Jira issue."""
    async with httpx.AsyncClient() as client:
        r = await client.post(
            "https://mycompany.atlassian.net/rest/api/3/issue",
            headers={"Authorization": f"Bearer {os.environ['JIRA_TOKEN']}"},
            json={"fields": {"project": {"key": project}, "summary": summary,
                             "description": description, "issuetype": {"name": "Task"}}}
        )
        return r.json()
```

### Database Query Tool
```python
from fastmcp import FastMCP
import sqlite3

mcp = FastMCP("DB Tools")

@mcp.tool()
def query_db(sql: str) -> list[dict]:
    """Run a read-only SQL query on the local database."""
    conn = sqlite3.connect("app.db")
    conn.row_factory = sqlite3.Row
    rows = conn.execute(sql).fetchall()
    return [dict(row) for row in rows]
```

### Resource Exposure (for context)
```python
@mcp.resource("config://app")
def get_config() -> str:
    """Return the current app configuration."""
    return Path("config.yaml").read_text()

@mcp.resource("schema://database")
def get_schema() -> str:
    """Return the database schema."""
    conn = sqlite3.connect("app.db")
    tables = conn.execute("SELECT sql FROM sqlite_master WHERE type='table'").fetchall()
    return "\n".join(t[0] for t in tables)
```

### Prompt Templates
```python
@mcp.prompt()
def review_code(code: str) -> str:
    """Generate a code review prompt."""
    return f"Review this code for bugs, security issues, and style:\n\n```\n{code}\n```"
```

## Register in Promethe

Once the MCP server is created, register it in Promethe's config:

```json
// .promethe/mcp-servers.json
{
  "servers": [
    {
      "name": "jira-tools",
      "command": "uv",
      "args": ["run", "--with", "fastmcp,httpx", "fastmcp", "run", "jira_server.py"],
      "env": {"JIRA_TOKEN": "${JIRA_TOKEN}"}
    }
  ]
}
```

The agent can then call the tools via `McpBridge`:
```
mcp_call(server="jira-tools", tool="get_issue", args={"issue_key": "PROJ-123"})
```

## Workflow: User Asks for a New MCP

1. **Understand** what API/service/data the user wants to expose
2. **Scaffold** the `server.py` with `FastMCP` + tools
3. **Write** the file using `file_write`
4. **Test** with `execute_command(command="uv", args=["run", "--with", "fastmcp", "fastmcp", "run", "server.py"])`
5. **Register** in `.promethe/mcp-servers.json`
6. **Verify** the tools are accessible via `mcp_call`
