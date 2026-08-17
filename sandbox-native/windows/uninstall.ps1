[CmdletBinding(SupportsShouldProcess)]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Promethe sandbox uninstall must run from an elevated PowerShell session.'
}

$offlineAccount = 'PrometheSbxOffline'
$onlineAccount = 'PrometheSbxOnline'
$writerGroup = 'PrometheSbxWriters'
$legacyWriterGroup = 'PrometheSandboxWriters'
$setupRoot = Join-Path $env:ProgramData 'Promethe\sandbox'
$manifestPath = Join-Path $setupRoot 'setup.json'
$runnerPath = Join-Path $setupRoot 'bin\promethe-sandbox-runner.exe'
$offlineCredentialPath = Join-Path $setupRoot 'offline.credential'
$entropy = [Text.Encoding]::UTF8.GetBytes('PrometheSandboxV1')

Add-Type -AssemblyName System.Security -ErrorAction Stop

if ($PSCmdlet.ShouldProcess($setupRoot, 'Remove elevated Promethe sandbox')) {
    $workspaceRoots = @()
    $appContainerSid = $null
    if (Test-Path -LiteralPath $manifestPath) {
        try {
            $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
            $workspaceRoots = @($manifest.workspaceRoots)
            $appContainerSid = $manifest.appContainerSid
        } catch {
            Write-Warning 'The setup manifest is invalid; account and firewall cleanup will continue.'
        }
    }

    $sidValues = @()
    foreach ($name in @($offlineAccount, $onlineAccount, $writerGroup, $legacyWriterGroup)) {
        try {
            $sidValues += ([Security.Principal.NTAccount]::new($env:COMPUTERNAME, $name)).Translate(
                [Security.Principal.SecurityIdentifier]
            ).Value
        } catch {
            # The identity is already absent.
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($appContainerSid)) {
        $sidValues += $appContainerSid
    }

    foreach ($root in $workspaceRoots) {
        if (Test-Path -LiteralPath $root -PathType Container) {
            foreach ($sid in $sidValues) {
                & "$env:SystemRoot\System32\icacls.exe" $root /remove "*$sid" /L /C /Q | Out-Null
            }
        }
    }

    Get-NetFirewallRule -Group 'Promethe Sandbox' -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule

    if (Test-Path -LiteralPath $runnerPath -PathType Leaf) {
        try {
            & $runnerPath --windows-delete-appcontainer-profile
            if ($LASTEXITCODE -ne 0) {
                Write-Warning 'The owner AppContainer profile could not be removed.'
            }
        } catch {
            Write-Warning 'The owner AppContainer profile could not be removed.'
        }
        if ((Test-Path -LiteralPath $offlineCredentialPath -PathType Leaf) -and
            $null -ne (Get-LocalUser -Name $offlineAccount -ErrorAction SilentlyContinue)) {
            $plainBytes = $null
            try {
                $protected = [IO.File]::ReadAllBytes($offlineCredentialPath)
                $plainBytes = [Security.Cryptography.ProtectedData]::Unprotect(
                    $protected,
                    $entropy,
                    [Security.Cryptography.DataProtectionScope]::LocalMachine
                )
                $password = [Text.Encoding]::Unicode.GetString($plainBytes)
                $securePassword = ConvertTo-SecureString $password -AsPlainText -Force
                $credential = [Management.Automation.PSCredential]::new(
                    "$env:COMPUTERNAME\$offlineAccount",
                    $securePassword
                )
                $cleanup = Start-Process `
                    -FilePath $runnerPath `
                    -ArgumentList '--windows-delete-appcontainer-profile' `
                    -Credential $credential `
                    -LoadUserProfile `
                    -WindowStyle Hidden `
                    -Wait `
                    -PassThru
                if ($cleanup.ExitCode -ne 0) {
                    Write-Warning 'The sandbox-account AppContainer profile could not be removed.'
                }
            } catch {
                Write-Warning 'The sandbox-account AppContainer profile could not be removed.'
            } finally {
                if ($null -ne $plainBytes) {
                    [Array]::Clear($plainBytes, 0, $plainBytes.Length)
                }
            }
        }
    }

    foreach ($account in @($offlineAccount, $onlineAccount)) {
        if ($null -ne (Get-LocalUser -Name $account -ErrorAction SilentlyContinue)) {
            Remove-LocalUser -Name $account
        }
    }
    foreach ($group in @($writerGroup, $legacyWriterGroup)) {
        if ($null -ne (Get-LocalGroup -Name $group -ErrorAction SilentlyContinue)) {
            Remove-LocalGroup -Name $group
        }
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
