// Shared helpers for the packet capture pages.

export const ACTIVE_STATES = ['PENDING', 'STARTING', 'RUNNING', 'STOPPING'];
export const ALL_STATES = ['PENDING', 'STARTING', 'RUNNING', 'STOPPING', 'COMPLETED', 'STOPPED', 'FAILED', 'UNSUPPORTED'];
export const isActive = (c) => c && ACTIVE_STATES.includes(c.status);
export const MB = 1024 * 1024;

/** Capture filter (BPF) presets: applied by dumpcap on the server, before anything is stored. */
export const CAPTURE_FILTER_PRESETS = [
  { key: 'all', filter: '' },
  { key: 'web', filter: 'tcp port 80 or tcp port 443' },
  { key: 'dns', filter: 'udp port 53 or tcp port 53' },
  { key: 'host', filter: 'host 10.0.0.10' },
  { key: 'subnet', filter: 'net 10.0.0.0/24' },
  { key: 'icmp', filter: 'icmp or icmp6' },
  { key: 'noSsh', filter: 'not tcp port 22' },
];

/** Display filter (Wireshark) presets: applied afterwards, over the stored packets. */
export const DISPLAY_FILTER_PRESETS = [
  { key: 'resets', filter: 'tcp.flags.reset == 1' },
  { key: 'retrans', filter: 'tcp.analysis.retransmission' },
  { key: 'syn', filter: 'tcp.flags.syn == 1 && tcp.flags.ack == 0' },
  { key: 'httpErrors', filter: 'http.response.code >= 400' },
  { key: 'dns', filter: 'dns' },
  { key: 'dnsErrors', filter: 'dns.flags.rcode != 0' },
  { key: 'tls', filter: 'tls.handshake.type == 1' },
  { key: 'icmp', filter: 'icmp || icmpv6' },
  { key: 'address', filter: 'ip.addr == 10.0.0.10' },
];

export const DURATION_OPTIONS = [30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 43200, 86400];

export const SNAPLEN_OPTIONS = [
  { value: 96, key: 'headers' },
  { value: 256, key: 'default' },
  { value: 1514, key: 'frame' },
  { value: 65535, key: 'full' },
];

/** Packet time with microseconds, always in Latin digits (technical value). */
export function packetTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n, w = 2) => String(n).padStart(w, '0');
  const micros = iso.match(/\.(\d+)/)?.[1]?.padEnd(6, '0').slice(0, 6) ?? '000000';
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${micros}`;
}

export function endpoint(addr, port) {
  if (!addr) return '—';
  if (port === null || port === undefined) return addr;
  return addr.includes(':') ? `[${addr}]:${port}` : `${addr}:${port}`;
}

/** Starts a browser download with a one-time ticket (no token in the URL, no file in memory). */
export function startDownload(url) {
  const a = document.createElement('a');
  a.href = url;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
}

/** "45 s" below a minute, otherwise the shared "x h y min" format. */
export function durationText(seconds, { t, formatDuration, formatNumber }) {
  if (seconds === null || seconds === undefined) return '—';
  return seconds < 60 ? t('time.seconds', { s: formatNumber(seconds) }) : formatDuration(seconds);
}
