# Internet control (websites and programs on/off)

One page, **Internet control**, with simple switches per computer:

| Switch | What it does | How it works |
|---|---|---|
| **Website** (e.g. `youtube.com`) | On = the site does not open in any browser on that computer | The agent writes the name, `www.` and `m.` into NetMon's own section of the computer's **hosts file**, pointing at `0.0.0.0` / `::` |
| **Program** (Windows `.exe` path, or Linux account) | Off = that program cannot reach the internet; the local network still works | A "Block internet access" application policy ([PHASE-8.md](PHASE-8.md)) on the computer's own firewall |

Only super and network administrators can use the page.

## Personal mode

By default every program switch is a request that a **second administrator** must approve (the
ministry workflow). For one person using NetMon on their own computers, set in the backend settings
(`.env`, or `backend\.env.local` with `run-dev.ps1`):

```
PERSONAL_MODE=true
```

and restart the backend. Then switches (and every firewall rule an administrator requests) apply at
once, with no approval step and no need to switch the computer's firewall target to *Enforce*.
Platform health shows a warning while it is on. Never use it on a shared or ministry deployment.

## Switching it on for a computer

The computer's own administrator decides; the platform cannot switch these on remotely.

**Windows** (admin PowerShell): add to `C:\ProgramData\NetMon\Agent\agent.properties`

```
sites.enabled=true
firewall.enabled=true
firewall.enforcement=true
```

The service must run as LocalSystem (`sc.exe config netmon-agent obj= LocalSystem`), then
`Restart-Service netmon-agent`. A new installation can use
`install-agent.ps1 ... -EnableSites -EnableFirewall -FirewallEnforce` instead.

**Linux**: `sudo deploy/linux/enable-sites.sh` (websites) and
`sudo deploy/linux/enable-firewall.sh --enforce` (programs; nftables).

## Limits (honest)

- A hosts file has no wildcards: `youtube.com`, `www.youtube.com` and `m.youtube.com` are blocked,
  other sub-domains are not. Add them separately if needed.
- A browser with **secure DNS / DNS over HTTPS** turned on can bypass the hosts file. Turn it off in
  the browser's settings (Chrome/Edge: Privacy and security → Use secure DNS).
- A page that is already open may keep working until it is reloaded (the browser keeps its
  connection and DNS cache for about a minute).
- Windows Defender sometimes reports changes to the hosts file; that warning refers to NetMon's
  own lines.
- On Linux a program switch covers every program of that account (nftables matches accounts, not
  programs).

## Undo by hand

- Websites: delete the lines between `# BEGIN NetMon blocked websites` and `# END NetMon blocked websites`
  in `C:\Windows\System32\drivers\etc\hosts` or `/etc/hosts` (Linux: `sudo deploy/linux/enable-sites.sh --disable`).
- Programs: `Remove-NetFirewallRule -Group NetMon` (Windows) or `nft delete table inet netmon` (Linux).
