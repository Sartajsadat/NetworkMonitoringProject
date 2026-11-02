<#
.SYNOPSIS
  Installs the NetMon monitoring agent as a Windows service and enrolls it.

.DESCRIPTION
  Run in an elevated Windows PowerShell 5.1 (or PowerShell 7) window:

    .\install-agent.ps1 -Jar .\netmon-agent.jar -WinSW .\WinSW-x64.exe `
        -Url https://netmon.example.gov.af -Token <ENROLLMENT_TOKEN> [-CaFile .\ministry-ca.pem] `
        [-EnableCapture [-CaptureInterfaces "Ethernet0"]]

  What it does:
    1. Copies the agent to "C:\Program Files\NetMon Agent" and uses WinSW (open-source service wrapper,
       v2.12.0, checked by SHA-256) to run it as the Windows service "netmon-agent".
    2. Writes C:\ProgramData\NetMon\Agent\agent.properties (no secrets).
    3. Restricts C:\ProgramData\NetMon\Agent to SYSTEM, Administrators and the LOCAL SERVICE account.
    4. Enrolls with the one-time token; the agent secret is stored in ...\state\credentials.json.
    5. Runs the service as NT AUTHORITY\LocalService (not LocalSystem, not an administrator) and adds
       that account to "Performance Monitor Users" so it can read performance counters.
  No inbound firewall port is opened: the agent only connects out to the platform.

  Packet capture (Phase 4) is OFF unless -EnableCapture is given. It needs Wireshark's dumpcap.exe
  and the Npcap driver installed WITHOUT "Restrict Npcap driver's access to Administrators only"
  (otherwise LocalService cannot capture). -CaptureInterfaces limits capture to the listed adapters
  (names as shown by dumpcap -D in brackets, e.g. Ethernet0); empty = every real adapter.

  Firewall control (Phase 7) is OFF unless -EnableFirewall is given. Changing Windows Defender
  Firewall needs administrative rights, so with -EnableFirewall the service runs as LocalSystem
  instead of LocalService. Without -FirewallEnforce the agent only reports the firewall and checks
  approved rules with -WhatIf (dry run). -FirewallProtect lists addresses that must never be blocked.
  Recovery (removes every NetMon rule): Remove-NetFirewallRule -Group NetMon
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $Jar,
    [Parameter(Mandatory = $true)] [string] $WinSW,
    [Parameter(Mandatory = $true)] [string] $Url,
    [Parameter(Mandatory = $true)] [string] $Token,
    [string] $CaFile,
    [string] $JavaPath,
    [switch] $SkipPerformanceGroup,
    [switch] $SkipWinSWHashCheck,
    [switch] $EnableCapture,
    [string] $CaptureInterfaces = '',
    [ValidateRange(1, 1440)] [int] $CaptureMaxMinutes = 60,
    [ValidateRange(1, 102400)] [int] $CaptureMaxMegabytes = 1024,
    [switch] $EnableFirewall,
    [switch] $FirewallEnforce,
    [string] $FirewallProtect = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

# SHA-256 of WinSW-x64.exe v2.12.0 from https://github.com/winsw/winsw/releases/tag/v2.12.0
$WinSWSha256 = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
$ServiceId   = 'netmon-agent'
$InstallDir  = Join-Path $env:ProgramFiles 'NetMon Agent'
$DataDir     = Join-Path $env:ProgramData 'NetMon\Agent'
$StateDir    = Join-Path $DataDir 'state'
$LogDir      = Join-Path $DataDir 'logs'
$ConfigFile  = Join-Path $DataDir 'agent.properties'

# Well-known SIDs (language-independent account names)
$SidSystem   = 'S-1-5-18'
$SidAdmins   = 'S-1-5-32-544'
$SidLocalSvc = 'S-1-5-19'
$SidPerfMon  = 'S-1-5-32-558'   # Performance Monitor Users

function Step([string]$text) { Write-Host "==> $text" -ForegroundColor Cyan }

# --- checks -----------------------------------------------------------------
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script in an elevated PowerShell window (Run as administrator).'
}
foreach ($f in @($Jar, $WinSW)) { if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { throw "File not found: $f" } }
if ($CaFile -and -not (Test-Path -LiteralPath $CaFile -PathType Leaf)) { throw "CA file not found: $CaFile" }
if ($Url -notmatch '^https?://[A-Za-z0-9.\-:\[\]_]+(/.*)?$') { throw "Invalid platform URL: $Url" }
if ($Url -notmatch '^https://' -and $Url -notmatch '^http://(localhost|127\.0\.0\.1)(:|/|$)') {
    throw 'The platform URL must use https:// (plain http is only accepted for localhost).'
}
if ($Token -notmatch '^[A-Za-z0-9_\-]{20,200}$') { throw 'The enrollment token does not look valid. Copy it again from the web UI.' }

