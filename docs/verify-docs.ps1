#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Verifies consistency between Promethe docs and source code.
    Detects drift before it becomes a factual error.

.EXAMPLE
    ./verify-docs.ps1
    ./verify-docs.ps1 -Verbose
#>

[CmdletBinding()]
param()

$ErrorActionPreference = "Continue"
$script:errors = @()
$script:warnings = @()
$script:checks = 0
$script:passed = 0

$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path "$root/settings.gradle.kts")) {
    $root = $PSScriptRoot
    if (-not (Test-Path "$root/settings.gradle.kts")) {
        Write-Error "Cannot find project root. Run from promethe/ or promethe/docs/"
        exit 1
    }
}

function Test-Check {
    param([string]$Name, [scriptblock]$Check)
    $script:checks++
    try {
        $result = & $Check
        if ($result -eq $true) {
            $script:passed++
            Write-Host "  [OK] $Name" -ForegroundColor Green
        }
    } catch {
        $script:errors += "[$Name] Exception: $_"
        Write-Host "  [FAIL] $Name -- $_" -ForegroundColor Red
    }
}

function Add-Error {
    param([string]$msg)
    $script:errors += $msg
    Write-Host "  [FAIL] $msg" -ForegroundColor Red
}

function Add-Warning {
    param([string]$msg)
    $script:warnings += $msg
    Write-Host "  [WARN] $msg" -ForegroundColor Yellow
}

# ==============================================================
Write-Host ""
Write-Host "--- Doc verification: Promethe ---" -ForegroundColor Cyan
Write-Host "    Root: $root" -ForegroundColor DarkGray
Write-Host ""

# -- 1. Stack versions ----------------------------------------
Write-Host "[1] Stack versions" -ForegroundColor White

$tomlPath = "$root/gradle/libs.versions.toml"
$tomlContent = Get-Content $tomlPath -Raw -Encoding UTF8

# Extract versions from TOML (key = "value")
$versions = @{}
foreach ($line in ($tomlContent -split "`n")) {
    if ($line -match '^([a-zA-Z][\w-]*)\s*=\s*"([^"]*)"') {
        $versions[$Matches[1]] = $Matches[2]
    }
}

$docsToCheck = @(
    "$root/.promethe.md",
    "$root/README.md",
    "$root/docs/INDEX.md",
    "$root/docs/ARCHITECTURE.md"
)

$versionChecks = @(
    @{ Key = "ktor"; Regex = 'Ktor\s+(\d+\.\d+\.\d+)'; Label = "Ktor" },
    @{ Key = "exposed"; Regex = 'Exposed\s+(\d+\.\d+\.\d+)'; Label = "Exposed" },
    @{ Key = "kotlin"; Regex = 'Kotlin\s+(\d+\.\d+\.\d+)'; Label = "Kotlin" },
    @{ Key = "koog"; Regex = 'Koog\s+(?:SDK\s+)?([\d\.]+(?:-[\w]+)?)'; Label = "Koog SDK" },
    @{ Key = "sqlite-jdbc"; Regex = 'SQLite.*?(\d+\.\d+\.\d+\.\d+)'; Label = "SQLite JDBC" },
    @{ Key = "compose-multiplatform"; Regex = 'Compose\s+(?:Multiplatform\s+)?(\d+\.\d+\.\d+)'; Label = "Compose" }
)

foreach ($vc in $versionChecks) {
    $expected = $versions[$vc.Key]
    if (-not $expected) { continue }

    Test-Check "$($vc.Label) version = $expected" {
        $stale = @()
        foreach ($doc in $docsToCheck) {
            if (-not (Test-Path $doc)) { continue }
            $content = Get-Content $doc -Raw -Encoding UTF8
            $rxMatches = [regex]::Matches($content, $vc.Regex)
            foreach ($m in $rxMatches) {
                $docVersion = $m.Groups[1].Value
                if ($docVersion -ne $expected) {
                    $stale += "$(Split-Path -Leaf $doc) says $($vc.Label) $docVersion"
                }
            }
        }
        if ($stale.Count -gt 0) {
            foreach ($s in $stale) { Add-Error "$($vc.Label): $s (actual: $expected)" }
            return $false
        }
        return $true
    }
}

