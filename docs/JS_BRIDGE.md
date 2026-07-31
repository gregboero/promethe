# JavaScript Bridge API

The Promethe WASM app exposes a JavaScript bridge on `window` after the WASM module loads.
All functions are available at `http://localhost:56708`.

> **Note:** Wait for the app to fully initialize before calling bridge functions.
> A reliable check: `typeof window.prometheLogin === 'function'`

---

## `window.prometheLogin(gatewayUrl, user, password)`

Authenticates with the Promethe gateway as the remote owner and reloads the page to start a fresh
cookie-backed session. The bridge never receives or stores a session token.

### Parameters

| Parameter | Type | Description |
|---|---|---|
| `gatewayUrl` | `string` | Full URL of the gateway, e.g. `http://localhost:8080` |
| `user` | `string` | Remote access username (set in Settings → Accès distant) |
| `password` | `string` | Remote access password |

### Example

```javascript
await window.prometheLogin('http://localhost:8080', 'admin', 'mypassword')
// Page reloads — wait for reload to complete
```

### Behaviour

1. POSTs `{user, password, clientKind: "BROWSER"}` to `{gatewayUrl}/auth/login`.
2. On success, the gateway sends a `Secure`, `HttpOnly`, `SameSite=Strict` session cookie. The bridge
   stores only the URL and a non-secret browser-session hint in `localStorage`, then reloads.
3. On failure, returns `{ success: false, error }`.

---

## `window.prometheNavigate(route)`

Navigates the app to a named route without a page reload.

### Parameters

| Parameter | Type | Description |
|---|---|---|
| `route` | `string` | Route identifier (see table below) |

### Available Routes

| Route ID | Screen | Description |
|---|---|---|
| `sessions` | Sessions | Chat session list |
| `agents` | Agent Profiles | Configure and manage agents |
| `monitor` | Agent Monitor | Real-time agent activity |
| `stats` | Statistics | Usage statistics and charts |
| `memory` | Memory | Conversation memory viewer |
| `gepa` | GEPA Dashboard | Genetic evolution of prompts |
| `settings` | Settings | All configuration sections |
| `tools` | Tools | Available tools catalogue |
| `channels` | Channels | Messaging channel configuration |
| `scheduler` | Scheduler | Scheduled tasks |
| `mcp` | MCP | Model Context Protocol servers |
| `plugins` | Plugins | Plugin management |
| `knowledge` | Knowledge | RAG knowledge base |
| `orchestrator` | Orchestrator | Multi-agent orchestration |

### Example

```javascript
window.prometheNavigate('settings')
window.prometheNavigate('agents')
window.prometheNavigate('sessions')
```

---

## `window.prometheStatus()`

Returns the current application state as a plain JavaScript object.

### Returns

```typescript
{
  loggedIn: boolean,       // true if the non-secret browser-session hint is present
  route: string,           // current route id, e.g. "settings"
  gatewayUrl: string       // configured gateway URL
}
```

### Example

```javascript
const status = window.prometheStatus()
console.log(status)
// { loggedIn: true, route: "settings", gatewayUrl: "https://gateway.example" }
```

---

## `window.prometheScroll(deltaY, x?, y?)`

Scrolls within the current Compose screen by dispatching a `WheelEvent` directly on the
app root element. Works even though Compose renders on canvas (no DOM scrollbars).

### Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `deltaY` | `number` | — | Pixels to scroll. Positive = down, negative = up |
| `x` | `number?` | Center of viewport | X coordinate of the wheel event |
| `y` | `number?` | Center of viewport | Y coordinate of the wheel event |

### Example

```javascript
// Scroll down 300px
window.prometheScroll(300)

// Scroll up 200px
window.prometheScroll(-200)

// Scroll at a specific screen position
window.prometheScroll(150, 640, 400)
```

---

## `window.prometheScrollTo(totalDeltaY, steps?, delayMs?)`

Smooth scroll helper — splits a total scroll distance into multiple small steps with a delay
between each, giving Compose time to re-render between frames.

### Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `totalDeltaY` | `number` | — | Total pixels to scroll |
| `steps` | `number?` | `5` | Number of incremental scroll events |
| `delayMs` | `number?` | `100` | Milliseconds between each step |

### Example

```javascript
// Smooth scroll down 800px in 8 steps
await window.prometheScrollTo(800, 8, 120)

// Quick scroll down 500px
await window.prometheScrollTo(500)

// Scroll back to top
await window.prometheScrollTo(-2000, 10, 80)
```

---

## `window.prometheLogout()`

Calls `POST /auth/logout` with the cookie when possible, removes the non-secret session hint, and reloads
the page. The last gateway URL remains available as a convenience for the next login.

### Example

```javascript
window.prometheLogout()
// Page reloads — app shows login screen
```

---

## Full Session Example

```javascript
// 1. Login
await window.prometheLogin('http://localhost:8080', 'admin', 'secret')
// Page reloads automatically …

// 2. After reload — check status
const s = window.prometheStatus()
console.assert(s.loggedIn === true)

// 3. Navigate to settings
window.prometheNavigate('settings')

// 4. Scroll down to find 'Accès distant' section
await window.prometheScrollTo(600, 6, 100)

// 5. Navigate to agents
window.prometheNavigate('agents')

// 6. Scroll back to top
await window.prometheScrollTo(-1000)

// 7. Logout when done
window.prometheLogout()
```

---

## localStorage Keys

| Key | Value |
|---|---|
| `promethe_gatewayUrl` | The gateway URL entered during login |
| `promethe_browserSession` | A non-secret hint that a cookie-backed browser session was established |
| `promethe_backStack` | Last active route stack (restored on reload) |