if (-not $SkipWinSWHashCheck) {
    $hash = (Get-FileHash -LiteralPath $WinSW -Algorithm SHA256).Hash
    if ($hash -ne $WinSWSha256) {
        throw "WinSW file hash $hash does not match WinSW-x64.exe v2.12.0. Download it from the official release page."
    }
}

if (-not $JavaPath) {
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $cmd) { throw 'Java 21 was not found. Install a Java 21 runtime (e.g. Eclipse Temurin 21 JRE) or pass -JavaPath.' }
    $JavaPath = $cmd.Source
}
$versionText = (& $JavaPath -version 2>&1 | Out-String)
if ($versionText -notmatch 'version "(\d+)') { throw "Could not read the Java version from $JavaPath" }
if ([int]$Matches[1] -lt 21) { throw "Java 21 or newer is required (found $($Matches[1]) at $JavaPath)." }

# --- files and permissions ----------------------------------------------------
Step "Copying files to $InstallDir"
New-Item -ItemType Directory -Force -Path $InstallDir, $DataDir, $StateDir, $LogDir | Out-Null
if (Get-Service -Name $ServiceId -ErrorAction SilentlyContinue) {
    Step 'Stopping the existing service (upgrade)'
    Stop-Service -Name $ServiceId -ErrorAction SilentlyContinue
}
Copy-Item -LiteralPath $Jar -Destination (Join-Path $InstallDir 'netmon-agent.jar') -Force
Copy-Item -LiteralPath $WinSW -Destination (Join-Path $InstallDir 'netmon-agent.exe') -Force

Step 'Restricting access to the agent data folder'
# Data folder: administrators and SYSTEM full control, the service account read-only (config) ...
& icacls.exe $DataDir /inheritance:r /grant:r "*${SidSystem}:(OI)(CI)F" "*${SidAdmins}:(OI)(CI)F" "*${SidLocalSvc}:(OI)(CI)RX" | Out-Null
# ... and modify rights only where it writes: its state (credential) and logs.
& icacls.exe $StateDir /grant:r "*${SidLocalSvc}:(OI)(CI)M" | Out-Null
& icacls.exe $LogDir /grant:r "*${SidLocalSvc}:(OI)(CI)M" | Out-Null

