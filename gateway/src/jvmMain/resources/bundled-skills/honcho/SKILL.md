---
name: honcho
description: "Cross-session memory backend for agents — user context, metamemory, dialectic personalization."
source: BUNDLED
requires_cli: python,uv
platforms: jvm
---

# Honcho — Promethe Memory Backend

[Honcho](https://github.com/plastic-labs/honcho) is an open-source user context management system for AI agents. It provides persistent, cross-session memory with user modeling and dialectic personalization.

## When to Use

- You need **cross-session** memory that persists beyond the current conversation
- You want **per-user** memory profiles (preferences, history, context)
- The native `MemorySave/Search` is too simple for your use case
- You need **metamemory** — the agent reasoning about what it remembers
- You want **dialectic personalization** — adapting responses based on user model

## Architecture: Honcho vs Native Memory

| Feature | Native (`MemorySave`) | Honcho |
|---------|----------------------|--------|
| Persistence | Session-scoped | Cross-session, database-backed |
| User modeling | None | Per-user profiles & preferences |
| Metamemory | None | Agent can reason about its memories |
| Multi-agent | Single agent | Shared memory across agents |
| Search | Simple keyword | Semantic + metadata filtering |
| Setup | Zero config | Requires Honcho server |

## Setup

### Option 1: Honcho Cloud
```
execute_command(command="uv", args=["pip", "install", "honcho-ai"])
```
Set `HONCHO_API_KEY` in environment.

### Option 2: Self-hosted
```
execute_command(command="docker", args=["run", "-d", "-p", "8000:8000", "plasticlabs/honcho:latest"])
```

## Core API

```python
from honcho import Honcho

honcho = Honcho(base_url="http://localhost:8000")

# Create app & user
app = honcho.apps.get_or_create("promethe")
user = honcho.apps.users.get_or_create(app_id=app.id, name="greg")

# Create session with metadata
session = honcho.apps.users.sessions.create(
    app_id=app.id, user_id=user.id,
    metadata={"topic": "kotlin-refactoring"}
)

# Store a message
honcho.apps.users.sessions.messages.create(
    app_id=app.id, user_id=user.id, session_id=session.id,
    content="User prefers functional style over OOP",
    is_user=False
)

# Store metamemory
honcho.apps.users.metamemories.create(
    app_id=app.id, user_id=user.id,
    content="User is an expert Kotlin developer who prefers KMP architecture"
)

# Query memories
memories = honcho.apps.users.metamemories.list(
    app_id=app.id, user_id=user.id
)
```

## Integration with Promethe

Honcho can run **alongside** native memory as an optional backend:

```kotlin
// In Promethe's MemoryService
interface MemoryBackend {
    suspend fun save(key: String, value: String, metadata: Map<String, String>)
    suspend fun search(query: String, limit: Int): List<Memory>
}

class NativeMemoryBackend : MemoryBackend { /* MemorySave/Search */ }
class HonchoMemoryBackend(val client: HonchoClient) : MemoryBackend { /* Honcho API */ }
```

Configure via settings which backend to use per session.
