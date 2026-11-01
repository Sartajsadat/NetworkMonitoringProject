# Packet capture guide

Packet capture is **off** on every server until that server's administrator turns it on. Capture
only on ministry-hosted servers and networks you are authorised to monitor. Packets can contain
passwords, documents and personal data.

## 1. What a server can see (visibility levels)

| Level | Where the agent captures | What it sees | Typical use |
|---|---|---|---|
| **A — host** | The server's own network card | Traffic to and from **this server only** | "Why is this web server slow / refusing connections?" |
| **B — mirror port** | A second card on a sensor server, connected to a switch **SPAN / mirror port** | Copies of the traffic of the mirrored ports or VLANs | Watching several servers or a VLAN without touching them |
| **C — network TAP** | A sensor server behind a hardware **TAP** on a link | Everything on that link, including errors a switch would drop | Core links, evidence-quality captures |

Level A needs nothing extra. For B and C:

1. Install the agent on a dedicated sensor server.
2. Allowlist only the mirror or TAP card: `capture.interfaces=ens224`.
3. Configure the switch or TAP as the network team decides.

The platform does not configure switches.

## 2. Turning capture on

### Linux
```bash
sudo deploy/linux/enable-capture.sh --interfaces eth0 --max-minutes 60 --max-megabytes 1024
```
The script:

1. installs dumpcap;
2. lets only the `wireshark` group run it;
3. gives dumpcap the file capabilities `cap_net_raw,cap_net_admin`;
4. installs the systemd drop-in `netmon-agent.service.d/capture.conf`;
5. sets `capture.*` in `/etc/netmon-agent/agent.properties`;
6. restarts the agent;
7. checks the result as the agent user.

The agent's Java process keeps **no** capabilities; only dumpcap does.

To turn it off again:
```bash
sudo deploy/linux/enable-capture.sh --disable
```

### Windows
1. Install Wireshark with **Npcap**. Do **not** tick *Restrict Npcap driver's access to
   Administrators only*: the agent runs as LOCAL SERVICE.
2. Re-run the installer with capture enabled:
   ```powershell
   .\install-agent.ps1 -Jar .\netmon-agent.jar -WinSW .\WinSW-x64.exe -Url https://netmon.example.gov.af -Token <TOKEN> -EnableCapture -CaptureInterfaces "Ethernet0"
   ```
   or edit `C:\ProgramData\NetMon\Agent\agent.properties` (see the example file) and restart the
   `netmon-agent` service.

Interfaces can be given by the name in brackets from `dumpcap -D`, for example `Ethernet0`.

### Check on the server
```bash
java -jar /opt/netmon-agent/netmon-agent.jar capture-check --config /etc/netmon-agent/agent.properties
```
It prints whether capture is enabled and available, the allowed interfaces and the dumpcap
version. The same information appears in the web UI when you choose the server under
**Captures → New capture**.

### Agent settings (`capture.*`)
| Setting | Default | Meaning |
|---|---|---|
| `capture.enabled` | `false` | Master switch, local only |
| `capture.interfaces` | empty | Allowed interfaces, comma separated. Empty = every real interface (pseudo interfaces such as `any` must be listed explicitly) |
| `capture.max-duration-seconds` | `3600` | Longest capture this server accepts |
| `capture.max-megabytes` | `1024` | Largest capture this server accepts |
| `capture.dumpcap-path` | `/usr/bin/dumpcap`, `C:\Program Files\Wireshark\dumpcap.exe` | Fixed program path |
| `capture.spool-dir` | `<state.dir>/captures` | Pieces wait here until uploaded, then are deleted |
| `capture.min-free-disk-megabytes` | `512` | The capture stops if free space drops below this |

