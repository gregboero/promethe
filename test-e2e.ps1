# Promethe E2E Test Script
# Usage: Set OPENAI_API_KEY env var, then run this script
# $env:OPENAI_API_KEY = "sk-..."
# .\test-e2e.ps1

$ErrorActionPreference = "Stop"
$baseUrl = "http://localhost:8080"

function Write-Test($name) {
    Write-Host "`n=== TEST: $name ===" -ForegroundColor Cyan
}

function Write-Pass($msg) {
    Write-Host "  PASS: $msg" -ForegroundColor Green
}

function Write-Fail($msg) {
    Write-Host "  FAIL: $msg" -ForegroundColor Red
}

# Get API key from credentials file
$credFile = Join-Path $env:USERPROFILE ".promethe\credentials.json"
if (Test-Path $credFile) {
    $creds = Get-Content $credFile | ConvertFrom-Json
    $apiKey = $creds.apiKey
    Write-Host "Using API key from credentials: $($apiKey.Substring(0, 12))..." -ForegroundColor Yellow
} else {
    $apiKey = ""
    Write-Host "No credentials file found, running without auth" -ForegroundColor Yellow
}

$headers = @{}
if ($apiKey) { $headers["Authorization"] = "Bearer $apiKey" }

# ── Test 1: Health Check ─────────────────────────────────────────
Write-Test "Health Check"
try {
    $health = Invoke-RestMethod -Uri "$baseUrl/health" -Method Get
    if ($health.status -eq "ok") {
        Write-Pass "status=ok, version=$($health.version), uptime=$($health.uptime)ms"
        if ($health.memory) {
            Write-Pass "memory: used=$($health.memory.used), max=$($health.memory.max)"
        }
    } else {
        Write-Fail "Unexpected status: $($health.status)"
    }
} catch {
    Write-Fail "Health check failed: $_"
    Write-Host "Is the gateway running? Start with:" -ForegroundColor Yellow
    Write-Host '  $env:OPENAI_API_KEY="sk-..."; $env:LLM_PROVIDER="openai"; $env:LLM_MODEL="gpt-4o-mini"; java -jar gateway\build\libs\gateway-all.jar'
    exit 1
}

# ── Test 2: A2A Agent Card ───────────────────────────────────────
Write-Test "A2A Agent Card"
try {
    $card = Invoke-RestMethod -Uri "$baseUrl/.well-known/agent.json" -Method Get
    if ($card.name -and $card.url) {
        Write-Pass "name=$($card.name), url=$($card.url)"
        Write-Pass "capabilities: streaming=$($card.capabilities.streaming), pushNotifications=$($card.capabilities.pushNotifications)"
    } else {
        Write-Fail "Agent card missing required fields"
    }
} catch {
    Write-Fail "Agent card failed: $_"
}

# ── Test 3: Create Session ───────────────────────────────────────
Write-Test "Create Session"
try {
    $session = Invoke-RestMethod -Uri "$baseUrl/api/sessions" -Method Post -Headers $headers `
        -ContentType "application/json" -Body '{}'
    if ($session.id) {
        Write-Pass "Session created: id=$($session.id)"
        $sessionId = $session.id
    } else {
        Write-Fail "No session ID returned"
        $sessionId = $null
    }
} catch {
    Write-Fail "Create session failed: $_"
    $sessionId = $null
}

# ── Test 4: List Sessions ────────────────────────────────────────
Write-Test "List Sessions"
try {
    $sessions = Invoke-RestMethod -Uri "$baseUrl/api/sessions" -Method Get -Headers $headers
    if ($sessions.sessions -and $sessions.sessions.Count -ge 1) {
        Write-Pass "Found $($sessions.sessions.Count) session(s)"
    } else {
        Write-Fail "No sessions found"
    }
} catch {
    Write-Fail "List sessions failed: $_"
}

# ── Test 5: Chat (LLM Roundtrip) ────────────────────────────────
Write-Test "Chat - LLM Roundtrip"
if ($sessionId) {
    try {
        $chatBody = @{
            sessionId = $sessionId
            message = "Hello! Reply with exactly: PROMETHE_OK"
        } | ConvertTo-Json

        $response = Invoke-RestMethod -Uri "$baseUrl/api/chat" -Method Post -Headers $headers `
            -ContentType "application/json" -Body $chatBody -TimeoutSec 120

        if ($response.content) {
            $preview = $response.content.Substring(0, [Math]::Min(100, $response.content.Length))
            Write-Pass "LLM responded: '$preview...'"
            Write-Pass "type=$($response.type), role=$($response.role)"
        } else {
            Write-Fail "Empty response from LLM"
        }
    } catch {
        Write-Fail "Chat roundtrip failed: $_"
    }
} else {
    Write-Fail "Skipped - no session"
}

# ── Test 6: Get Messages ─────────────────────────────────────────
Write-Test "Get Messages"
if ($sessionId) {
    try {
        $messages = Invoke-RestMethod -Uri "$baseUrl/api/sessions/$sessionId/messages" -Method Get -Headers $headers
        if ($messages.Count -ge 1) {
            Write-Pass "Found $($messages.Count) message(s) in session"
        } else {
            Write-Fail "No messages found"
        }
    } catch {
        Write-Fail "Get messages failed: $_"
    }
}

# ── Test 7: Export JSON ──────────────────────────────────────────
Write-Test "Export Session (JSON)"
if ($sessionId) {
    try {
        $export = Invoke-RestMethod -Uri "$baseUrl/api/sessions/$sessionId/export/json" -Method Get -Headers $headers
        if ($export.sessionId -eq $sessionId -and $export.messages) {
            Write-Pass "Export OK: $($export.messages.Count) messages, exportedAt=$($export.exportedAt)"
        } else {
            Write-Fail "Export missing data"
        }
    } catch {
        Write-Fail "Export JSON failed: $_"
    }
}

# ── Test 8: Export Markdown ──────────────────────────────────────
Write-Test "Export Session (Markdown)"
if ($sessionId) {
    try {
        $md = Invoke-WebRequest -Uri "$baseUrl/api/sessions/$sessionId/export/markdown" -Method Get -Headers $headers
        if ($md.Content -and $md.Content.Contains("#")) {
            Write-Pass "Markdown export OK: $($md.Content.Length) chars"
        } else {
            Write-Fail "Markdown export empty"
        }
    } catch {
        Write-Fail "Export MD failed: $_"
    }
}

# ── Test 9: Stats ────────────────────────────────────────────────
Write-Test "Stats"
try {
    $stats = Invoke-RestMethod -Uri "$baseUrl/api/stats" -Method Get -Headers $headers
    Write-Pass "totalTokens=$($stats.totalTokens), totalRequests=$($stats.totalRequests), cost=$($stats.estimatedCost)"
} catch {
    Write-Fail "Stats failed: $_"
}

# ── Test 10: Rate Limiter Headers ────────────────────────────────
Write-Test "Rate Limit Headers"
try {
    $resp = Invoke-WebRequest -Uri "$baseUrl/api/sessions" -Method Get -Headers $headers
    $limit = $resp.Headers["X-RateLimit-Limit"]
    $remaining = $resp.Headers["X-RateLimit-Remaining"]
    if ($limit) {
        Write-Pass "X-RateLimit-Limit=$limit, X-RateLimit-Remaining=$remaining"
    } else {
        Write-Fail "No rate limit headers"
    }
} catch {
    Write-Fail "Rate limit test failed: $_"
}

# ── Summary ──────────────────────────────────────────────────────
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "E2E Test Suite Complete!" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan
