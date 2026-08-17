[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $WorkspaceRoot,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $HelperPath,

    [string] $OwnerSid,

    [string] $ResultPath,

    [switch] $Elevated
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

try {
    Add-Type -AssemblyName System.Security -ErrorAction Stop
    $null = [Security.Cryptography.ProtectedData]
    $null = [Security.Cryptography.DataProtectionScope]
} catch {
    throw 'Windows DPAPI support is unavailable in this PowerShell host.'
}

try {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class PrometheSandboxAppContainer
{
    [DllImport("userenv.dll", CharSet = CharSet.Unicode)]
    private static extern int CreateAppContainerProfile(
        string name,
        string displayName,
        string description,
        IntPtr capabilities,
        uint capabilityCount,
        out IntPtr sid);

    [DllImport("userenv.dll", CharSet = CharSet.Unicode)]
    private static extern int DeriveAppContainerSidFromAppContainerName(string name, out IntPtr sid);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool ConvertSidToStringSid(IntPtr sid, out IntPtr value);

    [DllImport("advapi32.dll")]
    private static extern IntPtr FreeSid(IntPtr sid);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr value);

    public static string EnsureProfile(string name)
    {
        IntPtr sid;
        int result = CreateAppContainerProfile(
            name,
            "Promethe Sandbox",
            "Network-isolated Promethe process sandbox",
            IntPtr.Zero,
            0,
            out sid);
        if (result == unchecked((int)0x800700B7)) {
            result = DeriveAppContainerSidFromAppContainerName(name, out sid);
        }
        if (result < 0) {
            Marshal.ThrowExceptionForHR(result);
        }
        try {
            IntPtr value;
            if (!ConvertSidToStringSid(sid, out value)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            try {
                return Marshal.PtrToStringUni(value);
            }
            finally {
                LocalFree(value);
            }
        }
        finally {
            FreeSid(sid);
        }
    }
}
'@ -ErrorAction Stop
} catch {
    throw "Windows AppContainer support is unavailable: $($_.Exception.Message)"
}

trap {
    $message = $_.Exception.Message
    if (-not [string]::IsNullOrWhiteSpace($ResultPath)) {
        try {
            [IO.File]::WriteAllText($ResultPath, $message, [Text.UTF8Encoding]::new($false))
        } catch {
            # The original setup failure remains authoritative.
        }
    }
    [Console]::Error.WriteLine($message)
    exit 1
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    if ($Elevated) {
        throw 'Promethe sandbox setup elevation was not granted.'
    }

    $scriptPath = [IO.Path]::GetFullPath($PSCommandPath)
    $resolvedWorkspace = [IO.Path]::GetFullPath($WorkspaceRoot)
    $resolvedHelper = [IO.Path]::GetFullPath($HelperPath)
    $callerSid = $identity.User.Value
    $arguments = @(
        '-NoProfile',
        '-NonInteractive',
        '-ExecutionPolicy', 'Bypass',
        '-File', ('"{0}"' -f $scriptPath),
        '-WorkspaceRoot', ('"{0}"' -f $resolvedWorkspace),
        '-HelperPath', ('"{0}"' -f $resolvedHelper),
        '-OwnerSid', $callerSid
    )
    if (-not [string]::IsNullOrWhiteSpace($ResultPath)) {
        $arguments += @('-ResultPath', ('"{0}"' -f ([IO.Path]::GetFullPath($ResultPath))))
    }
    $arguments += '-Elevated'
    try {
        $process = Start-Process `
            -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" `
            -ArgumentList $arguments `
            -Verb RunAs `
            -WindowStyle Hidden `
            -Wait `
            -PassThru
    } catch {
        throw 'Promethe sandbox setup was cancelled or could not be elevated.'
    }
    if ($process.ExitCode -ne 0) {
        $detail = $null
        if (-not [string]::IsNullOrWhiteSpace($ResultPath) -and (Test-Path -LiteralPath $ResultPath -PathType Leaf)) {
            $detail = [IO.File]::ReadAllText($ResultPath).Trim()
        }
        if (-not [string]::IsNullOrWhiteSpace($detail)) {
            throw $detail
        }
        throw "Promethe sandbox setup failed with exit code $($process.ExitCode)."
    }
    Write-Information 'Promethe elevated sandbox setup completed.' -InformationAction Continue
    return
}

$setupVersion = 6
$runnerVersion = 4
$offlineAccount = 'PrometheSbxOffline'
$onlineAccount = 'PrometheSbxOnline'
$writerGroup = 'PrometheSbxWriters'
$legacyWriterGroup = 'PrometheSandboxWriters'
$appContainerName = 'Promethe.Sandbox.Offline'
$setupRoot = Join-Path $env:ProgramData 'Promethe\sandbox'
$tempRoot = Join-Path $setupRoot 'tmp'
$binRoot = Join-Path $setupRoot 'bin'
$runnerPath = Join-Path $binRoot 'promethe-sandbox-runner.exe'
$manifestPath = Join-Path $setupRoot 'setup.json'
$offlineCredentialPath = Join-Path $setupRoot 'offline.credential'
$onlineCredentialPath = Join-Path $setupRoot 'online.credential'
$entropy = [Text.Encoding]::UTF8.GetBytes('PrometheSandboxV1')
$previousOwnerSid = $null
$requiresIdentityReset = $true

foreach ($identityName in @($offlineAccount, $onlineAccount, $writerGroup)) {
    if ($identityName.Length -gt 20) {
        throw "Internal sandbox identity exceeds the Windows 20-character limit: $identityName"
    }
}

if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
    try {
        $previousManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        $previousOwnerSid = $previousManifest.ownerSid
        $requiresIdentityReset = ([int] $previousManifest.setupVersion -ne $setupVersion)
    } catch {
        throw 'Existing Promethe sandbox setup is invalid; uninstall it before reinstalling.'
    }
}