# Check for historically false stack references
Test-Check "No SQLDelight references (replaced by Exposed)" {
    $found = @()
    foreach ($doc in $docsToCheck) {
        if (-not (Test-Path $doc)) { continue }
        if ((Get-Content $doc -Raw -Encoding UTF8) -match "SQLDelight") {
            $found += Split-Path -Leaf $doc
        }
    }
    if ($found.Count -gt 0) {
        Add-Error "SQLDelight still mentioned in: $($found -join ', ')"
        return $false
    }
    return $true
}

Test-Check "No Netty references (replaced by CIO)" {
    $found = @()
    foreach ($doc in $docsToCheck) {
        if (-not (Test-Path $doc)) { continue }
        $c = Get-Content $doc -Raw -Encoding UTF8
        if ($c -match "Ktor.*Netty" -or $c -match "Netty.*Ktor") {
            $found += Split-Path -Leaf $doc
        }
    }
    if ($found.Count -gt 0) {
        Add-Error "Netty still mentioned in: $($found -join ', ')"
        return $false
    }
    return $true
}

# -- 2. Channel count -----------------------------------------
Write-Host ""
Write-Host "[2] Channel count" -ForegroundColor White

$channelsDir = "$root/shared/src/jvmMain/kotlin/dev/promethe/channels"

Test-Check "Channel count matches docs" {
    if (-not (Test-Path $channelsDir)) {
        Add-Warning "channels/ dir not found -- skip"
        return $true
    }

    $dedicatedFiles = @(Get-ChildItem "$channelsDir/*.kt" | Where-Object { $_.Name -ne "ExtraChannels.kt" }).Count

    $extraCount = 0
    $extraFile = "$channelsDir/ExtraChannels.kt"
    if (Test-Path $extraFile) {
        $extraContent = Get-Content $extraFile -Raw -Encoding UTF8
        $extraCount = ([regex]::Matches($extraContent, '(?m)^class \w+Channel\(')).Count
    }

    $totalChannels = $dedicatedFiles + $extraCount

    $allDocs = @()
    $allDocs += Get-ChildItem "$root/docs/*.md" -ErrorAction SilentlyContinue
    $allDocs += Get-Item "$root/README.md" -ErrorAction SilentlyContinue

    $stale = @()
    foreach ($doc in $allDocs) {
        $content = Get-Content $doc.FullName -Raw -Encoding UTF8
        # Match total channel counts like "19 canaux" but exclude partial counts like "13 canaux additionnels"
        $rxMatches = [regex]::Matches($content, '(\d+)\s+canaux(?!\s+additionnel)')
        foreach ($m in $rxMatches) {
            $docCount = [int]$m.Groups[1].Value
            if ($docCount -ne $totalChannels) {
                $stale += "$($doc.Name) says $docCount channels"
            }
        }
    }

    if ($stale.Count -gt 0) {
        foreach ($s in $stale) { Add-Error "Channels: $s (actual: $totalChannels)" }
        return $false
    }
    Write-Verbose "  Channels detected: $totalChannels ($dedicatedFiles files + $extraCount in ExtraChannels.kt)"
    return $true
}

# -- 3. Phantom routes ----------------------------------------
Write-Host ""
Write-Host "[3] Phantom routes (historically false patterns)" -ForegroundColor White

$phantomPatterns = @(
    @{ Pattern = '/api/chat'; Label = "POST /api/chat (does not exist)" },
    @{ Pattern = '/ws/chat[^/]'; Label = "WS /ws/chat (does not exist)" },
    @{ Pattern = 'agent-card\.json'; Label = "agent-card.json (actual: agent.json)" },
    @{ Pattern = 'POST /a2a\b'; Label = "POST /a2a (actual: POST /agents/a2a)" },
    @{ Pattern = 'POST /execute'; Label = "POST /execute (removed)" },
    @{ Pattern = '/api/setup/remote'; Label = "legacy remote setup route (removed)" },
    @{ Pattern = '/api/auth/login'; Label = "legacy /api/auth/login route (actual: /auth/login)" },
    @{ Pattern = '\?token='; Label = "URL query credentials (rejected)" },
    @{ Pattern = 'promethe_api_key'; Label = "persisted browser API key (removed)" }
)

