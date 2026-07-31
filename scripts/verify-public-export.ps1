#!/usr/bin/env pwsh
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Path
)

$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath($Path)
$errors = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $root -PathType Container)) {
    throw "Public export directory does not exist: $root"
}

$requiredFiles = @(
    "README.md",
    "LICENSE",
    "NOTICE",
    "CONTRIBUTING.md",
    "docs/SECURITY.md",
    "docs/REPOSITORY_SYNC.md",
    ".github/CODEOWNERS",
    ".github/SECURITY.md",
    ".github/workflows/ci.yml",
    ".github/workflows/release.yml",
    "qodana.yaml"
)

foreach ($relativePath in $requiredFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $relativePath) -PathType Leaf)) {
        $errors.Add("Missing required file: $relativePath")
    }
}

if (Test-Path -LiteralPath (Join-Path $root "promethe") -PathType Container) {
    $errors.Add("The public export must be rooted at the project, not under promethe/.")
}

$files = Get-ChildItem -LiteralPath $root -Recurse -Force -File
foreach ($file in $files) {
    $relativePath = [System.IO.Path]::GetRelativePath($root, $file.FullName).Replace("\", "/")

    if ($relativePath -match '(^|/)\.env($|\.)' -and $relativePath -ne ".env.example") {
        $errors.Add("Forbidden environment file: $relativePath")
    }
    if ($relativePath -match '(^|/)(credentials\.json|local\.properties|env\.json.*)$') {
        $errors.Add("Forbidden credential or machine-local file: $relativePath")
    }
    if ($relativePath -match '(^|/)(\.gradle|\.idea|\.kotlin|node_modules)(/|$)') {
        $errors.Add("Forbidden generated directory: $relativePath")
    }
    if ($relativePath -match '^(build|data|logs|profiles)/' -or $relativePath -match '^sandbox-native/target/') {
        $errors.Add("Forbidden runtime or build output: $relativePath")
    }
    if ($relativePath -match '\.(pem|p12|pfx)$' -or $relativePath -match '(^|/)id_(rsa|ecdsa|ed25519)$') {
        $errors.Add("Forbidden private key material: $relativePath")
    }
}

$licensePath = Join-Path $root "LICENSE"
if ((Test-Path -LiteralPath $licensePath) -and
    -not (Select-String -LiteralPath $licensePath -Pattern "Apache License" -Quiet)) {
    $errors.Add("LICENSE is not Apache License 2.0.")
}

$readmePath = Join-Path $root "README.md"
if ((Test-Path -LiteralPath $readmePath) -and
    -not (Select-String -LiteralPath $readmePath -SimpleMatch "[Apache License 2.0](LICENSE)" -Quiet)) {
    $errors.Add("README.md does not advertise the Apache License 2.0.")
}

$codeownersPath = Join-Path $root ".github/CODEOWNERS"
if ((Test-Path -LiteralPath $codeownersPath) -and
    -not (Select-String -LiteralPath $codeownersPath -Pattern "^\*\s+@gregboero\s*$" -Quiet)) {
    $errors.Add(".github/CODEOWNERS does not require @gregboero for all changes.")
}

$textFiles = $files | Where-Object {
    $_.Extension -in @(".md", ".yml", ".yaml", ".kts", ".properties", ".txt") -or
    $_.Name -in @("LICENSE", "NOTICE")
}
foreach ($file in $textFiles) {
    $relativePath = [System.IO.Path]::GetRelativePath($root, $file.FullName).Replace("\", "/")
    if (Select-String -LiteralPath $file.FullName -Pattern "github.com/gregboero/projectRandD" -Quiet) {
        $errors.Add("Stale monorepo link in $relativePath")
    }
    if (Select-String -LiteralPath $file.FullName -SimpleMatch "../.github/SECURITY.md" -Quiet) {
        $errors.Add("Broken standalone security-policy link in $relativePath")
    }
}

foreach ($workflow in @(".github/workflows/ci.yml", ".github/workflows/release.yml")) {
    $workflowPath = Join-Path $root $workflow
    if ((Test-Path -LiteralPath $workflowPath) -and
        (Select-String -LiteralPath $workflowPath -SimpleMatch "promethe/" -Quiet)) {
        $errors.Add("Monorepo-relative path remains in $workflow")
    }
}

$qodanaPath = Join-Path $root "qodana.yaml"
if ((Test-Path -LiteralPath $qodanaPath) -and
    (Select-String -LiteralPath $qodanaPath -Pattern "^\s*projectDir\s*:" -Quiet)) {
    $errors.Add("qodana.yaml must not declare the unsupported projectDir key.")
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    throw "Public export verification failed with $($errors.Count) error(s)."
}

Write-Host "Public export verification passed: $root"
