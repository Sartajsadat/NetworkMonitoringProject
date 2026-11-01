#!/usr/bin/env bash
# Installs the NetMon Linux agent as a systemd service and enrolls it.
#
#   sudo ./install-agent.sh --jar netmon-agent.jar --url https://netmon.example.gov.af --token <ENROLLMENT_TOKEN> [--ca ministry-ca.pem]
#
# What it does: creates the system user "netmon-agent", copies the jar to /opt/netmon-agent,
# writes /etc/netmon-agent/agent.properties, enrolls with the one-time token, starts the service.
# It does not open any port and gives the agent no root privileges.
set -euo pipefail

JAR="" URL="" TOKEN="" CA=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --url) URL="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --ca) CA="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then echo "Run as root (sudo)." >&2; exit 1; fi
if [[ -z "$JAR" || -z "$URL" || -z "$TOKEN" ]]; then
  echo "Usage: sudo $0 --jar netmon-agent.jar --url https://netmon.example --token TOKEN [--ca ca.pem]" >&2; exit 2
fi
if [[ ! -f "$JAR" ]]; then echo "Jar not found: $JAR" >&2; exit 1; fi
if [[ ! "$URL" =~ ^https?://[A-Za-z0-9.:\[\]_-]+(/.*)?$ ]]; then echo "Invalid URL: $URL" >&2; exit 1; fi
if ! command -v java >/dev/null 2>&1; then
  echo "Java 21 is required: sudo apt install openjdk-21-jre-headless" >&2; exit 1
fi
JAVA_MAJOR=$(java -version 2>&1 | awk -F'[".]' '/version/ {print $2; exit}')
if [[ "${JAVA_MAJOR:-0}" -lt 21 ]]; then echo "Java 21 or newer is required (found $JAVA_MAJOR)." >&2; exit 1; fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

id netmon-agent >/dev/null 2>&1 || useradd --system --no-create-home --home-dir /var/lib/netmon-agent \
  --shell /usr/sbin/nologin netmon-agent

install -d -m 0755 /opt/netmon-agent /etc/netmon-agent
install -d -m 0700 -o netmon-agent -g netmon-agent /var/lib/netmon-agent
install -m 0644 "$JAR" /opt/netmon-agent/netmon-agent.jar

CA_LINE="tls.ca-certificate="
if [[ -n "$CA" ]]; then
  install -m 0644 "$CA" /etc/netmon-agent/ca.pem
  CA_LINE="tls.ca-certificate=/etc/netmon-agent/ca.pem"
fi

if [[ ! -f /etc/netmon-agent/agent.properties ]]; then
  cat > /etc/netmon-agent/agent.properties <<PROPS
platform.url=${URL}
${CA_LINE}
state.dir=/var/lib/netmon-agent
buffer.max-reports=240
credentials.rotate-after-days=30
systemctl.path=$(command -v systemctl || echo /usr/bin/systemctl)
PROPS
  chmod 0644 /etc/netmon-agent/agent.properties
fi

# Enroll as the agent user so the credential file is owned by it (mode 0600).
runuser -u netmon-agent -- java -jar /opt/netmon-agent/netmon-agent.jar enroll \
  --config /etc/netmon-agent/agent.properties --token "$TOKEN"

install -m 0644 "$SCRIPT_DIR/../systemd/netmon-agent.service" /etc/systemd/system/netmon-agent.service
systemctl daemon-reload
systemctl enable --now netmon-agent
sleep 3
systemctl --no-pager --lines=5 status netmon-agent || true
echo "Installed. Logs: journalctl -u netmon-agent -f"
