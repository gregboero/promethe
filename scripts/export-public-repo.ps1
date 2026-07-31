#!/usr/bin/env pwsh
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Destination,
    [string]$Ref = "HEAD"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = (git -C $projectRoot rev-parse --show-toplevel).Trim()
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$isMonorepoLayout = $projectRoot -ne $repositoryRoot

if (Test-Path $destinationPath) {
    if ((Get-ChildItem -Force $destinationPath | Measure-Object).Count -gt 0) {
        throw "Destination must be empty: $destinationPath"
    }
} else {
    New-Item -ItemType Directory -Path $destinationPath | Out-Null
}

$archive = Join-Path ([System.IO.Path]::GetTempPath()) "promethe-public-$([guid]::NewGuid()).zip"
try {
    $archiveRef = if ($isMonorepoLayout) { "$Ref`:promethe" } else { $Ref }
    git -C $repositoryRoot archive --format=zip --output=$archive $archiveRef
    if ($LASTEXITCODE -ne 0) { throw "git archive failed for $archiveRef" }
    Expand-Archive -LiteralPath $archive -DestinationPath $destinationPath

    if ($isMonorepoLayout) {
        $githubDir = Join-Path $destinationPath ".github"
        New-Item -ItemType Directory -Force -Path (Join-Path $githubDir "workflows") | Out-Null
        Copy-Item -LiteralPath (Join-Path $repositoryRoot ".github/SECURITY.md") -Destination $githubDir

        foreach ($name in @("ci.yml", "release.yml")) {
            $source = Join-Path $repositoryRoot ".github/workflows/$name"
            $target = Join-Path $githubDir "workflows/$name"
            $content = Get-Content $source -Raw -Encoding UTF8
            $content = $content -replace "(?m)^[ \t]{8}working-directory: promethe[ \t]*$", "        working-directory: ."
            $content = $content -replace "scan-ref: promethe", "scan-ref: ."
            $content = $content -replace "context: promethe", "context: ."
            $content = $content -replace "args: --project-dir,promethe", "args: --project-dir,."
            $content = $content.Replace("--project-dir promethe", "--project-dir .")
            $content = $content.Replace("promethe/", "")
            Set-Content -LiteralPath $target -Value $content -Encoding UTF8 -NoNewline
        }

        $qodanaContent = Get-Content (Join-Path $repositoryRoot "qodana.yaml") -Raw -Encoding UTF8
        $qodanaContent = $qodanaContent.Replace("projectDir: promethe", "projectDir: .")
        Set-Content -LiteralPath (Join-Path $destinationPath "qodana.yaml") -Value $qodanaContent -Encoding UTF8 -NoNewline
    }

    & (Join-Path $destinationPath "scripts/verify-public-export.ps1") -Path $destinationPath
    git -C $destinationPath init --initial-branch=main | Out-Null
    Write-Host "Public repository preview exported to $destinationPath from $Ref"
    Write-Host "This preview is for review only; Copybara owns publication."
} finally {
    Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
}
