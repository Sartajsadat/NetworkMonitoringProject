# Firewall and IP access control

The platform can block or allow IP addresses on firewalls it is connected to. Every change is
requested, checked, previewed, approved by a second person, applied by a fixed adapter, verified,
and recorded. Nothing runs as a free-form command.

## 1. Where a rule takes effect (scope)

Each **firewall target** is one enforcement point. The pages always say which one.

| Target | What a rule changes | What it does **not** do |
|---|---|---|
| **Host firewall of a server** (agent) | nftables, UFW or Windows Defender Firewall on that one server | It is **not a network-wide block**. Other servers, and traffic that does not reach this server, are not affected |
| **Central firewall** (dry run only) | Nothing. The request is reviewed and the change is previewed; the device's administrators apply it through the device's own change process | No API call is made: no adapter exists for unidentified equipment |

Adapters for central firewalls will be added only for the exact models and versions the ministry
uses, after they are named and tested in a lab. No generic "firewall API" is guessed.

## 2. Two switches on each server, both OFF by default

The platform cannot turn firewall control on remotely. The server's own administrator decides:

| Setting (agent.properties) | Effect |
|---|---|
| `firewall.enabled=true` | The agent reports the firewall it found and **checks** approved rules (dry run): `nft -c`, `ufw --dry-run`, `New-NetFirewallRule -WhatIf` |
| `firewall.enforcement=true` | The agent may also really change the firewall |

On the platform each target also has a **mode**: *Dry run* (default) or *Enforce*. Only a
**super administrator** can switch a target to Enforce, with a reason, and only if the server allows
it. A rule is enforced only when both sides agree; otherwise it is checked and reported as
"Dry run (not applied)".

Recommended order: dry run in a lab → enforcement in the lab → integration tests → administrator
approval → enforcement on chosen production servers. Automated tests never touch production
firewalls (they use in-memory fakes; the real nftables test ran on a lab machine with test-only
addresses).

### Enabling on Linux
```bash
sudo deploy/linux/enable-firewall.sh                 # report + dry run only
```
```bash
sudo deploy/linux/enable-firewall.sh --enforce --protect 10.10.0.0/24,192.0.2.10
```
The script detects the firewall manager and never mixes them:
- **UFW enabled** → UFW commands through `sudo -n /usr/sbin/ufw` (a sudoers entry for that program
  only). NetMon rules are prepended and commented `netmon:<tag>`.
- **firewalld running** → not supported yet; nothing is changed.
- **otherwise** → nftables in NetMon's **own table `inet netmon`** (input/output chains, priority -10,
  policy accept). Nothing outside that table is touched (Docker's and the system's own tables stay
  as they are). The agent uses a private copy of `nft` with only the `cap_net_admin` capability; its
  Java process has no privileges.

Check at any time (changes nothing):
```bash
sudo -u netmon-agent java -jar /opt/netmon-agent/netmon-agent.jar firewall-check --config /etc/netmon-agent/agent.properties
```

### Enabling on Windows
```powershell
.\install-agent.ps1 ... -EnableFirewall                 # report + dry run (-WhatIf)
.\install-agent.ps1 ... -EnableFirewall -FirewallEnforce -FirewallProtect "10.10.0.0/24"
```
Changing Windows Defender Firewall needs administrative rights, so with `-EnableFirewall` the service
runs as **LocalSystem** instead of LocalService. Rules are created in the group **NetMon** with the
name `netmon-<tag>`. If a firewall profile is switched off, the agent reports it: rules are stored
but not enforced on that profile.

## 3. The request → approval → apply workflow

1. **Request** (super admin, network admin, security analyst): target, block/allow, direction,
   remote address or CIDR, optional local address, protocol, ports, **reason**, optional ticket,
   **expiry** (default 24 hours, at most 365 days).
2. **Validation**: strict CIDR (`10.0.0.5/24` is refused: host bits set), ports 1–65535 for TCP/UDP
   only, matching IP versions.
3. **Preview**: the exact command for the target's firewall, the mode, and the scope in plain words.
   Nothing is saved by a preview.
4. **Safety checks** (refuse):
   - blocking a **protected address**: `FIREWALL_PROTECTED_CIDRS` (default loopback) plus the
     platform address reported by the agent and the server's own `firewall.protected-addresses`;
   - blocking every address on every port.

   **Warnings** (must be acknowledged): the rule covers the requester's own address; it blocks
   SSH/RDP/WinRM ports for a large range; no expiry; allow-rule limits; dry run.
5. **Approval** by a super or network administrator **who is not the requester**. Every safety check
   runs again at approval time. The approval history is kept (`access_policy_approvals`).
