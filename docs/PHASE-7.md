# Phase 7 — Firewall and access control

**Goal:** block or allow IP addresses on firewalls the platform is connected to, with requests,
second-person approval, a preview of the exact change, protection of management addresses,
verification, expiry, audit and a recovery procedure. Start in dry run; enforce only after lab
tests and administrator approval.

**Result:** working end to end. How to use it, and the recovery procedure, are in
**[FIREWALL.md](FIREWALL.md)**.

| Part | Tests |
|---|---|
| Backend | 128 (validator, workflow, four-eyes approval, drift, expiry, timeouts, roles) |
| Agent | 54 (12 new: nftables, UFW and Windows adapters with fakes, local safety, expiry, injection attempts) |
| Frontend | 81 (8 new) |

Automated tests never change a real firewall: they use in-memory fakes.

## 1. Real test with nftables (lab machine, test-only addresses)

Two test addresses (198.51.100.1 and .5, a documentation range) were put on this machine's
loopback, with a small web server on .1. The agent ran with nftables.

| Step | Result |
|---|---|
| Agent check | Detected nftables; protected the platform's own address (127.0.0.1) automatically |
| Dry run | Rule approved → agent ran `nft -c` → **Dry run (not applied)**; no `netmon` table created; traffic still passed (HTTP 200) |
| Protected address | Blocking 127.0.0.0/8 was refused before it could be requested |
| Permissions | Analyst could not approve; network admin could not switch on enforcement (super admin only) |
| Enforce | Super admin switched the target to Enforce → rule approved → agent applied it in table `inet netmon` and read it back → **Applied**. Traffic from .5 was dropped; traffic from .1 to the same port still passed (the rule only affects what it names) |
| Drift | The rule was deleted by hand with `nft delete rule` → within one report: **Unverified**, ERROR event, "Unexpected firewall change" alert |
| Removal | Removed on the platform → agent confirmed → **Removed** |
| Expiry | A 90-second rule: the agent removed it by itself at expiry, the platform recorded **Expired**, traffic passed again |
| Recovery | `nft delete table inet netmon` removed everything at once; the platform noticed (Unverified) |

Only the `inet netmon` table was ever changed; the machine's other tables (Docker's) were untouched.

## 2. What was built

### Platform
- **Firewall targets** (`/firewall`):
  - *host firewall of a server* (through its agent);
  - *central firewall*: dry run only — a reviewed request and a vendor-neutral description of the
    change; nothing is sent to the device.
- **Modes:** every target starts in *Dry run*. *Enforce* needs a super administrator, a reason, and
  a server whose administrator allowed enforcement locally.
- **IP allow / block** (`/ip-policies`):
  - request form with validation, preview of the exact command, scope in plain words ("applies only
    to server web-01 — not a network-wide block"), warnings to acknowledge, expiry (default 24 h);
  - approval by a second person (super or network admin, never the requester), with the safety
    checks run again;
  - status, history, approvals, the agent's command and a recovery card on each rule;
  - filters: pending, active, problems, ended.
- **Statuses:** Pending (approval / sent to server / removal), Applied, Dry run, Failed, Unverified,
  Expired, Removed, Rejected, Cancelled.
- **Never "success" because a task was sent:** Applied only after the agent read the rule back;
  no answer within `FIREWALL_TASK_TIMEOUT` → Failed.
- **Verification on every report**, drift alert (new default rule "Unexpected firewall change"),
  unknown NetMon rules reported.
- **Audit and timeline:** every action (`FIREWALL_*` audit actions; *Firewall* event category, one
  incident id per rule).
- **Database (V6):** `firewall_integrations`, `firewall_rules`, `access_policy_approvals`; agent
  tasks gained the two firewall task types.
- **Dashboard:** the "Firewall integrations" tile shows the real number of targets and modes.

### Agent (version 0.6.0)
- Detects the firewall manager and never mixes them: UFW if enabled; firewalld → not supported;
  otherwise nftables in its own table `inet netmon`; Windows Defender Firewall (group NetMon).
- Two local switches, both off by default: `firewall.enabled` (report + check) and
  `firewall.enforcement` (change).
- Re-validates every field (literal addresses only, no host names, strict ports, tag pattern) and
  refuses unsafe rules locally: loopback, the platform address, configured protected addresses and
  addresses of live SSH/RDP/WinRM sessions.
- Changes are atomic nft scripts built from validated fields; UFW via `sudo -n /usr/sbin/ufw`;
  Windows via a bundled script with inputs in environment variables. No shell anywhere.
- Keeps a local state file, removes expired rules even when the platform is unreachable, and
  retries results until the platform accepts them.
- `netmon-agent firewall-check` shows what it would use, changing nothing.
- Install: `deploy/linux/enable-firewall.sh` (private `nft` copy with only `cap_net_admin`, or a
  sudoers entry for ufw) and `install-agent.ps1 -EnableFirewall [-FirewallEnforce]`.

## 3. Limits and next steps
- **Central firewalls stay dry run.** Please send the ministry's firewall models and versions
  (e.g. FortiGate, Palo Alto, Cisco ASA/FTD, MikroTik, pfSense); adapters will be built and
  lab-tested only for those.
- **firewalld** (RHEL/Rocky/Alma default) is detected and refused for now; supporting it means using
  firewalld's own commands rather than a separate nftables table.
- **UFW and Windows** were tested with simulated commands only (UFW cannot be switched on safely in
  this build machine, and there is no Windows machine here). Please run the checklist in FIREWALL.md
  on a lab server of each kind before enforcing.
- **Allow rules** only make exceptions to NetMon's own block rules; they do not open ports closed by
  other firewall rules.
- **Application access policies** (per-application allow lists) are Phase 8.
