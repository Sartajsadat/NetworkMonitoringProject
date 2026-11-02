# Known limitations and unsupported features

What NetMon does not do yet, or has not been tested on, as of Phase 9. The UI shows unsupported
features as unsupported instead of showing placeholder data.

## Not tested on real equipment yet
| Item | Status | What is needed |
|---|---|---|
| Windows agent (metrics, services, processes) | Built; tested with recorded PowerShell output | A test on Windows Server 2019/2022 (checklist in PHASE-3.md) |
| Windows packet capture (dumpcap + Npcap) | Built; simulated | Test with Npcap (PHASE-4.md) |
| Windows Defender Firewall rules and application policies | Built; simulated | Lab test (FIREWALL.md section 6, PHASE-8.md) |
| UFW firewall control | Built; simulated (UFW cannot be switched on safely on the build machine) | Lab test on an Ubuntu server with UFW enabled |

Tested for real on Linux: metrics, services, packet capture, NetFlow/IPFIX (softflowd), nftables
IP rules (dry run, enforce, drift, expiry, recovery), nftables application policies, TLS proxy,
agent over TLS, backup/restore, upgrade from the previous release's database.

## Not supported (by design or pending information)
- **Central firewalls** (FortiGate, Palo Alto, Cisco, MikroTik…): dry-run review only. Adapters
  will be built for the ministry's exact models and versions, once they are named.
- **firewalld** (RHEL family): detected and refused; NetMon does not mix firewall managers.
- **Application identity by business name or systemd unit:** only Linux accounts and Windows
  programs/services can be enforced (PHASE-8.md explains why).
- **Allow rules** only make exceptions to NetMon's own block rules; they do not open ports closed by
  other rules.
- **Network-wide packet capture** needs a capture sensor on a mirror port or TAP; an agent sees only
  its own server's traffic (NETWORK-VISIBILITY.md).

## Not built
- **Centralized logs** (journald / Windows Event Log viewer) and the **hosted-application
  inventory** with health checks: marked "Phase 5b" in the menu.
- **Notifications** (email, SMS, chat): alerts are shown in the UI only. The brief makes this optional.
- **SNMP polling** of switches and routers.
- **Firewall syslog parsing** in vendor formats (pending the equipment list).
- **Proxy / gateway application control.**

## Operational limits
- **One backend instance.** Scheduled jobs (retention, alert evaluation, firewall expiry) assume a
  single backend; running several replicas needs a scheduler lock (e.g. ShedLock) first.
- **Raw metrics** are stored in one table without partitioning. This is measured to be fine for a few
  hundred servers (PHASE-9.md); for thousands, consider monthly partitions or TimescaleDB.
- **Sampled flows** are shown as reported, not scaled up.
- **IPv6 neighbours** are not collected on Linux (Windows collects both).
- **Enrolment and sign-in share a rate limit** of 20 per minute per address. Enrolling hundreds of
  servers from behind one NAT address takes a while.
- **Health check details** on the Platform health page come from the backend in English; titles are
  translated.
