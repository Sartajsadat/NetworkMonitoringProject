#!/usr/bin/env bash
# Enables (or disables) packet capture for an installed NetMon agent.
#
#   sudo ./enable-capture.sh [--interfaces eth0,ens192] [--max-minutes 60] [--max-megabytes 1024]
#   sudo ./enable-capture.sh --disable
#
# Capture is off by default. Enabling it:
#   1. installs dumpcap (package "tshark"/"wireshark-cli") if missing,
#   2. lets only members of the "wireshark" group run dumpcap, with the capabilities
#      cap_net_raw,cap_net_admin on the dumpcap file (the agent itself stays unprivileged),
#   3. installs the systemd drop-in netmon-agent.service.d/capture.conf,
#   4. sets capture.* in /etc/netmon-agent/agent.properties and restarts the agent.
# Only capture on servers and networks you are authorised to monitor.
set -euo pipefail

PROPS=/etc/netmon-agent/agent.properties
DROPIN_DIR=/etc/systemd/system/netmon-agent.service.d
IFACES="" MAX_MIN=60 MAX_MB=1024 DISABLE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --interfaces) IFACES="$2"; shift 2 ;;
    --max-minutes) MAX_MIN="$2"; shift 2 ;;
    --max-megabytes) MAX_MB="$2"; shift 2 ;;
    --disable) DISABLE=1; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then echo "Run as root (sudo)." >&2; exit 1; fi
if [[ ! -f "$PROPS" ]]; then echo "$PROPS not found: install the agent first (install-agent.sh)." >&2; exit 1; fi
if [[ ! "$IFACES" =~ ^[A-Za-z0-9._:@,-]*$ ]]; then echo "Invalid --interfaces value" >&2; exit 1; fi
if [[ ! "$MAX_MIN" =~ ^[0-9]+$ || "$MAX_MIN" -lt 1 || "$MAX_MIN" -gt 1440 ]]; then echo "--max-minutes must be 1..1440" >&2; exit 1; fi
if [[ ! "$MAX_MB" =~ ^[0-9]+$ || "$MAX_MB" -lt 1 || "$MAX_MB" -gt 102400 ]]; then echo "--max-megabytes must be 1..102400" >&2; exit 1; fi

set_prop() { # key value: replace the line if present, else append
  local key="$1" value="$2"
  if grep -q "^${key}=" "$PROPS"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$PROPS"
  else
    echo "${key}=${value}" >> "$PROPS"
  fi
}

if [[ $DISABLE -eq 1 ]]; then
  set_prop capture.enabled false
  rm -f "$DROPIN_DIR/capture.conf"
  systemctl daemon-reload
  systemctl restart netmon-agent
  echo "Packet capture disabled. (dumpcap and the wireshark group were left in place.)"
  exit 0
fi

# 1. dumpcap
if [[ ! -x /usr/bin/dumpcap ]]; then
  if command -v apt-get >/dev/null 2>&1; then
    echo "wireshark-common wireshark-common/install-setuid boolean true" | debconf-set-selections
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends tshark
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y wireshark-cli
  else
    echo "Install dumpcap (Wireshark command line tools) first." >&2; exit 1
  fi
fi

# 2. Only the wireshark group may run dumpcap; dumpcap gets the two capture capabilities.
if command -v dpkg-reconfigure >/dev/null 2>&1 && dpkg -s wireshark-common >/dev/null 2>&1; then
  echo "wireshark-common wireshark-common/install-setuid boolean true" | debconf-set-selections
  DEBIAN_FRONTEND=noninteractive dpkg-reconfigure -f noninteractive wireshark-common
fi
getent group wireshark >/dev/null || groupadd --system wireshark
if ! getcap /usr/bin/dumpcap 2>/dev/null | grep -q cap_net_raw; then
  chgrp wireshark /usr/bin/dumpcap
  chmod 0750 /usr/bin/dumpcap
  setcap cap_net_raw,cap_net_admin=eip /usr/bin/dumpcap
fi
echo "dumpcap: $(stat -c '%A %U:%G' /usr/bin/dumpcap)  $(getcap /usr/bin/dumpcap)"

# 3. systemd drop-in
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
install -d -m 0755 "$DROPIN_DIR"
install -m 0644 "$SCRIPT_DIR/../systemd/netmon-agent-capture.conf" "$DROPIN_DIR/capture.conf"

# 4. agent settings
set_prop capture.enabled true
set_prop capture.interfaces "$IFACES"
set_prop capture.max-duration-seconds $((MAX_MIN * 60))
set_prop capture.max-megabytes "$MAX_MB"
set_prop capture.dumpcap-path /usr/bin/dumpcap

systemctl daemon-reload
systemctl restart netmon-agent
sleep 3
# Check as the agent user, inside the same restrictions as the service.
systemd-run --quiet --wait --pipe --collect -p User=netmon-agent -p SupplementaryGroups=wireshark \
  -p NoNewPrivileges=no -p "CapabilityBoundingSet=CAP_NET_RAW CAP_NET_ADMIN" \
  /usr/bin/java -jar /opt/netmon-agent/netmon-agent.jar capture-check --config "$PROPS" \
  && echo "Packet capture enabled." \
  || { echo "Capture check failed: see the message above and docs/CAPTURE-TROUBLESHOOTING.md" >&2; exit 1; }
