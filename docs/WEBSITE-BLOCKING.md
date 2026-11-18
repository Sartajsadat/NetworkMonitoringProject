# Website blocking (servers and staff PCs)

**Website blocking** blocks websites such as facebook.com on every device that has the NetMon agent
and website blocking set up: Linux or Windows, server or staff PC. Each website has one on/off switch.
No physical or network firewall is used.

## Using it

1. Open **Website blocking** in the menu.
2. Type a website (`facebook.com`, `https://www.facebook.com/...` also works) and press **Block**.
   The base name, `www.` and `m.` are blocked.
3. The switch turns blocking off and on again. **Delete** removes the website, which unblocks it everywhere.
4. The **Devices** column shows what the devices confirmed, for example *Blocked on 4 of 5 devices*.
   **Details** lists each device: *Blocked*, *Allowed*, *Waiting for the device…*, *Offline*
   (it updates when the device connects again) or *Not set up*.

Changes reach a device with its next report, normally within a minute. A browser that already
had the site open may keep using its open connection until the tab is closed or the browser restarted.

Only super and network administrators can change the list; everyone can view it. Every change is in
the audit log (`WEBSITE_ADDED`, `WEBSITE_SWITCHED`, `WEBSITE_DELETED`).

## Setting up a device (once per device)

The device needs the NetMon agent (0.8.0 or newer). A staff PC is added like a server: **Server
inventory → Add**, then install the agent with the enrollment token (AGENT-INSTALL.md /
AGENT-INSTALL-WINDOWS.md). Then, from the NetMon folder:

| Device | Command |
|---|---|
| Linux | `sudo ./deploy/linux/enable-website-blocking.sh` (or `install-agent.sh ... --enable-website-blocking`) |
| Windows (PowerShell as administrator) | `.\deploy\windows\enable-website-blocking.ps1` (or `install-agent.ps1 ... -EnableWebsiteBlocking`) |

Undo: `--disable` / `-Disable`. This removes NetMon's lines from the hosts file.

## How it works

- The platform sends the list of blocked websites with each agent's settings.
- The agent keeps a marked section of the device's **hosts file** equal to that list. It points each name
  to `0.0.0.0` and `::`, so the browser cannot connect. Other lines in the hosts file are never changed.
  If someone deletes the section, it is written again with the next report.
- The agent reads the hosts file back and reports which websites are really there. The page shows
  only what the devices confirmed.
- **Linux:** the agent stays unprivileged. It writes the list to `/var/lib/netmon-agent/blocked-sites.txt`.
  `netmon-hosts.path` then runs `/usr/lib/netmon-agent/netmon-hosts-apply` as root, which can change
  `/etc/hosts` and nothing else and ignores any line that is not a plain domain name.
- **Windows:** the agent writes `C:\Windows\System32\drivers\etc\hosts` itself. The setup gives its
  account (LOCAL SERVICE) permission to change that one file. Windows rereads the file by itself.
- Until the platform has sent a list, nothing is changed. A device that cannot reach NetMon keeps
  blocking what it had.
- NetMon's own address cannot be blocked.

## Limits (be honest with users)

- Blocking is by name in the hosts file. Someone with **administrator rights on the device** can remove it.
  A **VPN, a proxy, or typing the site's IP address** also gets around it.
- Only the base name, `www.` and `m.` are blocked, not every sub-domain. Add other names separately if needed,
  for example `web.facebook.com`.
- Windows: tested with simulated files only so far. Run a lab test on one PC first. Microsoft Defender may
  report changes to the hosts file for some well-known sites. If it does, allow the change in Defender.
- Linux: the helper and the agent's file format are tested together. The systemd path unit needs a test on a
  real Ubuntu machine.
