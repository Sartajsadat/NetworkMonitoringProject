# Phase 3 — Windows monitoring

**Goal:** a Windows agent using supported Windows interfaces, reporting to the same backend and
shown on the same dashboard as Linux.

**Result:** built and tested as far as possible without a Windows machine (see *What still needs a
Windows test* below). Agent: 29 tests (12 Windows). Backend: 80 tests (4 Windows). Frontend: 50 tests.
A lab run combined the real Linux agent with Windows-format reports produced by the real Windows
collector code, and both servers appeared correctly side by side in all three languages.

## 1. What was built, and how

### Agent
- **One jar for both systems.** A `Platform` interface selects Linux or Windows collectors at start;
  protocol, buffering, credentials, rotation and configuration are shared.
- **Windows data source:** one read-only PowerShell script bundled in the jar (`resources/windows/collect.ps1`),
  run each cycle with a fixed path (`%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe`),
  an argument list and `-EncodedCommand`, with a 60 s timeout. It never contains platform input;
  service names are validated and passed through `NETMON_SERVICES`.
- Each part of the script is independent, so one failure (for example *access denied* on the page
  file) does not stop the others. Failures become honest collector statuses
  (`PERMISSION_DENIED`, `UNSUPPORTED`, `UNAVAILABLE`, `ERROR`).
- **Rates from raw counters**, as on Linux. CPU uses the inverse 100 ns timer
  (`Win32_PerfRawData_PerfOS_Processor`), disk busy time uses `PercentIdleTime`, and bytes and
  packets come from cumulative counters, so values are exact over the report interval.
- **Windows specifics:** no load average (processor queue length instead), no I/O wait, the page
  file reported as swap, process owner not collected, Windows service names (`W3SVC`, `MSSQL$SQLEXPRESS`).
- **Credentials on Windows:** stored under `C:\ProgramData\NetMon\Agent\state`. The agent refuses
  the file if Windows permissions let Everyone/Users read it.

### Service installation (`deploy/windows/`)
- `install-agent.ps1`:
  - uses WinSW v2.12.0, with its SHA-256 checked;
  - runs the service as **LOCAL SERVICE**;
  - restricts folder permissions;
  - adds LOCAL SERVICE to *Performance Monitor Users*;
  - enrolls with the one-time token;
  - starts the service.
- `uninstall-agent.ps1` removes it.
- Both scripts were syntax-checked with PowerShell 7.

### Backend
- Accepts Windows adapter names with spaces and brackets (e.g. `vEthernet (Default Switch)`), but
  still rejects control characters.
- Allows `$` in service names.
- Warns when the agent's operating system differs from the one the server was registered with
  (`platformMismatch`).
- No database changes.

### Web UI (English, Pashto, Dari)
- The Agent tab shows Windows or Linux install steps depending on the server's operating system.
- Windows servers show "Processor queue n" instead of the load average, and a note instead of an
  empty load chart.
- Process lists explain that the owner is not collected on Windows.
- Old "Windows arrives in phase 3" texts were removed; 10 texts were added or changed in each language.

## 2. What still needs a Windows test (please check — see checklist)

These parts could not run here because there is no Windows machine in the build environment:

1. `collect.ps1` against real Windows CIM classes and NetAdapter cmdlets (field names and JSON shape
   were checked against the documented classes and with PowerShell 7 on Linux).
2. `install-agent.ps1` / WinSW service installation and the LOCAL SERVICE account.
3. Whether LOCAL SERVICE can read all counters after joining *Performance Monitor Users*.
4. Windows file permissions on `credentials.json` (the check uses Java's standard ACL API).
5. CPU cost of running PowerShell every interval (expected well under 1 s per run; with a 30 s
   interval that is a small load).

## 3. Test checklist for Windows

Do this on a **test** Windows Server, not production.

| # | Step | Expected |
|---|---|---|
| 1 | Register server *win-test-01* with OS **Windows**; Agent tab → Create enrollment token | Dialog shows the PowerShell command |
| 2 | Install Java 21; download `WinSW-x64.exe` v2.12.0; copy jar + WinSW + `deploy` | — |
| 3 | Admin PowerShell: run the command from the dialog | Ends with `netmon-agent Running` |
| 4 | Without installing, try `java -jar netmon-agent.jar collect-once` | JSON with host, cpu, memory, disks, network; collectors `OK` |
| 5 | Web UI after 1 minute | Server **Online**, status **Active**, agent **Healthy** |
| 6 | Overview tab | CPU %, memory, fullest disk (`C:\`), traffic, uptime match Task Manager roughly |
| 7 | Agent tab → collectors list | All `OK`. Any *Permission denied* → see troubleshooting |
| 8 | Agent tab → services `W3SVC` / `Spooler` (one per line), Save | Processes & services tab shows their state within one interval |
| 9 | Stop the Spooler service | Shows **Stopped** after the next report |
| 10 | Metrics tab after 10 minutes | Charts for CPU, memory/page file, disks, traffic, disk I/O |
| 11 | Stop the **platform** (backend) for 2 minutes, start it again | No gap appears in the charts |
| 12 | `icacls C:\ProgramData\NetMon\Agent\state\credentials.json` | Only SYSTEM, Administrators, LOCAL SERVICE |
| 13 | Revoke in UI | Agent log says it was revoked; server shows **No agent** |
| 14 | `uninstall-agent.ps1` | Service and folders removed |

If a step fails, send me the message from the screen or from
`C:\ProgramData\NetMon\Agent\logs\netmon-agent.err.log` and I will fix it.