if ([string]::IsNullOrWhiteSpace($OwnerSid)) {
    $OwnerSid = $identity.User.Value
}
try {
    $ownerIdentity = [Security.Principal.SecurityIdentifier]::new($OwnerSid)
    $null = $ownerIdentity.Translate([Security.Principal.NTAccount])
} catch {
    throw 'Promethe sandbox owner SID is invalid.'
}

$resolvedHelperPath = [IO.Path]::GetFullPath($HelperPath)
if (-not (Test-Path -LiteralPath $resolvedHelperPath -PathType Leaf)) {
    throw "Sandbox helper does not exist: $resolvedHelperPath"
}
if (-not [string]::IsNullOrWhiteSpace($previousOwnerSid) -and $previousOwnerSid -ne $OwnerSid) {
    throw 'This sandbox belongs to another Windows owner; uninstall it before reinstalling.'
}

$resolved = [IO.Path]::GetFullPath($WorkspaceRoot)
if (-not (Test-Path -LiteralPath $resolved -PathType Container)) {
    throw "Workspace does not exist: $resolved"
}
$workspaceItem = Get-Item -LiteralPath $resolved
if (($workspaceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'The sandbox workspace root cannot be a symbolic link or junction.'
}
$resolvedRoots = @($workspaceItem.FullName)
foreach ($protectedName in @('.git', '.promethe', '.codex', '.agents')) {
    $protectedPath = Join-Path $workspaceItem.FullName $protectedName
    if (
        (Test-Path -LiteralPath $protectedPath) -and
        (((Get-Item -LiteralPath $protectedPath).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)
    ) {
        throw "Protected sandbox path cannot be a symbolic link or junction: $protectedPath"
    }
}
$ineffectiveProfiles = Get-NetFirewallProfile | Where-Object { $_.AllowLocalFirewallRules -eq $false }
if ($null -ne $ineffectiveProfiles) {
    throw 'Local Windows Firewall rules are disabled by policy; the sandbox cannot fail closed.'
}

function New-RandomPassword {
    $bytes = New-Object byte[] 48
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
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
                [Security.Cryptography.DataProtectionScope]::LocalMachine
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
    $output = & "$env:SystemRoot\System32\icacls.exe" @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        $detail = ($output | Out-String).Trim()
        throw "Failed to apply a required Promethe sandbox ACL: $detail"
    }
}

if ($PSCmdlet.ShouldProcess($setupRoot, 'Configure elevated Promethe sandbox')) {
    New-Item -ItemType Directory -Path $setupRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $binRoot -Force | Out-Null

    if ($requiresIdentityReset) {
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
    }
    if ($null -ne (Get-LocalGroup -Name $legacyWriterGroup -ErrorAction SilentlyContinue)) {
        Remove-LocalGroup -Name $legacyWriterGroup
    }
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
    $ownerSid = $OwnerSid
    $appContainerSid = [PrometheSandboxAppContainer]::EnsureProfile($appContainerName)

    Invoke-Icacls @($setupRoot, '/inheritance:r')
    Invoke-Icacls @(
        $setupRoot,
        '/grant:r',
        '*S-1-5-18:(OI)(CI)F',
        '*S-1-5-32-544:(OI)(CI)F',
        "*$ownerSid`:(OI)(CI)F",
        "*$offlineSid`:(OI)(CI)RX",
        "*$onlineSid`:(OI)(CI)RX",
        "*$appContainerSid`:(OI)(CI)RX"
    )

    Copy-Item -LiteralPath $resolvedHelperPath -Destination $runnerPath -Force
    Invoke-Icacls @($binRoot, '/inheritance:r')
    Invoke-Icacls @(
        $binRoot,
        '/grant:r',
        '*S-1-5-18:(OI)(CI)F',
        '*S-1-5-32-544:(OI)(CI)F',
        "*$ownerSid`:(OI)(CI)F",
        "*$offlineSid`:(OI)(CI)RX",
        "*$onlineSid`:(OI)(CI)RX",
        "*$appContainerSid`:(OI)(CI)RX"
    )

    Invoke-Icacls @($tempRoot, '/inheritance:r')
    Invoke-Icacls @(
        $tempRoot,
        '/grant:r',
        '*S-1-5-18:(OI)(CI)F',
        '*S-1-5-32-544:(OI)(CI)F',
        "*$ownerSid`:(OI)(CI)F",
        "*$offlineSid`:(OI)(CI)M",
        "*$onlineSid`:(OI)(CI)M",
        "*$appContainerSid`:(OI)(CI)M"
    )

    foreach ($credentialPath in @($offlineCredentialPath, $onlineCredentialPath)) {
        Invoke-Icacls @($credentialPath, '/inheritance:r')
        Invoke-Icacls @(
            $credentialPath,
            '/grant:r',
            '*S-1-5-18:F',
            '*S-1-5-32-544:F',
            "*$ownerSid`:F",
            "*$offlineSid`:(RC)",
            "*$onlineSid`:(RC)",
            "*$writerSid`:(RC)"
        )
    }

    foreach ($root in $resolvedRoots) {
        Invoke-Icacls @(
            $root,
            '/grant:r',
            "*$offlineSid`:(OI)(CI)RX",
            "*$onlineSid`:(OI)(CI)RX",
            "*$writerSid`:(RX,W)",
            "*$appContainerSid`:(OI)(CI)M"
        )
        Invoke-Icacls @(
            $root,
            '/grant',
            "*$writerSid`:(OI)(CI)(IO)M"
        )
        foreach ($protectedName in @('.git', '.promethe', '.codex', '.agents')) {
            $protectedPath = Join-Path $root $protectedName
            if (-not (Test-Path -LiteralPath $protectedPath)) {
                New-Item -ItemType Directory -Path $protectedPath | Out-Null
            }
            Invoke-Icacls @(
                $protectedPath,
                '/deny',
                "*$offlineSid`:(OI)(CI)(W,D,DC)",
                "*$onlineSid`:(OI)(CI)(W,D,DC)",
                "*$writerSid`:(OI)(CI)(W,D,DC)",
                "*$appContainerSid`:(OI)(CI)(W,D,DC)"
            )
        }
    }

    Get-NetFirewallRule -Group 'Promethe Sandbox' -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule
    $localUserCondition = "O:LSD:(A;;CC;;;$offlineSid)"
    New-NetFirewallRule `
        -Name 'promethe_sandbox_offline_block_non_loopback' `
        -DisplayName 'Promethe Sandbox Offline - Block Non-Loopback Outbound' `
        -Group 'Promethe Sandbox' `
        -Direction Outbound `
        -Action Block `
        -Enabled True `
        -Profile Any `
        -RemoteAddress '0.0.0.0-126.255.255.255','128.0.0.0-255.255.255.255','::','::2-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff' `
        -LocalUser $localUserCondition | Out-Null
    New-NetFirewallRule `
        -Name 'promethe_sandbox_offline_block_loopback_tcp' `
        -DisplayName 'Promethe Sandbox Offline - Block Loopback TCP' `
        -Group 'Promethe Sandbox' `
        -Direction Outbound `
        -Action Block `
        -Enabled True `
        -Profile Any `
        -Protocol TCP `
        -RemoteAddress '127.0.0.0/8','::/127' `
        -LocalUser $localUserCondition | Out-Null
    New-NetFirewallRule `
        -Name 'promethe_sandbox_offline_block_loopback_udp' `
        -DisplayName 'Promethe Sandbox Offline - Block Loopback UDP' `
        -Group 'Promethe Sandbox' `
        -Direction Outbound `
        -Action Block `
        -Enabled True `
        -Profile Any `
        -Protocol UDP `
        -RemoteAddress '127.0.0.0/8','::/127' `
        -LocalUser $localUserCondition | Out-Null

    $manifest = [ordered]@{
        setupVersion = $setupVersion
        ownerSid = $ownerSid
        offlineAccount = $offlineAccount
        offlineSid = $offlineSid
        onlineAccount = $onlineAccount
        onlineSid = $onlineSid
        writerGroup = $writerGroup
        writerGroupSid = $writerSid
        appContainerName = $appContainerName
        appContainerSid = $appContainerSid
        workspaceRoots = $resolvedRoots
        offlineCredentialFile = 'offline.credential'
        onlineCredentialFile = 'online.credential'
        tempDirectory = 'tmp'
        runnerFile = 'bin\promethe-sandbox-runner.exe'
        runnerVersion = $runnerVersion
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
        "*$appContainerSid`:(OI)(CI)RX"
    )

    Write-Information 'Promethe elevated sandbox setup completed.' -InformationAction Continue
}
