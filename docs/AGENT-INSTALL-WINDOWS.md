# Installing the Windows monitoring agent

The Windows agent is the **same `netmon-agent.jar`** as on Linux. On Windows it reads data through
the supported Windows interfaces: performance counters and CIM/WMI classes (`Win32_OperatingSystem`,
`Win32_PerfRawData_*`, `Win32_LogicalDisk`, `Win32_Service`), the NetAdapter module
(`Get-NetAdapter`, `Get-NetAdapterStatistics`, `Get-NetIPAddress`) and `Get-Process`.

These are queried by one **read-only PowerShell script that is built into the jar**
(`agent/src/main/resources/windows/collect.ps1`). Nothing from the platform is inserted into the
script. The only input is the list of service names to watch, validated as
`^[A-Za-z0-9_.$-]{1,128}$` and passed in an environment variable.

## Requirements

| Item | Notes |
|---|---|
| Windows Server 2016 / 2019 / 2022 / 2025 (or Windows 10/11 for a lab) | Windows PowerShell 5.1 is built in |
| Java 21 runtime | e.g. Eclipse Temurin 21 JRE (x64 MSI, tick "Add to PATH") |
| WinSW **v2.12.0** `WinSW-x64.exe` | Open-source service wrapper (MIT). The installer checks its SHA-256: `05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA` |
| Outbound HTTPS to the platform | No inbound firewall rule is needed |

## Steps

1. Build the jar once (on any machine with JDK 21): `cd agent` then `mvnw.cmd package` → `agent\target\netmon-agent.jar`.
2. In the web UI: register the server with operating system **Windows**, open it → **Agent** tab →
   **Create enrollment token**. The dialog shows the exact PowerShell command.
3. Copy `netmon-agent.jar`, `WinSW-x64.exe` and the `deploy` folder to the server.
4. Open **PowerShell as administrator** in that folder and run:

   ```powershell
   .\deploy\windows\install-agent.ps1 -Jar .\netmon-agent.jar -WinSW .\WinSW-x64.exe -Url https://netmon.example.gov.af -Token <TOKEN>
   ```

   Add `-CaFile .\ministry-ca.pem` if the platform's certificate comes from the ministry's own CA.
   If PowerShell refuses to run the script, run once in that window:
   `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` (applies to this window only).

5. Check:

   ```powershell
   Get-Service netmon-agent
   ```

   Logs: `C:\ProgramData\NetMon\Agent\logs`. The server shows **Online** in the web UI within one
   report interval.

## What the installer does

| Item | Location / setting |
|---|---|
| Program | `C:\Program Files\NetMon Agent\` (jar + `netmon-agent.exe` service wrapper + XML) |
| Configuration (no secrets) | `C:\ProgramData\NetMon\Agent\agent.properties` |
| Credential | `C:\ProgramData\NetMon\Agent\state\credentials.json` |
| Folder permissions | Only SYSTEM, Administrators and LOCAL SERVICE; LOCAL SERVICE may write only `state` and `logs`. The agent refuses to start if the credential file is readable by Everyone/Users. |
| Service | `netmon-agent`, automatic (delayed) start, restarts on failure, runs as **NT AUTHORITY\LocalService** (not LocalSystem, not an administrator) |
| Performance counters | LOCAL SERVICE is added to the built-in group **Performance Monitor Users** so it can read counters |

## Differences from Linux (by design)

| Value | Windows |
|---|---|
| Load average | Does not exist on Windows. The **processor queue length** is shown instead. |
| CPU I/O wait | Not available on Windows: shown as "—", never as 0. |
| Swap | The **page file** is shown in the swap fields. |
| Disks | Local fixed volumes (`C:\`, `E:\`, …). |
| Disk activity | Per physical disk (e.g. `0_C:`). |
| Process owner | Not collected: Windows only shows it to administrators. |
| Services | Windows service names, e.g. `W3SVC`, `MSSQL$SQLEXPRESS`, `Spooler`. Read-only. |

## Upgrade, re-enroll, remove

- **Upgrade:** run `install-agent.ps1` again with a new jar and a new token. The existing
  configuration is kept.
- **Re-enroll only:** create a token in the UI, then in an admin PowerShell:
  `java -jar "C:\Program Files\NetMon Agent\netmon-agent.jar" enroll --config C:\ProgramData\NetMon\Agent\agent.properties --token <TOKEN>`
  and `Restart-Service netmon-agent`.
- **Remove:** `.\deploy\windows\uninstall-agent.ps1` (add `-KeepData` to keep configuration and logs),
  then revoke the agent in the UI.

## Packet capture (optional)

1. Install Wireshark with Npcap. Do not restrict Npcap to Administrators.
2. Re-run the installer with `-EnableCapture -CaptureInterfaces "Ethernet0"`.

Details and troubleshooting: [PACKET-CAPTURE.md](PACKET-CAPTURE.md).

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Collectors show **Permission denied** | LOCAL SERVICE cannot read performance counters: check it is in *Performance Monitor Users* (`lusrmgr.msc`), then `Restart-Service netmon-agent` |
| Network shows **Not supported** | The NetAdapter PowerShell module is missing (very old Windows) |
| All collectors **Unavailable** | PowerShell could not start: check `powershell.path` in `agent.properties` |
| `Enrollment failed (HTTP 401)` | Token wrong, expired or already used: create a new one |
| `PKIX path building failed` | Platform certificate not trusted: use `-CaFile` |
| `credentials.json is readable by …` | Folder permissions were changed: re-run the installer or restrict the folder again |
| Service starts and stops | Read `C:\ProgramData\NetMon\Agent\logs\netmon-agent.err.log` |