6. **Apply** (server target): the platform queues a typed task. The agent re-validates every field
   (literal addresses only, no host names), runs its **own** safety checks — loopback, the platform
   address, configured protected addresses, and the addresses of **live SSH/RDP/WinRM sessions**
   (read from `/proc/net/tcp` or `Get-NetTCPConnection`) — then applies the rule.
7. **Verification**: the agent reads the firewall back. Only if the rule is found is it reported
   **APPLIED**; if the command succeeded but the rule is not there it is **UNVERIFIED**. A task sent
   to an agent is never counted as success: if no result arrives within `FIREWALL_TASK_TIMEOUT`
   (default 1 hour) the rule becomes **FAILED**.
8. **Continuous check**: every agent report lists the NetMon rules present. A rule that disappears
   (deleted by hand, firewall reloaded) becomes **UNVERIFIED**, an ERROR event is written and the
   "Unexpected firewall change" alert opens. When it returns it is APPLIED again. A NetMon-tagged
   rule the platform does not know is reported once as an "unknown NetMon rule".
9. **Removal / expiry**: removal is a task too; the rule is REMOVED only after the agent confirms
   it is gone. At expiry the platform removes it — and the **agent removes expired rules by itself**
   (from its local state file) even if the platform is unreachable.

### Statuses
| Shown as | Meaning |
|---|---|
| **Pending approval** / **Pending: sent to the server** / **Pending: removal** | Waiting for a decision, or for the agent's confirmation |
| **Applied** | Applied and read back from the firewall; checked again with every report |
| **Dry run (not applied)** | Checked only; nothing changed |
| **Failed** | Not applied (refused by a safety check, firewall error, server refused enforcement, or no answer) |
| **Unverified** | Should be present but was not found |
| **Expired** / **Removed** / **Rejected** / **Cancelled** | Ended |

Everything is in the audit log (`FIREWALL_*` actions) and on the event timeline (category
*Firewall*, one incident id per rule).

## 4. Recovery procedure

Use this if a rule cuts off access, or to remove everything NetMon did on a server.

1. **Wait for expiry if it is near**: rules with an expiry are removed by the agent itself at that
   time, even without the platform.
2. **From the platform**: open the rule (IP allow / block) → *Remove rule*. Or set the target back to
   *Dry run* so no new rule is enforced.
3. **On the server console** (out-of-band access, e.g. hypervisor or iLO/iDRAC console):
   - **nftables** — remove every NetMon rule at once:
     ```bash
     sudo nft delete table inet netmon
     ```
     or one rule: `sudo nft -a list table inet netmon`, then
     `sudo nft delete rule inet netmon input handle <N>`.
   - **UFW**: `sudo ufw status numbered`, then `sudo ufw delete <N>` for each rule commented
     `netmon:<tag>` (highest number first).
   - **Windows** (elevated PowerShell):
     ```powershell
     Remove-NetFirewallRule -Group NetMon
     ```
4. **Stop further changes**: `sudo deploy/linux/enable-firewall.sh --disable` (or set
   `firewall.enforcement=false` and restart the agent). Disabling does not remove applied rules —
   use step 3 for that.
5. The platform notices the removal on the next report (rule becomes **Unverified**, alert opens).
   Close it by removing the rule on the platform, which then records it as removed.

Every NetMon rule is tagged (`netmon:<tag>` / `netmon-<tag>`), so these commands never touch rules
created by anyone else.

## 5. Settings (platform)

| Variable | Default | Meaning |
|---|---|---|
| `FIREWALL_PROTECTED_CIDRS` | `127.0.0.0/8,::1/128` | Addresses no rule may block, on any target. Add the management and monitoring networks, jump hosts and the platform's own address |
| `FIREWALL_TASK_TIMEOUT` | `PT1H` | How long an approved rule may wait for the agent's result before it is marked FAILED |

## 6. Lab checklist before enforcing on a new kind of server

Run on a **lab** server with console access, never first on production. Use a test client address
that is not used for management.

1. Enable dry run only (`enable-firewall.sh` or `-EnableFirewall`) and run `firewall-check`: the
   detected firewall and the protected addresses must be right.
2. Add the server under *Firewall integrations*; the detected firewall shows there.
3. Request a block of the test client on one port with a 15-minute expiry; approve it as a second
   person. Status must be **Dry run (not applied)**, and the client must still connect.
4. Allow enforcement on the server (`--enforce` / `-FirewallEnforce`), switch the target to
   *Enforce* (super administrator), and repeat step 3. Status must be **Applied** and the client
   must be blocked — on that port only, from that address only.
5. Delete the rule by hand (section 4): status becomes **Unverified** and the alert opens.
6. Request another short rule and let it expire: it must disappear and show **Expired**.
7. Try to block your own management address: the request must be refused.
8. Run the recovery commands of section 4 once and confirm access is unaffected.

Done on this build machine for nftables (see PHASE-7.md). Still to do: UFW, firewalld-free RHEL
family, and Windows Server.