$caLine = 'tls.ca-certificate='
if ($CaFile) {
    $caTarget = Join-Path $DataDir 'ca.pem'
    Copy-Item -LiteralPath $CaFile -Destination $caTarget -Force
    $caLine = 'tls.ca-certificate=' + $caTarget.Replace('\', '/')
}
if (-not (Test-Path -LiteralPath $ConfigFile)) {
    Step "Writing $ConfigFile"
    $props = @(
        "platform.url=$Url",
        $caLine,
        'state.dir=' + $StateDir.Replace('\', '/'),
        'buffer.max-reports=240',
        'credentials.rotate-after-days=30'
    )
    # ASCII: Java .properties files are read as ISO-8859-1.
    Set-Content -LiteralPath $ConfigFile -Value $props -Encoding Ascii
} else {
    Step "Keeping existing $ConfigFile"
}

if ($EnableCapture) {
    Step 'Enabling packet capture'
    if ($CaptureInterfaces -notmatch '^[A-Za-z0-9 ._:@,()-]*$') { throw "Invalid -CaptureInterfaces value" }
    $dumpcap = Join-Path $env:ProgramFiles 'Wireshark\dumpcap.exe'
    if (-not (Test-Path -LiteralPath $dumpcap)) {
        Write-Warning "dumpcap.exe not found at $dumpcap. Install Wireshark (with Npcap) before capturing."
    }
    if (-not (Get-Service -Name npcap -ErrorAction SilentlyContinue)) {
        Write-Warning 'The Npcap driver is not installed. Install it from https://npcap.com (do not restrict it to Administrators).'
    }
    $captureProps = [ordered]@{
        'capture.enabled'              = 'true'
        'capture.interfaces'           = $CaptureInterfaces
        'capture.max-duration-seconds' = [string]($CaptureMaxMinutes * 60)
        'capture.max-megabytes'        = [string]$CaptureMaxMegabytes
        'capture.dumpcap-path'         = $dumpcap.Replace('\', '/')
    }
    $lines = @(Get-Content -LiteralPath $ConfigFile | Where-Object { $_ -notmatch '^capture\.(enabled|interfaces|max-duration-seconds|max-megabytes|dumpcap-path)=' })
    foreach ($k in $captureProps.Keys) { $lines += "$k=$($captureProps[$k])" }
    Set-Content -LiteralPath $ConfigFile -Value $lines -Encoding Ascii
}

if ($EnableFirewall) {
    Step ('Enabling firewall control (' + $(if ($FirewallEnforce) { 'ENFORCEMENT ALLOWED' } else { 'dry run only' }) + ')')
    if ($FirewallProtect -notmatch '^[0-9A-Fa-f:./,]*$') { throw "Invalid -FirewallProtect value" }
    $fwProps = [ordered]@{
        'firewall.enabled'             = 'true'
        'firewall.enforcement'         = $(if ($FirewallEnforce) { 'true' } else { 'false' })
        'firewall.protected-addresses' = $FirewallProtect
    }
    $lines = @(Get-Content -LiteralPath $ConfigFile | Where-Object { $_ -notmatch '^firewall\.(enabled|enforcement|protected-addresses)=' })
    foreach ($k in $fwProps.Keys) { $lines += "$k=$($fwProps[$k])" }
    Set-Content -LiteralPath $ConfigFile -Value $lines -Encoding Ascii
}

# --- enrollment ----------------------------------------------------------------
Step 'Enrolling with the platform'
& $JavaPath -jar (Join-Path $InstallDir 'netmon-agent.jar') enroll --config $ConfigFile --token $Token
if ($LASTEXITCODE -ne 0) { throw 'Enrollment failed (see the message above). Create a new token in the web UI and try again.' }

# --- performance counter access ---------------------------------------------------
if (-not $SkipPerformanceGroup) {
    Step 'Allowing LOCAL SERVICE to read performance counters (Performance Monitor Users)'
    try {
        $groupName = (New-Object Security.Principal.SecurityIdentifier($SidPerfMon)).Translate([Security.Principal.NTAccount]).Value.Split('\')[-1]
        $group = [ADSI]("WinNT://$env:COMPUTERNAME/$groupName,group")
        $group.Add("WinNT://$SidLocalSvc")
    } catch {
        # 0x80070562 = ERROR_MEMBER_IN_ALIAS: already a member, nothing to do.
        $hr = $_.Exception.InnerException.HResult
        if ($hr -ne -2147023518) {
            Write-Warning "Could not add LOCAL SERVICE to Performance Monitor Users: $($_.Exception.Message). If CPU or disk values show 'Permission denied', add it manually (lusrmgr.msc)."
        }
    }
}

# --- service ---------------------------------------------------------------------
Step 'Installing the Windows service'
$xmlPath = Join-Path $InstallDir 'netmon-agent.xml'
$javaEsc = [Security.SecurityElement]::Escape($JavaPath)
$cfgEsc  = [Security.SecurityElement]::Escape($ConfigFile)
$logEsc  = [Security.SecurityElement]::Escape($LogDir)
@"
<service>
  <id>$ServiceId</id>
  <name>NetMon monitoring agent</name>
  <description>Reports server metrics to the NetMon platform. Outbound HTTPS only; read-only.</description>
  <executable>$javaEsc</executable>
  <arguments>-Xms16m -Xmx128m -XX:+UseSerialGC -jar "%BASE%\netmon-agent.jar" run --config "$cfgEsc"</arguments>
  <startmode>Automatic</startmode>
  <delayedAutoStart>true</delayedAutoStart>
  <onfailure action="restart" delay="15 sec"/>
  <onfailure action="restart" delay="60 sec"/>
  <resetfailure>1 hour</resetfailure>
  <stoptimeout>15 sec</stoptimeout>
  <logpath>$logEsc</logpath>
  <log mode="roll-by-size">
    <sizeThreshold>10240</sizeThreshold>
    <keepFiles>5</keepFiles>
  </log>
</service>
"@ | Set-Content -LiteralPath $xmlPath -Encoding UTF8

$exe = Join-Path $InstallDir 'netmon-agent.exe'
if (-not (Get-Service -Name $ServiceId -ErrorAction SilentlyContinue)) {
    & $exe install
    if ($LASTEXITCODE -ne 0) { throw 'WinSW could not install the service.' }
}
if ($EnableFirewall) {
    # Windows Defender Firewall can only be changed by an administrator account.
    & sc.exe config $ServiceId obj= 'LocalSystem' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not set the service account to LocalSystem.' }
    Write-Warning 'The agent runs as LocalSystem because firewall control is enabled.'
} else {
    # Run as LOCAL SERVICE (least privilege) instead of the default LocalSystem.
    & sc.exe config $ServiceId obj= 'NT AUTHORITY\LocalService' password= '' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not set the service account to LOCAL SERVICE.' }
}

Step 'Starting the service'
Start-Service -Name $ServiceId
Start-Sleep -Seconds 5
Get-Service -Name $ServiceId | Format-Table -AutoSize Name, Status, StartType
Write-Host "Installed. Logs: $LogDir" -ForegroundColor Green
Write-Host 'The server should show "Online" in the web UI within one report interval.' -ForegroundColor Green
