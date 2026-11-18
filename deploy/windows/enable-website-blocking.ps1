<#
.SYNOPSIS
  Turns website blocking on (or off) for an installed NetMon agent on a Windows server or PC.

.DESCRIPTION
  Run in an elevated PowerShell window:

    .\enable-website-blocking.ps1            the device follows the "Website blocking" page
    .\enable-website-blocking.ps1 -Disable   stop, and remove NetMon's lines from the hosts file

  The agent then keeps its own marked section of C:\Windows\System32\drivers\etc\hosts equal to the
  platform's list (other lines are never changed). The agent service account (LOCAL SERVICE) is given
  permission to change that one file and nothing else; with firewall control it runs as LocalSystem,
  which already can. Windows reads the hosts file again by itself after every change.
#>
[CmdletBinding()]
param([switch] $Disable, [switch] $NoRestart)

$ErrorActionPreference = 'Stop'
$ServiceId  = 'netmon-agent'
$ConfigFile = Join-Path $env:ProgramData 'NetMon\Agent\agent.properties'
$Hosts      = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
$LocalSvc   = '*S-1-5-19'   # NT AUTHORITY\LOCAL SERVICE (by SID, works in every Windows language)
$Begin      = '# BEGIN NetMon website blocking (managed by the NetMon agent - do not edit)'
$End        = '# END NetMon website blocking'

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { throw 'Run this in an elevated PowerShell window (Run as administrator).' }
if (-not (Test-Path -LiteralPath $ConfigFile)) { throw "$ConfigFile not found: install the agent first (install-agent.ps1)." }

function Set-Props([hashtable] $props) {
    $lines = @(Get-Content -LiteralPath $ConfigFile | Where-Object { $line = $_; -not ($props.Keys | Where-Object { $line -like "$_=*" }) })
    foreach ($k in $props.Keys) { $lines += "$k=$($props[$k])" }
    Set-Content -LiteralPath $ConfigFile -Value $lines -Encoding Ascii
}

if ($Disable) {
    Set-Props @{ 'websites.enabled' = 'false' }
    # Remove NetMon's section, keep everything else.
    $kept = New-Object System.Collections.Generic.List[string]
    $inside = $false
    foreach ($l in (Get-Content -LiteralPath $Hosts)) {
        if ($l.Trim() -eq $Begin) { $inside = $true; continue }
        if ($l.Trim() -eq $End) { $inside = $false; continue }
        if (-not $inside) { $kept.Add($l) }
    }
    while ($kept.Count -gt 0 -and [string]::IsNullOrWhiteSpace($kept[$kept.Count - 1])) { $kept.RemoveAt($kept.Count - 1) }
    Set-Content -LiteralPath $Hosts -Value $kept -Encoding Ascii
    & icacls.exe $Hosts /remove:g $LocalSvc | Out-Null
    if (-not $NoRestart) { Restart-Service -Name $ServiceId }
    Write-Host "Website blocking disabled; NetMon's lines were removed from the hosts file." -ForegroundColor Green
    return
}

Set-Props @{ 'websites.enabled' = 'true'; 'websites.mode' = 'direct' }
# Allow the agent's service account to change this one file (Modify).
& icacls.exe $Hosts /grant "${LocalSvc}:(M)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not give the agent permission to change the hosts file.' }
if (-not $NoRestart) { Restart-Service -Name $ServiceId }
Write-Host 'Website blocking enabled. This device now follows the Website blocking page (changes apply within about a minute).' -ForegroundColor Green
Write-Host "Check: Select-String -Path '$Hosts' -Pattern 'NetMon' -Context 0,3     Undo: .\enable-website-blocking.ps1 -Disable"
