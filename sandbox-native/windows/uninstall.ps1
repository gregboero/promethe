[CmdletBinding(SupportsShouldProcess)]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Promethe sandbox uninstall must run from an elevated PowerShell session.'
}

$offlineAccount = 'PrometheSandboxOffline'
$onlineAccount = 'PrometheSandboxOnline'
$writerGroup = 'PrometheSandboxWriters'
$setupRoot = Join-Path $env:ProgramData 'Promethe\sandbox'
$manifestPath = Join-Path $setupRoot 'setup.json'

if ($PSCmdlet.ShouldProcess($setupRoot, 'Remove elevated Promethe sandbox')) {
    $workspaceRoots = @()
    if (Test-Path -LiteralPath $manifestPath) {
        try {
            $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
            $workspaceRoots = @($manifest.workspaceRoots)
        } catch {
            Write-Warning 'The setup manifest is invalid; account and firewall cleanup will continue.'
        }
    }

    $sidValues = @()
    foreach ($name in @($offlineAccount, $onlineAccount, $writerGroup)) {
        try {
            $sidValues += ([Security.Principal.NTAccount]::new($env:COMPUTERNAME, $name)).Translate(
                [Security.Principal.SecurityIdentifier]
            ).Value
        } catch {
            # The identity is already absent.
        }
    }

    foreach ($root in $workspaceRoots) {
        if (Test-Path -LiteralPath $root -PathType Container) {
            foreach ($sid in $sidValues) {
                & "$env:SystemRoot\System32\icacls.exe" $root /remove "*$sid" /T /C /Q | Out-Null
            }
        }
    }

    Get-NetFirewallRule -Group 'Promethe Sandbox' -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule

    foreach ($account in @($offlineAccount, $onlineAccount)) {
        if ($null -ne (Get-LocalUser -Name $account -ErrorAction SilentlyContinue)) {
            Remove-LocalUser -Name $account
        }
    }
    if ($null -ne (Get-LocalGroup -Name $writerGroup -ErrorAction SilentlyContinue)) {
        Remove-LocalGroup -Name $writerGroup
    }

    if (Test-Path -LiteralPath $setupRoot) {
        $resolvedRoot = [IO.Path]::GetFullPath($setupRoot)
        $expectedRoot = [IO.Path]::GetFullPath((Join-Path $env:ProgramData 'Promethe\sandbox'))
        if ($resolvedRoot -ne $expectedRoot) {
            throw 'Refusing to remove an unexpected setup directory.'
        }
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }

    Write-Information 'Promethe elevated sandbox was removed.' -InformationAction Continue
}
