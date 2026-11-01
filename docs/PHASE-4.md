# Phase 4 — Packet analyzer

**Goal:** capture packets on monitored servers with Wireshark's tools, analyse them centrally
(packet list, protocol tree, display filters, statistics, conversations), and keep the whole
lifecycle authorised, limited, audited and deleted on time.

**Result:** working end to end on Linux. Tests:

| Part | Tests |
|---|---|
| Backend | 97 (17 capture) |
| Agent | 40 (11 capture) |
| Frontend | 60 (10 capture) |

A real run on this machine used the agent as an **unprivileged user**. dumpcap had only
`cap_net_raw,cap_net_admin`, and real traffic was generated. Results:

- 996 packets were captured.
- All 996 were indexed by the platform.
- The downloaded PCAPNG held exactly 996 packets.

Captures were started, watched live, filtered, downloaded and stopped from the web UI, in all
three languages. Windows capture is built, but still needs a test on a real Windows server
(section 4).

## 1. How it works

```
 Browser ── request (reason, filter, limits) ──► Backend ──► agent_tasks (typed START/STOP)
                                                     ▲
 Agent (polls every 5 s) ◄───────────────────────────┘
   │ checks LOCAL rules: capture.enabled, interface allowlist, max duration/size
   │ runs: dumpcap -i IFACE [-f BPF] -s SNAPLEN -n -q -a duration:N -b duration:15 -b filesize:32768 -w spool/cap.pcapng
   │ uploads every finished piece (PUT), deletes it, reports status
   ▼
 Backend stores pieces as files (CAPTURE_STORAGE_DIR, mode 600)
   └► TShark (fixed path, -n, argument list) indexes packet METADATA into packet_summaries
      └► UI: packet list, display filters, protocol tree, bytes, statistics, download
```

- **Capture runs on the server, analysis on the platform.** Wireshark's `dumpcap` is the only
  program that touches packets. TShark reads the stored files on the platform.
- **Files, not the database.** PCAPNG pieces live on disk. The database keeps:
  - the capture request;
  - file bookkeeping;
  - per-packet metadata: addresses, ports, protocol, length, TCP flags, DNS name, a short summary.

  It never stores payload bytes.
- **Live analysis.** dumpcap starts a new piece every 15 s (or every 32 MB). Each finished piece is
  uploaded and indexed immediately, so packets appear while the capture is still running.
- **One capture per server at a time**, so captures cannot pile up on a busy server.

## 2. What was built

### Agent (`agent/.../capture/`)
- **Off by default.** Capture works only if the server's own administrator sets
  `capture.enabled=true`. The platform cannot switch it on.
- **Local limits win.** The agent checks every request against its own settings before dumpcap
  starts:
  - interface allowlist (`capture.interfaces`);
  - maximum duration and size;
  - snapshot length;
  - a filter that contains only printable characters.
- **Filter is checked first.** The capture filter is compiled with `dumpcap -d` before the
  capture starts. dumpcap 4.2 exits with 0 even for an invalid filter, so the agent looks for the
  compiled BPF code instead of trusting the exit code.
- **Fixed program, fixed argument list, no shell.** The filter is one argument after `-f`, so
  input such as `tcp port 80; rm -rf /` is only an (invalid) filter.
- **Capability report.** Every report says whether capture is enabled and available, and why not:
  - dumpcap missing;
  - no permission;
  - no allowed interface.

  `netmon-agent capture-check` prints the same information on the server.
- **Ending the capture.** The agent stops dumpcap on any of these:
  - a stop request from the platform;
  - the size limit;
  - low disk space on the server;
  - a duration safety margin.
- **Upload handling:**
  - Pieces stay in the spool while the platform is unreachable and are retried.
  - Unsent pieces are dropped after 24 h.
  - Leftovers from a crashed run are removed at start.
- **Final report.** dumpcap's own packet count, and any drops, are sent with the final status.

### Install scripts
- **Linux: `deploy/linux/enable-capture.sh`** (or `install-agent.sh --enable-capture`). It:
  - installs dumpcap;
  - lets only the `wireshark` group run it;
  - gives dumpcap the file capabilities `cap_net_raw,cap_net_admin`;
  - installs the systemd drop-in `netmon-agent.service.d/capture.conf`;
  - checks the result as the agent user.

  The agent's Java process still has **no capabilities**; only dumpcap does. `--disable` turns
  capture off again.
- **Windows: `install-agent.ps1 -EnableCapture [-CaptureInterfaces "Ethernet0"]`.**
  - It requires Wireshark (dumpcap.exe) and Npcap.
  - Npcap must *not* be restricted to Administrators, because the service runs as LOCAL SERVICE.

### Backend (`backend/.../capture/`)
- **Tables** (migration `V3`):
  - `packet_captures`
  - `capture_segments`
  - `agent_tasks` (typed START/STOP tasks only)
  - `packet_summaries`
- **Request rules:**
  - A reason (at least 10 characters) is required; a ticket reference is optional.
  - Duration, size, snapshot length and retention are capped by the platform's limits *and* by
    the server's own limits.
  - A storage quota (`CAPTURE_STORAGE_QUOTA_BYTES`) counts the space reserved by running captures.
- **Roles** (checked on every request):

  | Action | Roles |
  |---|---|
  | View captures, packet list, protocol tree, statistics | SUPER_ADMIN, NETWORK_ADMIN, SECURITY_ANALYST |
  | Start/stop, raw bytes, download | SUPER_ADMIN, NETWORK_ADMIN |
  | Delete files before retention ends | SUPER_ADMIN |
