# Phase 8 — Application-specific access control

**Goal:** let authorised administrators restrict or permit the network access of a specific
application, with the same approval, audit, expiry and rollback as IP rules, and report honestly
where it cannot be enforced.

**Result:** working end to end on Linux with nftables (real test below). Windows Defender Firewall
support is built and tested with simulated commands; it needs a lab test on Windows Server.

| Part | Tests |
|---|---|
| Backend | 6 new (identity validation, policy checks, previews, workflow, unsupported targets) |
| Agent (0.7.0) | 58 (4 new: parsing and injection attempts, nftables batch, Windows rules, unsupported backends) |
| Frontend | 85 (4 new) |

## 1. The key point: what identifies an application

A firewall cannot recognise an application by its business name ("Payroll"). Each application is
registered with an identity its server can really enforce:

| Identity | Server | Enforced with | Matches |
|---|---|---|---|
| **Linux account** | Linux, nftables | `meta skuid "<account>"` | every process running as that account |
| **Windows program** | Windows | `New-NetFirewallRule -Program "C:\...\app.exe"` | that executable |
| **Windows service** | Windows | `New-NetFirewallRule -Service <name>` | that service |

Not supported, and shown as such ("Not supported" with the reason, and no request button):
- **UFW servers:** UFW has no per-application matching.
- **firewalld servers:** firewalld is not managed by NetMon (Phase 7).
- **Central firewalls:** no adapter exists for the ministry's equipment yet.
- **Agents older than 0.7.0.**

Linux systemd *unit* matching (cgroups) was left out on purpose: nftables binds a cgroup when the
rule is loaded, so the rule silently stops matching after the service restarts. A dedicated
account per application (`User=` in the unit) is reliable instead.

Each application's page lists the processes seen running with its identity in the latest report.
That confirms the identity is right; a policy for an identity that never runs protects nothing.

## 2. Policy types

| Policy | Effect (only connections the application **starts**) |
|---|---|
| **Block internet access** | Private, shared, link-local and loopback networks stay reachable (10/8, 172.16/12, 192.168/16, 100.64/10, 169.254/16, 127/8, fc00::/7, fe80::/10, ::1), plus listed exceptions; everything else is dropped |
| **Allow only approved destinations** | Only the listed address/protocol/port entries (and loopback) |
| **Block specific destinations** | Only the listed entries are dropped |

Replies to connections that other systems open to the application still pass (`ct direction
reply accept` on Linux; Windows Firewall is stateful). A web server with "block internet" therefore
still serves its users, but cannot open connections to the internet itself.

On Linux each policy is one chain `app_<tag>` in NetMon's table `inet netmon`, entered from the
output chain. The jump rule is loaded last, so the chain is complete before any packet can enter it.

On Windows, block rules always win over allow rules. "Allow only" is therefore written as a block of
every other address range: the agent computes the exact ranges. Ports cannot be limited this way on
Windows, so such a request is refused with that explanation.

## 3. Safety
- **Refused identities:** `root`, the agent's own account, `systemd-network`/`systemd-resolve`;
  Windows `svchost.exe`, `lsass.exe`, `java.exe`, the agent, and services such as the firewall,
  DNS client, DHCP, RDP, WinRM and SSH. Both the platform and the agent check this.
- **Warnings to acknowledge:**
  - a Linux account covers every process of that account;
  - an "allow only" list without DNS;
  - what "internet" means;
  - no expiry;
  - dry run.
- **Validation:** strict addresses and ports; at most 50 destinations; no host names.
- **Workflow:** the same as IP rules — second-person approval, dry run until a super administrator
  enforces and the server allows it, read-back verification, drift alert, expiry (removed by the
  agent itself if needed), audit and timeline. The approval history and status are on the rule page.
- **Changing or deleting an application:** its identity cannot change while a policy is active.
  An application with policy history is kept for the audit trail.

## 4. Real test (lab machine)
A lab account `payroll` stood in for an application. Test web servers ran on 198.51.100.1:18081 (the
"database") and 198.51.100.9:18082 (anything else).

| Step | Result |
|---|---|
| Capability | Agent reported `applicationControl: [LINUX_USER]`; the application showed **Enforceable** |
| Preview | Scope "Only connections started by Payroll API (Linux user payroll) on server app-01…", shared-account and no-DNS warnings |
| Approve (allow only .1 tcp/18081) | Agent loaded the chain and read it back → **Applied** |
| Traffic | `payroll` → .1:18081 **200**, → .9:18082 **dropped**; `root` → both **200** (other applications unaffected) |
| Remove | Agent deleted the jump rule and chain, verified → **Removed**; `payroll` → .9 **200** again |
| Wrong account | `nft -c` reports "User does not exist" — a typo fails even in dry run |

## 5. Limits and next steps
- **Windows:** program and service policies need a test on a lab Windows Server (checklist in
  FIREWALL.md, section 6, with an application instead of an address).
- **Central firewalls:** application-aware policies (App-ID, FortiGate application control) can be
  added once the ministry's models are known.
- **Proxy/gateway control:** not built; it depends on the ministry's proxy.
