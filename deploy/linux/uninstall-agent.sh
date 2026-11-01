#!/usr/bin/env bash
# Stops and removes the NetMon agent. Revoke the agent in the web UI as well.
set -euo pipefail
if [[ $EUID -ne 0 ]]; then echo "Run as root (sudo)." >&2; exit 1; fi
systemctl disable --now netmon-agent 2>/dev/null || true
rm -f /etc/systemd/system/netmon-agent.service
systemctl daemon-reload
rm -rf /opt/netmon-agent /var/lib/netmon-agent /etc/netmon-agent
userdel netmon-agent 2>/dev/null || true
echo "Agent removed. Also revoke it in the web UI (server page > Agent > Revoke)."