$allMdFiles = @()
$allMdFiles += Get-ChildItem "$root/docs/*.md" -ErrorAction SilentlyContinue
$allMdFiles += Get-Item "$root/README.md" -ErrorAction SilentlyContinue
$allMdFiles += Get-Item "$root/.promethe.md" -ErrorAction SilentlyContinue

foreach ($pp in $phantomPatterns) {
    Test-Check "No phantom: $($pp.Label)" {
        $found = @()
        foreach ($doc in $allMdFiles) {
            if (-not $doc -or -not (Test-Path $doc.FullName)) { continue }
            $content = Get-Content $doc.FullName -Raw -Encoding UTF8
            # Check each line for the pattern, but skip lines that are negations/warnings
            foreach ($line in ($content -split "`n")) {
                # -cmatch: routes are lowercase; -match would false-positive on
                # paths like dev/promethe/api/ChatModels.kt matching '/api/chat'
                if ($line -cmatch $pp.Pattern) {
                    # Skip if the line is a warning/negation about the route not existing
                    if ($line -match "n'existe pas|does not exist|not exist|supprim|removed|remplac") { continue }
                    $found += $doc.Name
                    break
                }
            }
        }
        if ($found.Count -gt 0) {
            Add-Error "Phantom route '$($pp.Label)' found in: $($found -join ', ')"
            return $false
        }
        return $true
    }
}

# -- 4. Security route contracts ------------------------------
Write-Host ""
Write-Host "[4] Security route contracts" -ForegroundColor White

function Test-RouteContract {
    param(
        [string]$Name,
        [string[]]$Documentation,
        [string[]]$RequiredDocumentation,
        [string[]]$Sources,
        [string[]]$RequiredSource
    )

    Test-Check $Name {
        $docContent = ""
        foreach ($path in $Documentation) {
            if (-not (Test-Path $path)) {
                Add-Error "Route contract '$Name' missing documentation file: $path"
                return $false
            }
            $docContent += "`n" + (Get-Content $path -Raw -Encoding UTF8)
        }

        $sourceContent = ""
        foreach ($path in $Sources) {
            if (-not (Test-Path $path)) {
                Add-Error "Route contract '$Name' missing source file: $path"
                return $false
            }
            $sourceContent += "`n" + (Get-Content $path -Raw -Encoding UTF8)
        }

        $missingDocs = @($RequiredDocumentation | Where-Object { $docContent -notmatch [regex]::Escape($_) })
        $missingSource = @($RequiredSource | Where-Object { $sourceContent -notmatch [regex]::Escape($_) })
        if ($missingDocs.Count -gt 0) {
            Add-Error "Route contract '$Name' missing docs: $($missingDocs -join ', ')"
        }
        if ($missingSource.Count -gt 0) {
            Add-Error "Route contract '$Name' missing implementation: $($missingSource -join ', ')"
        }
        return $missingDocs.Count -eq 0 -and $missingSource.Count -eq 0
    }
}

Test-RouteContract -Name "A2A endpoint is documented and mounted" -Documentation @("$root/README.md", "$root/docs/API.md") -RequiredDocumentation @("POST /agents/a2a") -Sources @("$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/OmnichannelGateway.kt", "$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/PrometheA2AExecutor.kt") -RequiredSource @('route("agents")', 'transportRoutes(route, "/a2a")')

Test-RouteContract -Name "Remote owner and sessions are documented and mounted" -Documentation @("$root/docs/API.md", "$root/docs/SECURITY.md") -RequiredDocumentation @("POST /auth/login", "POST /auth/logout", "PUT /api/v1/security/remote-owner") -Sources @("$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/SystemAndAuthRoutes.kt") -RequiredSource @('post("/auth/login")', 'post("/auth/logout")', 'put("/api/v1/security/remote-owner")')

Test-RouteContract -Name "OAuth callback is fixed and protected by owner sessions" -Documentation @("$root/docs/API.md", "$root/docs/CONFIGURATION.md") -RequiredDocumentation @("/auth/oauth/callback", "OAUTH_REDIRECT_URI", "GET /api/v1/oauth/connections", "DELETE /api/v1/oauth/{provider}") -Sources @("$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/auth/OAuthRoutes.kt", "$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/auth/OAuthManager.kt") -RequiredSource @('get("/callback")', 'get("/connections")', 'delete("/{provider}")', 'completeAuthorization(code, state')

