# Ubuntu test environment

A complete test setup on one or two Ubuntu 22.04/24.04 machines: the platform plus an agent with
packet capture and firewall control. For production, follow [DEPLOYMENT.md](DEPLOYMENT.md).

## 1. Platform machine
```bash
sudo apt update && sudo apt install -y openjdk-21-jdk docker.io docker-compose-v2 git chrony
```
```bash
sudo usermod -aG docker $USER   # sign out and in again
```
```bash
git clone <repository> netmon && cd netmon && cp .env.example .env
```
In `.env` set `DB_PASSWORD`, `JWT_SECRET` (see README "Quick start") and, because this test uses
plain HTTP, `REFRESH_COOKIE_SECURE=false`. Then:
```bash
docker compose up -d --build && docker compose ps
```
```bash
docker compose logs backend | grep -A6 "FIRST-RUN SETUP"
```
Open `http://<platform>:8088` (set `HTTP_BIND=0.0.0.0` in `.env` if you browse from another machine)
and create the first administrator. For HTTPS, add the TLS proxy (DEPLOYMENT.md section 4).

## 2. Monitored server with the agent
Build the agent on the platform machine and copy it over:
```bash
cd agent && ./mvnw -q package && scp target/netmon-agent.jar ../deploy/linux/* user@server:/tmp/netmon/
```
On the server:
```bash
sudo apt install -y openjdk-21-jre-headless chrony
```
In the web UI: **Servers → New server**, then the **Agent** tab → **Create enrollment token**. Copy
the install command it shows. It looks like:
```bash
sudo /tmp/netmon/install-agent.sh --jar /tmp/netmon/netmon-agent.jar --url http://<platform>:8088 --token <TOKEN> --allow-insecure-http
```
(`--allow-insecure-http` only for this plain-HTTP lab; with TLS use `https://` and, for a private
CA, `--ca ca.pem`.)

The server should show **Online** within a minute.

## 3. Optional: packet capture and firewall control on that server
```bash
sudo /tmp/netmon/enable-capture.sh --interfaces eth0
```
```bash
sudo /tmp/netmon/enable-firewall.sh            # dry run only
```
Each script ends with a self-check run as the agent user. In the UI, the server's **Packet capture**
card and **Firewall integrations** show what was detected.

## 4. Verify
| Check | Where |
|---|---|
| Connectivity | Server page shows Online; *Agents* page lists the version |
| Health | Admin → Platform health is OK (cookie warning is expected on plain HTTP) |
| Metrics | CPU & memory page shows the server with live values |
| Capture | Captures → New capture on `eth0` for 30 s; open it in the packet analyzer |
| Firewall state | Firewall integrations: detected firewall, "Dry run"; request and approve a test rule to a documentation address (203.0.113.10) and see **Dry run (not applied)** |
| Alerts | Stop the agent (`sudo systemctl stop netmon-agent`); after 5 minutes "Agent not reporting" opens |

## 5. Tests on Ubuntu
```bash
cd backend && ./mvnw test       # needs Docker (Testcontainers) and tshark
```
```bash
cd agent && ./mvnw test
```
```bash
cd frontend && npm ci && npm test
```
