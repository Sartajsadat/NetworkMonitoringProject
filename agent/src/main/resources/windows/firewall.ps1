# NetMon agent: Windows Defender Firewall operations for platform-approved rules.
# Bundled with the agent (constant text); inputs come ONLY from environment variables that the
# agent has validated. Rules are created in the group "NetMon" and named "netmon-<tag>".
# Recovery: Remove-NetFirewallRule -Group NetMon
$ErrorActionPreference = 'Stop'
$op = $env:NETMON_FW_OP
$tag = $env:NETMON_FW_TAG
function Out-Result($ok, $message, $extra) {
    $r = [ordered]@{ ok = $ok; message = $message }
    if ($extra) { foreach ($k in $extra.Keys) { $r[$k] = $extra[$k] } }
    $r | ConvertTo-Json -Compress -Depth 4
}
try {
    Import-Module NetSecurity -ErrorAction Stop
    switch ($op) {
        'state' {
            $profiles = @(Get-NetFirewallProfile | ForEach-Object { [ordered]@{ name = [string]$_.Name; enabled = [bool]($_.Enabled -eq 'True') } })
            $tags = @(Get-NetFirewallRule -Group 'NetMon' -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '^netmon-[0-9a-f]{8,16}$' -and $_.Enabled -eq 'True' } |
                ForEach-Object { $_.Name.Substring(7) })
            $ports = @(22, 3389, 5985, 5986)
            $sessions = @(Get-NetTCPConnection -State Established -ErrorAction SilentlyContinue |
                Where-Object { $ports -contains $_.LocalPort } | ForEach-Object { [string]$_.RemoteAddress } | Sort-Object -Unique)
            Out-Result $true 'ok' @{ profiles = $profiles; tags = $tags; sessions = $sessions }
        }
        { $_ -eq 'apply' -or $_ -eq 'check' } {
            if ($tag -notmatch '^[0-9a-f]{8,16}$') { throw 'invalid tag' }
            $p = @{
                Name = "netmon-$tag"; DisplayName = "NetMon $tag"; Group = 'NetMon'
                Direction = $env:NETMON_FW_DIRECTION; Action = $env:NETMON_FW_ACTION
                RemoteAddress = $env:NETMON_FW_REMOTE; Enabled = 'True'; Profile = 'Any'
                Description = 'Created by the NetMon agent for an approved platform request'
            }
            if ($env:NETMON_FW_LOCAL) { $p.LocalAddress = $env:NETMON_FW_LOCAL }
            if ($env:NETMON_FW_PROTOCOL -and $env:NETMON_FW_PROTOCOL -ne 'Any') { $p.Protocol = $env:NETMON_FW_PROTOCOL }
            if ($env:NETMON_FW_LPORT) { $p.LocalPort = $env:NETMON_FW_LPORT }
            if ($env:NETMON_FW_RPORT) { $p.RemotePort = $env:NETMON_FW_RPORT }
            if ($op -eq 'check') {
                New-NetFirewallRule @p -WhatIf | Out-Null
                Out-Result $true 'checked (WhatIf)' $null
            } else {
                if (Get-NetFirewallRule -Name "netmon-$tag" -ErrorAction SilentlyContinue) {
                    Out-Result $true 'already present' $null
                } else {
                    New-NetFirewallRule @p | Out-Null
                    Out-Result $true 'created' $null
                }
            }
        }
        'remove' {
            if ($tag -notmatch '^[0-9a-f]{8,16}$') { throw 'invalid tag' }
            $r = Get-NetFirewallRule -Name "netmon-$tag" -ErrorAction SilentlyContinue
            if ($r) { Remove-NetFirewallRule -Name "netmon-$tag"; Out-Result $true 'removed' @{ found = $true } }
            else { Out-Result $true 'not present' @{ found = $false } }
        }
        default { Out-Result $false 'unknown operation' $null }
    }
} catch {
    Out-Result $false ([string]$_.Exception.Message) $null
}
