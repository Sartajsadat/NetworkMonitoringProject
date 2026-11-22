#!/usr/bin/env bash
# Enables (or disables) website blocking for an installed NetMon agent on Linux.
#
#   sudo ./enable-sites.sh             allow the agent to block websites by name (in /etc/hosts)
#   sudo ./enable-sites.sh --disable   turn it off and remove NetMon's section from /etc/hosts
#
# Installs the root-owned helper /usr/lib/netmon-agent/netmon-hosts, a sudoers entry that lets the
# agent account run only that helper, and the systemd drop-in netmon-agent.service.d/sites.conf;
# then sets sites.enabled in /etc/netmon-agent/agent.properties and restarts the agent.
set -euo pipefail

PROPS=/etc/netmon-agent/agent.properties
DROPIN_DIR=/etc/systemd/system/netmon-agent.service.d
HELPER=/usr/lib/netmon-agent/netmon-hosts
SUDOERS=/etc/sudoers.d/netmon-agent-sites
DISABLE=0
[[ "${1:-}" == "--disable" ]] && DISABLE=1

if [[ $EUID -ne 0 ]]; then echo "Run as root (sudo)." >&2; exit 1; fi
if [[ ! -f "$PROPS" ]]; then echo "$PROPS not found: install the agent first (install-agent.sh)." >&2; exit 1; fi

set_prop() { # key value: replace the line if present, else append
  local key="$1" value="$2"
  if grep -q "^${key}=" "$PROPS"; then sed -i "s|^${key}=.*|${key}=${value}|" "$PROPS"; else echo "${key}=${value}" >> "$PROPS"; fi
}

if [[ $DISABLE -eq 1 ]]; then
  [[ -x "$HELPER" ]] && "$HELPER"   # no arguments: removes NetMon's section
  set_prop sites.enabled false
  rm -f "$DROPIN_DIR/sites.conf" "$SUDOERS" "$HELPER"
  systemctl daemon-reload
  systemctl restart netmon-agent
  echo "Website blocking disabled; NetMon's entries were removed from /etc/hosts."
  exit 0
fi

command -v sudo >/dev/null 2>&1 || { echo "Install sudo first." >&2; exit 1; }
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
install -d -m 0755 "$(dirname "$HELPER")"
install -m 0755 -o root -g root "$SCRIPT_DIR/netmon-hosts" "$HELPER"
echo "netmon-agent ALL=(root) NOPASSWD: $HELPER" > "$SUDOERS.tmp"
chmod 0440 "$SUDOERS.tmp"
visudo -cf "$SUDOERS.tmp" >/dev/null && mv "$SUDOERS.tmp" "$SUDOERS"
install -d -m 0755 "$DROPIN_DIR"
install -m 0644 "$SCRIPT_DIR/../systemd/netmon-agent-sites.conf" "$DROPIN_DIR/sites.conf"
set_prop sites.enabled true
systemctl daemon-reload
systemctl restart netmon-agent
echo "Website blocking enabled. Blocked sites appear in NetMon under Internet control."
