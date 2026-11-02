# NetMon agent - Windows data collection. READ-ONLY.
# This script is bundled inside the agent jar and is never built from platform input.
# The only input is $env:NETMON_SERVICES: a comma-separated list of service names that the agent has
# already validated against ^[A-Za-z0-9_.$-]{1,128}$ (no quotes, spaces or operators possible).
# Compatible with Windows PowerShell 5.1 (Windows Server 2016+ / Windows 10+).
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$out = [ordered]@{ errors = [ordered]@{} }

function Iso($d) {
    if ($null -eq $d) { return $null }
    return ([datetime]$d).ToUniversalTime().ToString('o')
}

# Each section is independent: one failure (e.g. access denied) never stops the others.
function Section([string]$name, [scriptblock]$block) {
    try { $out[$name] = @(& $block) }
    catch { $out.errors[$name] = [string]$_.Exception.Message }
}

Section 'os' {
    Get-CimInstance -ClassName Win32_OperatingSystem |
        Select-Object CSName, Caption, Version, BuildNumber, OSArchitecture, TotalVisibleMemorySize, FreePhysicalMemory,
            @{ n = 'LastBootUpTime'; e = { Iso $_.LastBootUpTime } }
}
Section 'machine' {
    $guid = (Get-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Cryptography' -Name MachineGuid).MachineGuid
    [pscustomobject]@{ MachineGuid = $guid }
}
Section 'pagefile' {
    Get-CimInstance -ClassName Win32_PageFileUsage | Select-Object Name, AllocatedBaseSize, CurrentUsage
}
# Raw (cumulative) counters: the agent computes rates from the difference between two cycles.
Section 'cpu' {
    Get-CimInstance -ClassName Win32_PerfRawData_PerfOS_Processor | Select-Object Name, PercentProcessorTime, Timestamp_Sys100NS
}
Section 'system' {
    Get-CimInstance -ClassName Win32_PerfRawData_PerfOS_System | Select-Object ProcessorQueueLength
}
Section 'disks' {
    Get-CimInstance -ClassName Win32_LogicalDisk -Filter 'DriveType=3' | Select-Object DeviceID, FileSystem, Size, FreeSpace
}
Section 'diskio' {
    Get-CimInstance -ClassName Win32_PerfRawData_PerfDisk_PhysicalDisk |
        Where-Object { $_.Name -ne '_Total' } |
        Select-Object Name, DiskReadBytesPersec, DiskWriteBytesPersec, DiskReadsPersec, DiskWritesPersec, PercentIdleTime, Timestamp_Sys100NS
}
Section 'adapters' {
    Get-NetAdapter | Select-Object Name, InterfaceDescription, Status, MacAddress, MtuSize, @{ n = 'Speed'; e = { [uint64]$_.Speed } }
}
Section 'adapterStats' {
    Get-NetAdapterStatistics |
        Select-Object Name, ReceivedBytes, SentBytes, ReceivedUnicastPackets, ReceivedMulticastPackets, ReceivedBroadcastPackets,
            SentUnicastPackets, SentMulticastPackets, SentBroadcastPackets, ReceivedDiscardedPackets, ReceivedPacketErrors,
            OutboundDiscardedPackets, OutboundPacketErrors
}
Section 'addresses' {
    Get-NetIPAddress | Select-Object InterfaceAlias, IPAddress, PrefixLength
}
# Neighbour (ARP / NDP) table: addresses on directly connected networks and their MAC (read only).
Section 'neighbors' {
    Get-NetNeighbor -ErrorAction Stop |
        Where-Object { @('Reachable', 'Stale', 'Delay', 'Probe', 'Permanent') -contains [string]$_.State -and $_.LinkLayerAddress -and
            $_.LinkLayerAddress -ne '00-00-00-00-00-00' -and $_.LinkLayerAddress -ne 'FF-FF-FF-FF-FF-FF' } |
        Select-Object -First 512 IPAddress, LinkLayerAddress, InterfaceAlias, @{ n = 'State'; e = { [string]$_.State } }
}
Section 'services' {
    $wanted = @()
    if ($env:NETMON_SERVICES) { $wanted = $env:NETMON_SERVICES.Split(',') }
    if ($wanted.Count -gt 0) {
        Get-CimInstance -ClassName Win32_Service | Where-Object { $wanted -contains $_.Name } | Select-Object Name, State, StartMode, Status
    }
}
# Name, id, CPU time, memory and start time only. Command lines are never read.
Section 'processes' {
    Get-Process | Select-Object Id, ProcessName, WorkingSet64,
        @{ n = 'CpuSeconds'; e = { try { $_.TotalProcessorTime.TotalSeconds } catch { $null } } },
        @{ n = 'StartTime'; e = { try { Iso $_.StartTime } catch { $null } } }
}

$out | ConvertTo-Json -Depth 4 -Compress