Test-RouteContract -Name "Wasm bridge documents cookie-backed login without a persisted token" -Documentation @("$root/docs/JS_BRIDGE.md", "$root/docs/SECURITY.md") -RequiredDocumentation @("POST /auth/login", "HttpOnly", "promethe_browserSession") -Sources @("$root/composeApp/src/wasmJsMain/kotlin/dev/promethe/app/Main.kt", "$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/SystemAndAuthRoutes.kt") -RequiredSource @("clientKind: 'BROWSER'", 'post("/auth/login")', 'httpOnly = true')

Test-RouteContract -Name "Persistent MCP management is documented and mounted" -Documentation @("$root/docs/API.md", "$root/docs/MCP.md") -RequiredDocumentation @("PUT /api/v1/mcp/servers/{id}", "DELETE /api/v1/mcp/servers/{id}", "MCP_SERVERS") -Sources @("$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/McpManagementRoutes.kt", "$root/shared/src/jvmMain/kotlin/dev/promethe/core/mcp/McpConfigurationStore.kt") -RequiredSource @('put("/mcp/servers/{id}")', 'delete("/mcp/servers/{id}")', 'encryptedSecrets')

Test-RouteContract -Name "MCP elicitation is documented and mounted" -Documentation @("$root/docs/API.md", "$root/docs/MCP.md") -RequiredDocumentation @("GET /api/v1/approval/mcp/pending", "POST /api/v1/approval/mcp/{id}") -Sources @("$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/McpElicitationRoutes.kt", "$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/OmnichannelGateway.kt", "$root/shared/src/jvmMain/kotlin/dev/promethe/core/JvmMcpTransportFactory.kt") -RequiredSource @('get("/approval/mcp/pending")', 'post("/approval/mcp/{id}")', 'mcpElicitationRoutes(mcpElicitationBroker)', 'mapOf("elicitation/create"')

Test-RouteContract -Name "Discord live policy is documented and mounted" -Documentation @("$root/docs/API.md", "$root/docs/CHANNELS.md", "$root/docs/SECURITY.md") -RequiredDocumentation @("GET /api/v1/channels/discord/policy", "PUT /api/v1/channels/discord/policy/users/{userId}", "discord_policy") -Sources @("$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/DiscordPolicyRoutes.kt", "$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/OmnichannelGateway.kt") -RequiredSource @('get("/channels/discord/policy")', 'put("/channels/discord/policy/users/{userId}")', 'discordPolicyRoutes(discordPolicyService)')

Test-RouteContract -Name "Plugin API documents only mounted management routes" -Documentation @("$root/docs/API.md", "$root/docs/PLUGINS.md") -RequiredDocumentation @("/api/v1/plugins/:name/toggle", "501 Not Implemented") -Sources @("$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/PluginRoutes.kt") -RequiredSource @('get("/plugins")', 'post("/plugins/{name}/toggle")', 'get("/plugins/{name}")')

Test-RouteContract -Name "Capability catalog and generated OpenAPI are documented and mounted" -Documentation @("$root/docs/API.md") -RequiredDocumentation @("GET /api/v1/capabilities", "GET /api/v1/openapi.json") -Sources @("$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/CapabilityRoutes.kt", "$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/OpenApiRoutes.kt", "$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/OmnichannelGateway.kt") -RequiredSource @('get("/api/v1/capabilities")', 'get("/api/v1/openapi.json")', 'openApiRoutes()')

Test-Check "Management REST routes are versioned under /api/v1" {
    $gateway = Get-Content "$root/gateway/src/jvmMain/kotlin/dev/promethe/gateway/OmnichannelGateway.kt" -Raw -Encoding UTF8
    if ($gateway -notmatch [regex]::Escape('route("api")') -or $gateway -notmatch [regex]::Escape('route("v1")')) {
        Add-Error 'OmnichannelGateway does not mount management routes below route("api")/route("v1")'
        return $false
    }
    return $true
}

