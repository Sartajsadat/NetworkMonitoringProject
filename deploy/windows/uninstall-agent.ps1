<#
.SYNOPSIS
  Removes the NetMon monitoring agent service and its files. Also revoke the agent in the web UI.
.PARAMETER KeepData
  Keep C:\ProgramData\NetMon\Agent (configuration, credential, logs).
#>
[CmdletBinding()]
param([switch] $KeepData)

$ErrorActionPreference = 'Stop'
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script in an elevated PowerShell window (Run as administrator).'
}
$InstallDir = Join-Path $env:ProgramFiles 'NetMon Agent'
$DataDir    = Join-Path $env:ProgramData 'NetMon\Agent'
$exe        = Join-Path $InstallDir 'netmon-agent.exe'

if (Get-Service -Name 'netmon-agent' -ErrorAction SilentlyContinue) {
    Stop-Service -Name 'netmon-agent' -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $exe) { & $exe uninstall } else { & sc.exe delete 'netmon-agent' | Out-Null }
}
if (Test-Path -LiteralPath $InstallDir) { Remove-Item -LiteralPath $InstallDir -Recurse -Force }
if (-not $KeepData -and (Test-Path -LiteralPath $DataDir)) { Remove-Item -LiteralPath $DataDir -Recurse -Force }
Write-Host 'Agent removed. Also revoke it in the web UI (server page > Agent tab > Revoke agent).' -ForegroundColor Green
Write-Host 'LOCAL SERVICE was left in "Performance Monitor Users" because other services may rely on it.'
