# Diagrams

The diagrams use Mermaid; GitHub and most Markdown viewers draw them.

## 1. Architecture

```mermaid
flowchart LR
  subgraph Users["Administrators (browser)"]
    B[Browser: React UI<br/>English · پښتو · دری]
  end
  subgraph Platform["Central platform (Docker Compose)"]
    T[TLS proxy :443<br/>deploy/tls] --> F[frontend: nginx<br/>static UI + /api proxy]
    F --> A[backend: Spring Boot<br/>REST /api/v1, jobs, alert engine,<br/>flow collector, TShark analysis]
    A --> D[(PostgreSQL 16<br/>Flyway migrations)]
    A --> C[(capture volume<br/>PCAPNG files)]
  end
  subgraph Servers["Monitored servers"]
    L[Linux agent<br/>/proc, systemd, dumpcap,<br/>nftables / UFW]
    W[Windows agent<br/>CIM/PowerShell, dumpcap,<br/>Windows Defender Firewall]
  end
  subgraph Network["Network equipment"]
    R[Routers / switches / firewalls<br/>NetFlow v5/v9, IPFIX]
  end
  B -- HTTPS --> T
  L -- "HTTPS (outbound only)<br/>reports, tasks, uploads" --> T
  W -- "HTTPS (outbound only)" --> T
  R -- "UDP 2055 / 4739<br/>registered exporters only" --> A
```

## 2. Network communication and ports

```mermaid
sequenceDiagram
  participant Agent as Agent (server)
  participant TLS as TLS proxy :443
  participant API as Backend
  participant DB as PostgreSQL
  Agent->>TLS: POST /api/v1/agent/enroll (one-time token)
  TLS->>API: forward (X-Forwarded-For)
  API->>DB: store agent, hashed secret
  API-->>Agent: agentId + secret (saved 0600 on the server)
  loop every 30 s
    Agent->>TLS: POST /api/v1/agent/report (Agent credential)
    API->>DB: snapshot, metrics, events, drift check
    API-->>Agent: configuration
  end
  loop every few seconds (only if capture or firewall enabled locally)
    Agent->>TLS: POST /api/v1/agent/tasks
    API-->>Agent: typed tasks (capture / firewall rule), never commands
    Agent->>TLS: status, capture pieces
  end
```

| From | To | Port | Purpose |
|---|---|---|---|
| Administrators' browsers | TLS proxy | TCP 443 | Web UI and API |
| Agents | TLS proxy | TCP 443 | Enrolment, reports, tasks, capture upload (outbound from servers) |
| Routers, switches, firewalls | Platform host | UDP 2055 (NetFlow), UDP 4739 (IPFIX) | Flow records, only if the collector is enabled; bind to the management network |
| TLS proxy | frontend container | TCP 8088 on 127.0.0.1 | Internal |
| Platform | — | — | The platform never connects to the servers; no inbound port is opened on monitored servers |

## 3. Database (main tables)

```mermaid
erDiagram
  users ||--o{ user_roles : has
  roles ||--o{ user_roles : grants
  servers ||--o| agents : "monitored by"
  servers ||--o{ server_group_members : in
  server_groups ||--o{ server_group_members : contains
  servers ||--o{ server_ip_addresses : registered
  servers ||--o| server_snapshots : "latest report"
  servers ||--o{ metric_samples : "raw (7 d)"
  servers ||--o{ metric_rollups_5m : "5-min (90 d)"
  servers ||--o{ packet_captures : captures
  packet_captures ||--o{ capture_segments : "PCAPNG pieces"
  packet_captures ||--o{ packet_summaries : "packet metadata"
  servers ||--o{ agent_tasks : "typed tasks"
  alert_rules ||--o{ alerts : raises
  servers ||--o{ events : timeline
  alerts ||--o{ events : linked
  subnets ||--o{ ip_addresses : contains
  network_devices ||--o{ network_flows : exports
  servers ||--o| firewall_integrations : "host firewall"
  firewall_integrations ||--o{ firewall_rules : enforces
  firewall_rules ||--o{ access_policy_approvals : decisions
  servers ||--o{ application_inventory : runs
  application_inventory ||--o{ firewall_rules : "application policies"
  firewall_rules ||--o{ agent_tasks : "apply / remove"
  audit_logs }o--|| users : "actor (by name)"
  retention_policies }o--o{ audit_logs : "purges (≥ 1 year)"
```

Not shown: `refresh_tokens`, `agent_enrollment_tokens`, `server_agent_configs`, `system_settings`.
Columns and constraints are in `backend/src/main/resources/db/migration/V1…V8`.
