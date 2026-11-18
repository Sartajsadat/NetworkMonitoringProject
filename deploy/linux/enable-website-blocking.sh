#!/usr/bin/env bash
# Turns website blocking on (or off) for an installed NetMon agent on a Linux server or PC.
#
#   sudo ./enable-website-blocking.sh             the device follows the "Website blocking" page
#   sudo ./enable-website-blocking.sh --disable   stop, and remove NetMon's lines from /etc/hosts
#
# The agent stays unprivileged: it writes the list to /var/lib/netmon-agent/blocked-sites.txt and the
# small root helper /usr/lib/netmon-agent/netmon-hosts-apply (run by netmon-hosts.path/.service) copies
# it into NetMon's own section of /etc/hosts. Nothing else in /etc/hosts is changed.
set -euo pipefail

PROPS=/etc/netmon-agent/agent.properties
HELPER=/usr/lib/netmon-agent/netmon-hosts-apply
REQUEST=/var/lib/netmon-agent/blocked-sites.txt
DISABLE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --disable) DISABLE=1; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
if [[ $EUID -ne 0 ]]; then echo "Run as root (sudo)." >&2; exit 1; fi
if [[ ! -f "$PROPS" ]]; then echo "$PROPS not found: install the agent first (install-agent.sh)." >&2; exit 1; fi
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

set_prop() { # key value: replace the line if present, else append
  if grep -q "^$1=" "$PROPS"; then sed -i "s|^$1=.*|$1=$2|" "$PROPS"; else echo "$1=$2" >> "$PROPS"; fi
}

if [[ $DISABLE -eq 1 ]]; then
  set_prop websites.enabled false
  systemctl disable --now netmon-hosts.path 2>/dev/null || true
  if [[ -x "$HELPER" ]]; then NETMON_HOSTS_REQUEST=/dev/null "$HELPER"; fi   # empty list: removes NetMon's section
  rm -f /etc/systemd/system/netmon-hosts.path /etc/systemd/system/netmon-hosts.service "$REQUEST"
  systemctl daemon-reload
  systemctl restart netmon-agent
  echo "Website blocking disabled; NetMon's lines were removed from /etc/hosts."
  exit 0
fi

install -d -m 0755 "$(dirname "$HELPER")"
install -m 0755 -o root -g root "$SCRIPT_DIR/netmon-hosts-apply" "$HELPER"
install -m 0644 "$SCRIPT_DIR/../systemd/netmon-hosts.path" /etc/systemd/system/netmon-hosts.path
install -m 0644 "$SCRIPT_DIR/../systemd/netmon-hosts.service" /etc/systemd/system/netmon-hosts.service
set_prop websites.enabled true
set_prop websites.mode helper
systemctl daemon-reload
systemctl enable --now netmon-hosts.path
systemctl restart netmon-agent
echo "Website blocking enabled. This device now follows the Website blocking page (changes apply within about a minute)."
echo "Check: grep -A3 'BEGIN NetMon' /etc/hosts      Undo: sudo $0 --disable"
