# Installing the Linux monitoring agent

The agent is a small Java program that runs on each monitored Ubuntu/Linux server as an
unprivileged systemd service. It **connects out** to the platform over HTTPS; no port is opened
on the monitored server and it has no remote-shell capability.

## Requirements on the monitored server

| Item | Notes |
|---|---|
| Ubuntu 22.04 / 24.04 (other systemd Linux distributions should work) | Tested in a lab on Ubuntu 24.04 |
| Java 21 runtime | `sudo apt install openjdk-21-jre-headless` |
| Outbound HTTPS to the platform | Port 443 (or the port nginx is published on) |
| Correct clock (NTP) | Reports more than 5 minutes in the future are refused |

## 1. Build the agent jar (once, on any machine with JDK 21)

```bash
cd agent && ./mvnw package
```

The result is `agent/target/netmon-agent.jar` (about 2.5 MB). On Windows use `mvnw.cmd package`.

## 2. Create an enrollment token

In the web UI: **Server inventory → open the server → Agent tab → Create enrollment token**.
Only super administrators and infrastructure administrators can do this.

- The token is shown **once**, can be used **once**, and expires after 24 hours
  (`AGENT_ENROLLMENT_TOKEN_TTL`). Creating a new token cancels any earlier unused one.
- The dialog shows the exact install command, including the platform address
  (`AGENT_PUBLIC_URL`, or the address in your browser if that is not set).

## 3. Install on the server

Copy `netmon-agent.jar` and the `deploy/` folder to the server, then:

```bash
sudo ./deploy/linux/install-agent.sh --jar netmon-agent.jar --url https://netmon.example.gov.af --token <TOKEN>
```

If the platform uses a certificate from the ministry's own CA, add `--ca /path/to/ministry-ca.pem`.

The script:

1. creates the system user `netmon-agent` (no login shell, no home directory),
2. copies the jar to `/opt/netmon-agent/`,
3. writes `/etc/netmon-agent/agent.properties` (no secrets in it),
4. enrolls with the token, which stores the agent secret in `/var/lib/netmon-agent/credentials.json` (mode `0600`, owned by `netmon-agent`),
5. installs and starts `netmon-agent.service` (hardened: no capabilities, read-only system, private /tmp).

Check it:

```bash
systemctl status netmon-agent
```

```bash
journalctl -u netmon-agent -f
```

Within one report interval the server shows **Online** in the web UI and its status changes
from *Pending agent* to *Active*.

## 4. Choose services to watch (optional)

On the server's **Agent** tab, list systemd units (one per line, e.g. `nginx.service`) and the
report interval (10–60 s). The agent picks up the change with its next report. It only **reads**
service state; it cannot start, stop or restart anything.

## Test without installing

```bash
java -jar netmon-agent.jar collect-once --config /dev/null
```

prints one report as JSON (two samples, 2 s apart, so rates are filled) and sends nothing.

## Everyday operations

| Task | How |
|---|---|
| Re-enroll (new machine, lost credential) | Create a new token in the UI, then `sudo -u netmon-agent java -jar /opt/netmon-agent/netmon-agent.jar enroll --config /etc/netmon-agent/agent.properties --token <TOKEN>` and `sudo systemctl restart netmon-agent` |
| Revoke | Agent tab → **Revoke agent**. The agent is rejected immediately and logs that it was revoked. |
| Credential rotation | Automatic every 30 days (`credentials.rotate-after-days`); the old secret works for 10 more minutes. |
| Platform unreachable | The agent keeps collecting on schedule and queues up to 240 reports (2 hours at 30 s); they are delivered oldest-first when the platform is back. Beyond that the oldest are dropped and the gap shows in the charts. |
| Uninstall | `sudo ./deploy/linux/uninstall-agent.sh`, then revoke in the UI |

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Enrollment failed (HTTP 401)` | Token wrong, expired or already used: create a new one |
| `platform.url must use https://` | Use HTTPS. Plain HTTP is accepted only for `localhost` or with `tls.allow-insecure-http=true` in an isolated lab |
| `PKIX path building failed` | The platform certificate is not trusted: pass `--ca ministry-ca.pem` (or set `tls.ca-certificate`) |
| `CLOCK_SKEW` in the journal | Server clock is wrong: `timedatectl set-ntp true` |
| Server shows **Degraded** | A collector reported a problem. The Agent tab lists which one and why (e.g. permission denied) |
| Services show **Unknown** | `systemctl show` failed, e.g. systemd is not running (containers) |
| `credentials.json is readable by other users` | `sudo chmod 600 /var/lib/netmon-agent/credentials.json` |