- **Display filters.** Wireshark display filters are validated and run by TShark over the stored
  files:
  - invalid filters return TShark's own message;
  - results are capped at 50,000 matches;
  - results are cached.
- **TShark is fixed and limited.** It runs with a fixed path, `-n` (no name lookups) and an
  argument list, with a 2-minute timeout.
- **Protocol tree.** The tree comes from TShark's PDML output. It is parsed with external entities
  and DOCTYPE disabled. Fields TShark marks hidden are left out.
- **Download:**
  - PCAPNG, streamed.
  - The browser asks for a **one-time ticket** (60 s, single use, only its hash is kept) and then
    downloads natively.
  - Large files never pass through browser memory, and the download is audited as the user who
    asked for it.
- **Audit log:**
  - Always recorded: capture requested, stop, status changes from the agent, raw bytes viewed,
    downloaded, files deleted.
  - Opening a capture is recorded at most once per user and capture every 30 minutes.
- **Housekeeping jobs:**
  - A request the agent never picks up fails after 5 minutes.
  - A capture whose agent stops reporting fails after a grace period.
  - Retention deletes files and packet metadata, and keeps the record.
- **Docker image** now includes TShark (without setuid dumpcap) and a `capture-data` volume.
- **nginx:**
  - The agent upload path alone allows 70 MB bodies; everything else keeps the 2 MB limit.
  - Downloads are streamed.

### Web UI (English, Pashto, Dari)
- **Sidebar:** *Packet analyzer* and *Captures* are now real pages, shown only to roles that may
  see captures. *Packet analyzer* opens the latest capture that has packets.
- **Captures:**
  - history with status, packets, size and retention;
  - storage quota usage;
  - status filter and search.
- **New capture:**
  - The server list shows each server's capture state. If a server cannot capture, the page
    explains why (for example "only the server's administrator can switch it on").
  - Interface choice comes from the server's allowlist.
  - BPF presets.
  - A short guide: capture filter vs display filter.
  - Snapshot-length choices with a data-minimisation note.
  - Retention, reason, ticket reference.
  - A required "I am authorised" confirmation.
- **Analyzer:**
  - live progress (time and size);
  - packet table: resets marked red, retransmissions amber;
  - quick filters (address, protocol, port, text);
  - Wireshark display filter with examples, checked before it is applied;
  - protocol tree;
  - raw bytes (managers only, with a warning that it is audited);
  - statistics: protocols, packets over time, conversations, top sources, destinations and ports,
    DNS names;
  - PCAPNG download;
  - stop;
  - delete files (super admin).
- **Dashboard:** new *Active captures* tile. The *Packet capture* module now reports how many
  servers can capture.
- **Text and layout:**
  - 201 new texts in each language; the old "arrives in phase 4" text was replaced.
  - Packet data (addresses, filters, protocol tree, hex) stays left-to-right inside right-to-left
    pages.

## 3. Problems found by the end-to-end run (fixed)

1. **Final status overwritten.**
   - What happened: the agent reported COMPLETED, but the capture stayed RUNNING.
   - Why: background indexing of the last piece saved the whole capture row and undid the
     agent's status change.
   - Fix:
     - The indexer now updates only its own counters.
     - The entity writes only changed columns.
     - Indexing passes never overlap.
   - Test: a regression test reproduces the timing; it fails on the old code and passes now.
2. **Duplicate lines in the protocol tree.**
   - What happened: lines were shown twice (for example HTTP headers).
   - Why: TShark marks hidden fields with `hide="yes"`; the parser only recognised `"1"`.
   - Fix: the parser now handles both. The unit test now uses TShark's real format.
3. **Misleading agent message.** The agent said "dumpcap not found" when dumpcap existed but the
   agent user had no permission. It now says exactly that, and how to fix it.

## 4. Still to test on Windows (please check)

There is no Windows machine in the build environment. Please check:

1. Install Wireshark **with Npcap** (do not tick *Restrict Npcap driver's access to
   Administrators only*).
2. Run `.\install-agent.ps1 ... -EnableCapture -CaptureInterfaces "Ethernet0"` (the adapter name
   as shown in brackets by `& "C:\Program Files\Wireshark\dumpcap.exe" -D`).
3. As administrator, run
   `java -jar "C:\Program Files\NetMon Agent\netmon-agent.jar" capture-check --config C:\ProgramData\NetMon\Agent\agent.properties`.
   It should print `"available" : true` and your adapter.
4. In the UI, start a 1-minute capture on that server and check:
   - the status goes *Waiting for agent → Capturing → Completed*;
   - packets appear while it runs;
   - the download opens in Wireshark.
5. Start another capture and press **Stop**. On Windows the agent ends dumpcap with a hard stop
   (Windows has no clean "please finish" signal for it). The last piece can therefore end in the
   middle of a packet. The platform keeps every complete packet, so this is expected. Please
   report anything else.

## 5. Limits and notes

- **Visibility:** an agent sees only traffic that reaches its own network card. See
  [PACKET-CAPTURE.md](PACKET-CAPTURE.md) (visibility levels A/B/C).
- **Index limit:** at most 500,000 packets per capture are listed
  (`CAPTURE_MAX_INDEXED_PACKETS`). The download always contains everything captured.
- **Conversations** are grouped by address pair. Ports are shown under *Top ports*.
- **Hidden payloads:** with the default snapshot length of 256 bytes, payloads after the first
  bytes are not stored. Choose more only when the investigation needs it.
- **Time zones:** packet times show the browser's local time; everything is stored in UTC.
