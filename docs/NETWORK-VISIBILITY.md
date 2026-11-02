# What the platform can see on the network

The platform only knows about traffic that something actually observes. There are three levels.
Each one shows a different part of the network, and none of them replaces the others.

| Level | Source in this platform | What it sees | What it does **not** see |
|---|---|---|---|
| **A — host** | Agent on a server: packet capture on its own card, its interface addresses and its neighbour (ARP) table | Traffic to and from **that server**; the server's own addresses and MACs; neighbours on its local network that talked to it recently | Traffic between other devices. An ordinary server card does not receive other machines' unicast traffic, even on the same switch |
| **B — copied packets** | A **capture sensor** server connected to a switch **SPAN / mirror port** or a **network TAP**, running the agent with capture enabled on that card | Full packets of the mirrored ports or VLANs, or of the tapped link | Anything not mirrored. Busy links may overload a SPAN port (drops) |
| **C — flow records** | **NetFlow v5/v9 or IPFIX** from routers, layer-3 switches and firewalls registered under *Network devices* | Who talked to whom, on which ports, how many bytes and packets, and when — for all traffic through that device | Packet contents; exact timing inside a flow; anything the exporter samples away (1:N sampling) |

The pages say which kind of data they show:
- **Packet analyzer and Captures:** level A or B packets.
- **Traffic and conversations:** level C flow records, labelled "Flow records, not packets".

A flow record is a summary written by the exporter and cannot be opened like a packet capture.

## Level B: setting up a capture sensor

1. Use a dedicated server with two network cards.
   - One card for management, which reaches the platform.
   - One card for capture, connected to the mirror port. It needs no IP address.
2. Ask the network team to mirror the wanted ports or VLAN to that switch port. For example:
   - Cisco IOS:
     `monitor session 1 source vlan 10 both` and
     `monitor session 1 destination interface Gi1/0/48`
   - Juniper:
     `set forwarding-options analyzer SENSOR input ingress vlan SERVERS` and
     `set forwarding-options analyzer SENSOR output interface ge-0/0/47`

   Keep the mirrored volume below the sensor card's speed.
3. Install the agent and enable capture on the capture card only:
   ```bash
   sudo deploy/linux/enable-capture.sh --interfaces ens224
   ```
4. Register the sensor as a server, and optionally as a network device of type *Capture sensor*.

Mirroring copies traffic of other systems. It must be approved, like any capture.

## Level C: sending flow records to the platform

### 1. Turn the collector on
In `.env`:
```
FLOW_COLLECTOR_ENABLED=true
FLOW_BIND=<this host's management-network address>
```
Then run `docker compose up -d`.

The collector listens on **UDP 2055** (NetFlow v5/v9) and **UDP 4739** (IPFIX). Allow these ports
only from the exporters, in the host firewall and any firewall in between.

### 2. Register each exporter
Go to **Network devices → New device**, tick *sends NetFlow / IPFIX*, and give the address its flow
packets come from (usually the management or loopback address).

Records from any other sender are dropped and counted. UDP source addresses can be forged, so keep
flow export on the management network.

### 3. Configure the device
Examples to adapt with your network team:

- **Cisco IOS / IOS-XE (Flexible NetFlow):**
  ```
  flow exporter NETMON
   destination <platform address>
   source Loopback0
   transport udp 2055
   export-protocol netflow-v9
  flow monitor NETMON-MON
   exporter NETMON
   record netflow ipv4 original-input
  interface Gi0/1
   ip flow monitor NETMON-MON input
  ```
- **Juniper (inline jflow):** a `services flow-monitoring version-ipfix template` and
  `forwarding-options sampling` towards the platform on port 4739.
- **FortiGate:** `config system netflow`, then set `collector-ip <platform address>` and
  `collector-port 2055`, and enable `netflow-sampler` on the interfaces.
- **MikroTik RouterOS:**
  `/ip traffic-flow set enabled=yes` and
  `/ip traffic-flow target add dst-address=<platform address> port=2055 version=9`
- **pfSense / OPNsense:** the *softflowd* package, or *NetFlow* under *Reporting*, sending
  version 9 to port 2055.
- **Any Linux router or gateway:**
  ```bash
  softflowd -i eth0 -n <platform address>:2055 -v 9
  ```
  This is what the platform was tested with; use `-v 10` for IPFIX.

**Sampling:** if the exporter samples packets (1:N), the platform shows the exporter's reported
values and says that sampling is on. It does not multiply them into estimates.

## Firewall telemetry
- **Firewalls that export flows:** FortiGate, Palo Alto (NetFlow), Cisco ASA (NSEL / NetFlow v9)
  and pfSense can send NetFlow or IPFIX. Register them as network devices like any exporter.
- **Firewall logs** (syslog in vendor formats): these depend on the exact equipment and need the
  ministry's device list before an integration is built. They will be added for the models actually
  in use.

## IP inventory: how addresses are learned
Addresses are learned only from things the platform already receives. **Nothing scans the network.**

| Source | Meaning |
|---|---|
| Server registry | An IP entered on a server's record. It says who owns the address, not that it was seen |
| Agent interface | An address configured on a monitored server, with its MAC |
| Neighbour table | The server's ARP table: a device on the same network answered recently, with its MAC |
| Flow records | The address sent or received traffic through an exporter. Only addresses inside **registered subnets** are added |
| Network device | A device's management address |
| Manual / import | Entered by hand, or imported from a DHCP or DNS export (CSV) |

What the observation status means:
- **"Active now"** means the address was observed in use in the last 15 minutes.
- **"Never observed"** and **"not seen recently"** do **not** mean an address is free. Many devices
  block ping, and a quiet device may simply not have been seen by an agent, a neighbour table or an
  exporter. Check before reusing an address.

If a known address starts answering from a **different MAC**, the timeline gets an "Address changed
MAC" warning. This may be a replaced device, or ARP spoofing.