Test-Check "No unversioned management API paths in documentation" {
    $resources = 'sessions|agents|memory|feedback|scheduler|channels|config|mcp|skills|orchestrator|goal|status|rag|plugins|voice|tts|webhooks|gepa|stats|settings|security|system|oauth|context'
    $pattern = "/api/(?!v1/)(?:$resources)(?:/|\b)"
    $found = @()
    foreach ($doc in (Get-ChildItem "$root/docs" -Recurse -File -Include *.md,*.mmd)) {
        $lineNumber = 0
        foreach ($line in (Get-Content $doc.FullName -Encoding UTF8)) {
            $lineNumber++
            if ($line -match $pattern) {
                $found += "$($doc.FullName):$lineNumber"
            }
        }
    }
    if ($found.Count -gt 0) {
        Add-Error "Unversioned management paths found: $($found -join ', ')"
        return $false
    }
    return $true
}

# -- 5. Gradle modules ----------------------------------------
Write-Host ""
Write-Host "[5] Gradle modules" -ForegroundColor White

Test-Check "No reference to deleted cli/ module" {
    $found = @()
    foreach ($doc in $allMdFiles) {
        if (-not $doc -or -not (Test-Path $doc.FullName)) { continue }
        $content = Get-Content $doc.FullName -Raw -Encoding UTF8
        # Match: :cli, cli/src, cli:run, gradlew cli:, ├── cli/, | cli/ |
        if ($content -match ':cli\b' -or $content -match 'module\s+cli' -or $content -match 'cli/src' -or
            $content -match 'gradlew\s+cli:' -or $content -match '[\u251c\u2514].*cli/' -or
            $content -match '\|\s*\*?\*?cli/') {
            $found += $doc.Name
        }
    }
    if ($found.Count -gt 0) {
        Add-Error "Deleted cli/ module referenced in: $($found -join ', ')"
        return $false
    }
    return $true
}

Test-Check "All modules in settings.gradle.kts exist on disk" {
    $settings = Get-Content "$root/settings.gradle.kts" -Raw -Encoding UTF8
    $modules = [regex]::Matches($settings, 'include\(":(\w+)"\)') | ForEach-Object { $_.Groups[1].Value }
    $missing = @()
    foreach ($mod in $modules) {
        if (-not (Test-Path "$root/$mod")) {
            $missing += ":$mod"
        }
    }
    if ($missing.Count -gt 0) {
        Add-Error "Modules declared but missing from disk: $($missing -join ', ')"
        return $false
    }
    return $true
}

# -- 6. Internal links in INDEX.md ----------------------------
Write-Host ""
Write-Host "[6] Internal links in INDEX.md" -ForegroundColor White

Test-Check "All files linked in INDEX.md exist" {
    $indexFile = "$root/docs/INDEX.md"
    if (-not (Test-Path $indexFile)) {
        Add-Warning "INDEX.md not found -- skip"
        return $true
    }
    $content = Get-Content $indexFile -Raw -Encoding UTF8
    $links = [regex]::Matches($content, '\[.*?\]\(([^)]+)\)') | ForEach-Object { $_.Groups[1].Value }
    $missing = @()
    foreach ($link in $links) {
        if ($link -match '^https?://') { continue }
        if ($link -match '^file://') { continue }
        $resolved = Join-Path "$root/docs" $link
        $resolved = [System.IO.Path]::GetFullPath($resolved)
        if (-not (Test-Path $resolved)) {
            $missing += $link
        }
    }
    if ($missing.Count -gt 0) {
        foreach ($m in $missing) { Add-Error "INDEX.md broken link: $m" }
        return $false
    }
    return $true
}

# ==============================================================
Write-Host ""
Write-Host "-------------------------------------------" -ForegroundColor DarkGray
$color = if ($script:errors.Count -eq 0) { "Green" } else { "Red" }
Write-Host "Result: $($script:passed)/$($script:checks) checks passed" -ForegroundColor $color

if ($script:warnings.Count -gt 0) {
    Write-Host "   $($script:warnings.Count) warning(s)" -ForegroundColor Yellow
}
if ($script:errors.Count -gt 0) {
    Write-Host "   $($script:errors.Count) error(s)" -ForegroundColor Red
    exit 1
} else {
    Write-Host "   Documentation is consistent with code" -ForegroundColor Green
    exit 0
}
