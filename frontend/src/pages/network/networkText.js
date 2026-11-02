// Shared helpers for the IP inventory and traffic pages.

const PROTOCOLS = { 1: 'ICMP', 2: 'IGMP', 6: 'TCP', 17: 'UDP', 41: 'IPv6', 47: 'GRE', 50: 'ESP', 51: 'AH', 58: 'ICMPv6', 89: 'OSPF', 112: 'VRRP', 132: 'SCTP' };

/** IANA protocol number as a name (protocol names are the same in every language). */
export const protocolName = (n) => PROTOCOLS[n] || `IP ${n}`;

export const SEEN_STATUS = {
  SEEN_NOW: 'ONLINE', SEEN_RECENTLY: 'STALE', NOT_SEEN_RECENTLY: 'UNKNOWN', NEVER_SEEN: 'NOT_COLLECTED',
};
export const SEEN_VALUES = ['SEEN_NOW', 'SEEN_RECENTLY', 'NOT_SEEN_RECENTLY', 'NEVER_SEEN'];
export const SOURCES = ['AGENT_INTERFACE', 'NEIGHBOR', 'FLOW', 'SERVER_REGISTRY', 'DEVICE', 'MANUAL', 'IMPORT'];
export const ALLOCATIONS = ['UNKNOWN', 'ASSIGNED', 'RESERVED', 'DHCP', 'GATEWAY'];
export const DEVICE_TYPES = ['ROUTER', 'SWITCH', 'FIREWALL', 'LOAD_BALANCER', 'WIRELESS', 'SENSOR', 'OTHER'];

export const blankToNull = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, typeof v === 'string' && v.trim() === '' ? null : v]));
