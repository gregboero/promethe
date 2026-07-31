[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string[]] $WorkspaceRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Promethe sandbox setup must run from an elevated PowerShell session.'
}

$setupVersion = 1
$offlineAccount = 'PrometheSandboxOffline'
$onlineAccount = 'PrometheSandboxOnline'
$writerGroup = 'PrometheSandboxWriters'
$setupRoot = Join-Path $env:ProgramData 'Promethe\sandbox'
$tempRoot = Join-Path $setupRoot 'tmp'
$manifestPath = Join-Path $setupRoot 'setup.json'
$offlineCredentialPath = Join-Path $setupRoot 'offline.credential'
$onlineCredentialPath = Join-Path $setupRoot 'online.credential'
$entropy = [Text.Encoding]::UTF8.GetBytes('PrometheSandboxV1')

function New-RandomPassword {
    $bytes = New-Object byte[] 48
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes) + '!aA1'
}

function Set-SandboxAccount {
    param(
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $CredentialPath
    )

    $password = New-RandomPassword
    $securePassword = $null
    try {
        $securePassword = ConvertTo-SecureString $password -AsPlainText -Force
        $existing = Get-LocalUser -Name $Name -ErrorAction SilentlyContinue
        if ($null -eq $existing) {
            New-LocalUser `
                -Name $Name `
                -Password $securePassword `
                -AccountNeverExpires `
                -PasswordNeverExpires `
                -UserMayNotChangePassword `
                -Description 'Promethe isolated sandbox identity' | Out-Null
        } else {
            Set-LocalUser `
                -Name $Name `
                -Password $securePassword `
                -PasswordNeverExpires $true `
                -UserMayChangePassword $false
            Enable-LocalUser -Name $Name
        }

        $plainBytes = [Text.Encoding]::Unicode.GetBytes($password)
        try {
            $protected = [Security.Cryptography.ProtectedData]::Protect(
                $plainBytes,
                $entropy,
                [Security.Cryptography.DataProtectionScope]::CurrentUser
            )
            [IO.File]::WriteAllBytes($CredentialPath, $protected)
        } finally {
            [Array]::Clear($plainBytes, 0, $plainBytes.Length)
        }
    } finally {
        $password = $null
        $securePassword = $null
    }
}

function Get-AccountSid {
    param([Parameter(Mandatory = $true)][string] $Name)
    return ([Security.Principal.NTAccount]::new($env:COMPUTERNAME, $Name)).Translate(
        [Security.Principal.SecurityIdentifier]
    ).Value
}

function Invoke-Icacls {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    & "$env:SystemRoot\System32\icacls.exe" @Arguments | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to apply a required Promethe sandbox ACL.'
    }
}

if ($PSCmdlet.ShouldProcess($setupRoot, 'Configure elevated Promethe sandbox')) {
    New-Item -ItemType Directory -Path $setupRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

    if ($null -eq (Get-LocalGroup -Name $writerGroup -ErrorAction SilentlyContinue)) {
        New-LocalGroup -Name $writerGroup -Description 'Promethe sandbox workspace writers' | Out-Null
    }

    Set-SandboxAccount -Name $offlineAccount -CredentialPath $offlineCredentialPath
    Set-SandboxAccount -Name $onlineAccount -CredentialPath $onlineCredentialPath

    foreach ($account in @($offlineAccount, $onlineAccount)) {
        $qualifiedName = "$env:COMPUTERNAME\$account"
        $isMember = Get-LocalGroupMember -Group $writerGroup -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -eq $qualifiedName }
        if ($null -eq $isMember) {
            Add-LocalGroupMember -Group $writerGroup -Member $account
        }
    }

    $offlineSid = Get-AccountSid $offlineAccount
    $onlineSid = Get-AccountSid $onlineAccount
    $writerSid = Get-AccountSid $writerGroup
    $ownerSid = $identity.User.Value

    $resolvedRoots = @(
        foreach ($root in $WorkspaceRoot) {
            $resolved = [IO.Path]::GetFullPath($root)
            if (-not (Test-Path -LiteralPath $resolved -PathType Container)) {
                throw "Workspace does not exist: $resolved"
            }
            (Get-Item -LiteralPath $resolved).FullName
        }
    ) | Sort-Object -Unique

    Invoke-Icacls @($setupRoot, '/inheritance:r')
    Invoke-Icacls @(
        $setupRoot,
        '/grant:r',
        '*S-1-5-18:(OI)(CI)F',
        '*S-1-5-32-544:(OI)(CI)F',
        "*$ownerSid`:(OI)(CI)F",
        "*$offlineSid`:(OI)(CI)RX",
        "*$onlineSid`:(OI)(CI)RX",
        "*$writerSid`:(OI)(CI)M"
    )

    foreach ($root in $resolvedRoots) {
        Invoke-Icacls @(
            $root,
            '/grant:r',
            "*$offlineSid`:(OI)(CI)RX",
            "*$onlineSid`:(OI)(CI)RX",
            "*$writerSid`:(OI)(CI)M"
        )
        foreach ($protectedName in @('.git', '.promethe', '.codex', '.agents')) {
            $protectedPath = Join-Path $root $protectedName
            if (Test-Path -LiteralPath $protectedPath) {
                Invoke-Icacls @(
                    $protectedPath,
                    '/deny',
                    "*$offlineSid`:(OI)(CI)(W,D,DC)",
                    "*$onlineSid`:(OI)(CI)(W,D,DC)",
                    "*$writerSid`:(OI)(CI)(W,D,DC)"
                )
            }
        }
    }

    Get-NetFirewallRule -DisplayName 'Promethe Sandbox Offline - Block Outbound' -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule
    New-NetFirewallRule `
        -DisplayName 'Promethe Sandbox Offline - Block Outbound' `
        -Group 'Promethe Sandbox' `
        -Direction Outbound `
        -Action Block `
        -Enabled True `
        -Profile Any `
        -LocalUser "D:(A;;CC;;;$offlineSid)" | Out-Null

    Get-NetFirewallRule -DisplayName 'Promethe Sandbox Online - Allow Outbound' -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule
    New-NetFirewallRule `
        -DisplayName 'Promethe Sandbox Online - Allow Outbound' `
        -Group 'Promethe Sandbox' `
        -Direction Outbound `
        -Action Allow `
        -Enabled True `
        -Profile Any `
        -LocalUser "D:(A;;CC;;;$onlineSid)" | Out-Null

    $manifest = [ordered]@{
        setupVersion = $setupVersion
        offlineAccount = $offlineAccount
        offlineSid = $offlineSid
        onlineAccount = $onlineAccount
        onlineSid = $onlineSid
        writerGroup = $writerGroup
        writerGroupSid = $writerSid
        workspaceRoots = $resolvedRoots
        offlineCredentialFile = 'offline.credential'
        onlineCredentialFile = 'online.credential'
        tempDirectory = 'tmp'
    }
    $manifestJson = $manifest | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText($manifestPath, $manifestJson, [Text.UTF8Encoding]::new($false))
    Invoke-Icacls @(
        $setupRoot,
        '/grant:r',
        '*S-1-5-18:(OI)(CI)F',
        '*S-1-5-32-544:(OI)(CI)F',
        "*$ownerSid`:(OI)(CI)F",
        "*$offlineSid`:(OI)(CI)RX",
        "*$onlineSid`:(OI)(CI)RX",
        "*$writerSid`:(OI)(CI)M"
    )

    Write-Information 'Promethe elevated sandbox setup completed.' -InformationAction Continue
}
