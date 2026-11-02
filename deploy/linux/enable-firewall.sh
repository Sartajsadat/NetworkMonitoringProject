#!/usr/bin/env bash
# Enables (or disables) firewall control for an installed NetMon agent.
#
#   sudo ./enable-firewall.sh                       report + dry run only (nothing is ever changed)
#   sudo ./enable-firewall.sh --enforce             also allow approved rules to change the firewall
#   sudo ./enable-firewall.sh --protect 10.1.0.0/24,192.0.2.10   addresses that must never be blocked
#   sudo ./enable-firewall.sh --disable
#
# The firewall manager is detected (UFW if UFW is enabled, otherwise nftables in NetMon's own table
# "inet netmon"; firewalld is not supported). Enabling:
#   1. nftables: makes a private copy of nft at /usr/lib/netmon-agent/nft with the capability
#      cap_net_admin, executable only by the group "netmon-fw" (the agent's Java process gets nothing);
#      UFW: a sudoers entry that lets the agent account run /usr/sbin/ufw only;
#   2. installs the systemd drop-in netmon-agent.service.d/firewall.conf;
#   3. sets firewall.* in /etc/netmon-agent/agent.properties and restarts the agent.
#
# Recovery (removes every NetMon rule):  nft delete table inet netmon
#   UFW:     ufw status numbered, then  ufw delete <N>  for each rule commented netmon:<tag>
# Use --enforce only after the integration was tested in a lab and an administrator approved it.
set -euo pipefail

PROPS=/etc/netmon-agent/agent.properties
DROPIN_DIR=/etc/systemd/system/netmon-agent.service.d
PRIVATE_NFT=/usr/lib/netmon-agent/nft
SUDOERS=/etc/sudoers.d/netmon-agent
ENFORCE=false PROTECT="" DISABLE=0 BACKEND=auto
while [[ $# -gt 0 ]]; do
  case "$1" in
    --enforce) ENFORCE=true; shift ;;
    --protect) PROTECT="$2"; shift 2 ;;
    --backend) BACKEND="$2"; shift 2 ;;
    --disable) DISABLE=1; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then echo "Run as root (sudo)." >&2; exit 1; fi
if [[ ! -f "$PROPS" ]]; then echo "$PROPS not found: install the agent first (install-agent.sh)." >&2; exit 1; fi
if [[ ! "$PROTECT" =~ ^[0-9A-Fa-f:./,]*$ ]]; then echo "Invalid --protect value (comma separated addresses/CIDRs)" >&2; exit 1; fi
if [[ ! "$BACKEND" =~ ^(auto|nftables|ufw)$ ]]; then echo "--backend must be auto, nftables or ufw" >&2; exit 1; fi

set_prop() { # key value: replace the line if present, else append
  local key="$1" value="$2"
  if grep -q "^${key}=" "$PROPS"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$PROPS"
  else
    echo "${key}=${value}" >> "$PROPS"
  fi
}

if [[ $DISABLE -eq 1 ]]; then
  set_prop firewall.enabled false
  set_prop firewall.enforcement false
  rm -f "$DROPIN_DIR/firewall.conf" "$SUDOERS"
  systemctl daemon-reload
  systemctl restart netmon-agent
  echo "Firewall control disabled. Rules NetMon already applied are NOT removed automatically:"
  echo "  nftables: nft list table inet netmon   (remove all: nft delete table inet netmon)"
  echo "  UFW:      ufw status numbered          (delete the rules commented netmon:<tag>)"
  exit 0
fi

if [[ "$BACKEND" == auto ]]; then
  if grep -qiE '^\s*ENABLED\s*=\s*yes' /etc/ufw/ufw.conf 2>/dev/null; then BACKEND=ufw
  elif systemctl is-active --quiet firewalld 2>/dev/null; then
    echo "firewalld is running. NetMon does not manage firewalld yet; nothing was changed." >&2; exit 1
  else BACKEND=nftables; fi
fi
echo "Firewall manager: $BACKEND"

getent group netmon-fw >/dev/null || groupadd --system netmon-fw
install -d -m 0755 "$DROPIN_DIR"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
install -m 0644 "$SCRIPT_DIR/../systemd/netmon-agent-firewall.conf" "$DROPIN_DIR/firewall.conf"

if [[ "$BACKEND" == nftables ]]; then
  command -v nft >/dev/null 2>&1 || { echo "Install nftables first (apt-get install nftables / dnf install nftables)." >&2; exit 1; }
  install -d -m 0755 "$(dirname "$PRIVATE_NFT")"
  install -m 0750 -o root -g netmon-fw "$(command -v nft)" "$PRIVATE_NFT"
  setcap cap_net_admin=eip "$PRIVATE_NFT"
  echo "nft: $(stat -c '%A %U:%G' "$PRIVATE_NFT")  $(getcap "$PRIVATE_NFT")"
  set_prop firewall.nft-path "$PRIVATE_NFT"
  rm -f "$SUDOERS"
else
  [[ -x /usr/sbin/ufw ]] || { echo "/usr/sbin/ufw not found." >&2; exit 1; }
  echo "netmon-agent ALL=(root) NOPASSWD: /usr/sbin/ufw" > "$SUDOERS.tmp"
  chmod 0440 "$SUDOERS.tmp"
  visudo -cf "$SUDOERS.tmp" >/dev/null && mv "$SUDOERS.tmp" "$SUDOERS"
  # sudo and ufw need these inside the service sandbox; ufw writes its rule files under /etc/ufw.
  cat >> "$DROPIN_DIR/firewall.conf" <<'UFW'
# UFW through sudo (added by enable-firewall.sh --backend ufw)
CapabilityBoundingSet=CAP_SETUID CAP_SETGID CAP_AUDIT_WRITE CAP_DAC_OVERRIDE CAP_NET_RAW CAP_CHOWN CAP_FOWNER
ReadWritePaths=/etc/ufw /lib/ufw /run
UFW
fi

set_prop firewall.enabled true
set_prop firewall.enforcement "$ENFORCE"
set_prop firewall.backend "$BACKEND"
set_prop firewall.protected-addresses "$PROTECT"

systemctl daemon-reload
systemctl restart netmon-agent
sleep 3
systemd-run --quiet --wait --pipe --collect -p User=netmon-agent -p SupplementaryGroups=netmon-fw \
  -p NoNewPrivileges=no /usr/bin/java -jar /opt/netmon-agent/netmon-agent.jar firewall-check --config "$PROPS" \
  && echo "Firewall control enabled ($([[ $ENFORCE == true ]] && echo 'ENFORCEMENT ALLOWED' || echo 'dry run only'))." \
  || { echo "Firewall check failed: see the message above and docs/FIREWALL.md" >&2; exit 1; }
