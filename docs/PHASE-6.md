# Phase 6 — IP inventory and traffic analysis

**Goal:** manage subnets and IP addresses, receive flow telemetry, show top IPs, ports, protocols
and conversations, and document host-only versus network-wide visibility.

**Result:** working end to end. Tests:

| Part | Tests |
|---|---|
| Backend | 120 (NetFlow/IPFIX parser, real softflowd packets, inventory, flows) |
| Agent | 42 (neighbour tables on Linux and Windows) |
| Frontend | 73 |

A real run on this machine checked three things:
- **Live NetFlow v9:** `softflowd` on `eth0` exported real traffic, which the collector accepted
  from the registered exporter and stored.
- **Live IPFIX:** a replay of the Phase 4 capture as IPFIX gave 226 flows with exactly **996
  packets**, the same number dumpcap counted when it captured them.
- **IP inventory:** it filled itself from the agent (own addresses and MACs), its neighbour table
  (the gateway and a Docker container), flows inside registered subnets, the server registry and a
  device management address.

A read-only user saw the inventory but no traffic data.

How the levels differ (host capture, mirror port/TAP, flow records), plus setup examples for
switches, routers and firewalls, is in **[NETWORK-VISIBILITY.md](NETWORK-VISIBILITY.md)**.

## 1. What was built

### Subnets (`/subnets`)
- IPv4 and IPv6 networks in CIDR notation, strictly validated. "10.10.1.5/24" is refused with the
  hint "the network is 10.10.1.0/24".
- Each subnet records its name, VLAN, gateway (must be inside the subnet), location and
  organization.
- **Nesting:** subnets may be nested (a site /16 containing VLAN /24s). Each address belongs to the
  most specific one, and every address is reassigned when subnets change.
- **Usage:** shows known addresses out of the usable ones (IPv4) and how many were seen in 24 hours.
  This is not a scan: unknown addresses are not counted as free.

### IP addresses (`/ip-addresses`)
- **Passive sources** (see the table in NETWORK-VISIBILITY.md):
  - server registry;
  - agent interfaces;
  - agent neighbour tables (new in the agent, version 0.5.0);
  - device management addresses;
  - flows inside registered subnets (internet addresses are not added);
  - manual entry and CSV import (for example a DHCP or DNS export).
- **Ignored:** loopback, link-local, multicast and broadcast addresses.
- **Each address shows:**
  - subnet, MAC, host name, and the server or device it belongs to;
  - allocation;
  - the sources it was learned from;
  - first and last observed time, and observation status: *active now*, *seen this week*, *not
    seen recently*, *never observed*. The page states that "not seen" never means "free".
- **Address details:**
  - related alerts;
  - flow totals and top peers for 24 hours;
  - packet captures containing the address;
  - links to the timeline and traffic for that address.
- **MAC change warning:** a known address answering from a different MAC creates an "Address
  changed MAC" warning on the timeline (a replaced device, or ARP spoofing).
- **Write throttling:** each address is written at most every 5 minutes per source, unless its MAC
  changes.

### Network devices (`/network-devices`)
- Routers, switches, firewalls, load balancers, wireless equipment and capture sensors.
- A device with *flow export* has an exporter address (unique). Its page shows:
  - flows received and dropped;
  - NetFlow version or IPFIX;
  - sampling;
  - last flow time.

### Flow collector
- **Formats:** NetFlow v5, NetFlow v9 (RFC 3954) and IPFIX (RFC 7011). It handles templates per
  exporter, options data (sampling, system start time), IPv6, variable-length fields and vendor
  fields.
- **Off by default.** It accepts records **only from registered exporters**; others are dropped and
  counted.
- **Limits:**
  - each exporter is limited to `FLOW_MAX_PER_SECOND`;
  - a bounded queue and batched writes;
  - malformed packets are rejected after length checks.
- **Retention:** 14 days (`FLOWS_RETENTION`).
- **Firewalls** that export NetFlow/IPFIX (FortiGate, Palo Alto, Cisco ASA, pfSense) work through
  the same collector.
- **Firewall logs** in vendor formats wait for the ministry's equipment list (see section 3).

### Traffic and conversations (`/traffic`)
- **Filters:** time range, address, subnet, port, protocol, exporter.
- **Display:**
  - totals, and bytes over time;
  - top sources and destinations;
  - top services (protocol and port);
  - protocols;
  - conversations with bytes in each direction;
  - the latest flow records.
- **Drill-down:** click an address, port or protocol to filter by it.
- **Labels:** the page always says **"Flow records, not packets"**, and warns when exporters sample
  packets.
- **Access:** network admins, security analysts and super admins (the same as packet captures).

## 2. Problems found with a real exporter (fixed)

The parser was tested against packets recorded from `softflowd` 1.1.0. They are now test fixtures,
and all three formats must decode to the same 226 flows.

1. **NetFlow v5/v9 flow times were 49.7 days off.**
   - **Why:** these formats give times relative to the exporter's 32-bit uptime counter, and the
     arithmetic did not handle wraparound. The same thing happens on any router running longer than
     49.7 days.
   - **Fix:** wrap-safe 32-bit arithmetic.
2. **IPFIX flows were dated at the export time.**
   - **Why:** softflowd sends flow times relative to a "system start" time carried in a separate
     options record.
   - **Fix:** the parser now reads that record. It also reads the sampling interval in the
     "interval/space" form.
3. **Server registry addresses appeared late.** IPs registered on a server only appeared after the
   5-minute sync; they now appear as soon as the server is saved.

## 3. Limits and next steps
- **Firewall logs** (syslog) need vendor-specific parsers. Please send the ministry's firewall
  models and versions and they will be added.
- **SNMP polling** of switches (interface tables, MAC address tables) is not built yet. It needs
  read-only SNMPv3 credentials for each device.
- **IPv6 neighbours on Linux** are not collected yet (`/proc/net/arp` is IPv4 only); Windows
  collects both.
- **Sampled flows** are shown as reported, not scaled up.
- **Large networks:** flows are stored raw for 14 days. For very high volumes, pre-aggregated
  storage is a Phase 9 item.