### Platform settings (environment)
| Variable | Default | Meaning |
|---|---|---|
| `CAPTURE_STORAGE_DIR` | `/var/lib/netmon/captures` | Where PCAPNG files are kept (Docker volume `capture-data`) |
| `CAPTURE_STORAGE_QUOTA_BYTES` | 10 GB | Total space for all capture files |
| `CAPTURE_MAX_DURATION_SECONDS` / `CAPTURE_MAX_BYTES` | 3600 / 1 GB | Upper limits for any request |
| `CAPTURE_DEFAULT_RETENTION_DAYS` / `CAPTURE_MAX_RETENTION_DAYS` | 7 / 90 | Automatic deletion |
| `CAPTURE_MAX_INDEXED_PACKETS` | 500000 | Packets listed per capture (the download has all) |
| `TSHARK_PATH` | `/usr/bin/tshark` | TShark used for analysis |

## 3. Capture filter or display filter?

| | Capture filter (BPF) | Display filter (Wireshark) |
|---|---|---|
| Where | On the server, by dumpcap | On the platform, by TShark |
| When | Before anything is stored | Any time afterwards |
| Effect | Packets that do not match are **never recorded** | Only hides or shows recorded packets |
| Example | `host 10.0.0.10 and tcp port 443` | `ip.addr == 10.0.0.10 && tcp.flags.reset == 1` |

Useful display filters (also under **Examples** in the analyzer):

| Filter | Shows |
|---|---|
| `tcp.flags.reset == 1` | Refused or aborted connections |
| `tcp.analysis.retransmission` | Packet loss and retransmissions |
| `tcp.flags.syn == 1 && tcp.flags.ack == 0` | New connection attempts |
| `http.response.code >= 400` | HTTP errors |
| `dns.flags.rcode != 0` | Failed DNS lookups |
| `tls.handshake.type == 1` | TLS client hellos (server names) |

## 4. Who can do what
| Action | Roles |
|---|---|
| See captures, packet list, protocol tree, statistics | Super admin, network admin, security analyst |
| Start/stop captures, view raw bytes, download PCAPNG | Super admin, network admin |
| Delete files before retention ends | Super admin |

Every start, stop, status change, raw-bytes view, download and deletion is in the audit log.
Opening a capture is also recorded, at most once per user and capture every 30 minutes.

## 5. Troubleshooting

| What you see | Cause and fix |
|---|---|
| Server shows **capture not enabled** | `capture.enabled` is false. Run `enable-capture.sh` (Linux) or `-EnableCapture` (Windows). |
| **The agent account may not run /usr/bin/dumpcap** | The agent user is not in the `wireshark` group, or the systemd drop-in is missing. Run `enable-capture.sh` again and check `systemctl cat netmon-agent`. |
| **dumpcap lists no interfaces** | dumpcap has no capture permission. On Linux check `getcap /usr/bin/dumpcap` (should show `cap_net_admin,cap_net_raw=eip`). The drop-in must keep `NoNewPrivileges=no`. On Windows, Npcap may be restricted to Administrators: reinstall Npcap without that option. |
| **None of the interfaces in capture.interfaces exists** | Wrong name. Compare with `dumpcap -D`. On Windows use the name in brackets (`Ethernet0`). |
| Capture stays **Waiting for agent**, then **Failed** after 5 min | The agent is offline, or capture is off on that server. Check `journalctl -u netmon-agent` (Linux) or `C:\ProgramData\NetMon\Agent\logs` (Windows). |
| **Invalid capture filter** right after starting | The BPF expression does not compile on that interface. The message shows dumpcap's explanation. Example: `tcp port` needs a number. |
| Capture **Failed: dumpcap failed: …** | dumpcap's last message is shown, for example a missing interface or no permission. |
| **0 packets** | Nothing matched the capture filter, or the traffic does not pass this card (see visibility levels). |
| **packets dropped by the server** in the status | The server could not keep up. Use a narrower capture filter or a smaller snapshot length. |
| **Capture storage is full** | Delete old captures (super admin) or raise `CAPTURE_STORAGE_QUOTA_BYTES`. |
| Display filter is **red** | TShark's own error message is shown under the field. |
| **Packet analysis took too long** | The display filter ran longer than `TSHARK_TIMEOUT` (2 min). Narrow the filter or the capture. |
| **TShark is not installed** banner on Captures | The backend host has no TShark. The Docker image includes it; without Docker run `sudo apt install tshark` (answer *No* to non-root capture). |
| **The download link expired** | Download links work once, for 60 seconds. Click **Download** again. |
