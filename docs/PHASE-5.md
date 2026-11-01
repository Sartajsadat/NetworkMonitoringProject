# Phase 5 — Historical events and alerts

**Goal:** record what happens on the servers and on the platform as events, show them on one
timeline, raise alerts from configurable thresholds, let people acknowledge and resolve them, and
link every event to the metrics, captures and servers around it.

**Result:** working end to end. Tests:

| Part | Tests |
|---|---|
| Backend | 105 (8 new event/alert scenarios) |
| Frontend | 68 (8 new) |
| Agent | 40 (no agent change was needed) |

A real run on this machine did the following:
- **CPU load:** real load on all CPUs opened a CPU alert after 86 s ("averaged 83.9% over 2 min").
  The alert resolved by itself about a minute after the load stopped, with a peak of 99.995%.
- **Agent stopped:**
  - "Agent stopped reporting" was dated at its last report.
  - The CRITICAL "Agent not reporting" alert opened.
  - After restarting the agent, "Agent reporting again" appeared, linked to the disconnection by
    one incident id, and the alert resolved.
  - The disconnection's detail page listed the exact missing period.
- **Failed sign-ins:** 14 failed sign-ins produced the security events, the account lockout and the
  "Repeated failed sign-ins" alert. Security analysts see these; the infrastructure admin and
  read-only users do not.
- **A real problem:** the run also found one. This machine's `/opt/claude-code` disk is 92.3% full,
  and the default "Disk almost full" rule raised it.

## 1. What creates events

| Source | Events |
|---|---|
| Agent reports (compared with the previous report) | Service state changed (dated at the service's own change time), service restarted between two reports, server rebooted (new boot time), collector problem / recovered |
| Connection monitor (every minute) | Agent stopped reporting (dated at its last report), agent reporting again; both share one incident id |
| Alert engine | One event when an alert opens (type = rule type), plus acknowledged and resolved events |
| Packet captures | Requested, started, finished (packet count), failed, cancelled, stop requested, files deleted; linked by the capture id |
| Audit trail (security) | Failed sign-in, account locked, sign-in rate limit, session token reused, failed setup attempt, agent enrolled/revoked |

Each event has:
- time and recording time;
- category, type and severity;
- source (the agent or the platform);
- server (its name is kept even if the server is deleted later);
- resource (service, mount point, interface…) and IP address;
- a summary and structured details;
- an incident (correlation) id, and links to an alert or capture.

Events are written only after the work they describe is committed, so the timeline never shows
something that was rolled back.

## 2. Alert rules

Eleven default rules are created (all editable); the throughput rules are off by default.

| Rule | Default |
|---|---|
| High CPU usage | average ≥ 90% over 5 min |
| High memory usage | average ≥ 95% over 5 min |
| Disk almost full | ≥ 90% (each mount or drive) |
| Agent not reporting | no report for 5 min |
| Monitored service not running | stopped, failed or missing for 1 min |
| Agent collector problems | error or missing permission for 5 min |
| Interface errors or drops | ≥ 10 per second over 5 min |
| Repeated failed sign-ins | ≥ 10 from one address in 15 min |
| Capture storage almost full | ≥ 80% of the quota |
| High network throughput | ≥ 800 Mb/s (off by default) |
| Unusual traffic increase | 5× the previous 24-hour average (off by default) |

Swap usage is also available as a rule type.

### How evaluation works
Rules are evaluated every minute (`ALERT_EVALUATION_INTERVAL`).
- **Window:** metric rules use the average over the window, and need at least half of the expected
  samples. State rules (service, collector) must hold for the whole window.
- **Deduplication:** while an alert for a rule and resource is open or acknowledged, it is updated
  (latest value, peak value, how many times the condition was seen) instead of creating a new
  alert. A database index guarantees this.
- **Cooldown:** after an alert is resolved, the same resource does not open a new alert until the
  cooldown has passed.
- **Missing data never opens or resolves anything.** If a server sends no data, its alerts stay as
  they are, and the "Agent not reporting" rule covers the silence.
- **Changing, disabling or deleting a rule** closes its active alerts. They reopen at once if the
  condition still holds.

### Acknowledging and resolving
- **Acknowledge** (optional comment) and **resolve by hand** (a note is required): super,
  infrastructure and network administrators, and security analysts. Read-only users only view.
- **Rules:** changed only by super and infrastructure administrators.
- **Audit:** all of these actions are in the audit log and on the timeline.

### Who sees what
- Security events and security alerts (sign-ins, source addresses): super admin and security
  analyst only.
- Capture events: the roles that may see packet captures.
- A name typed into the sign-in form is shown only if it belongs to a real account, so a password
  typed into the wrong field never appears.

## 3. Timeline and investigation (web UI, three languages)

- **Event timeline** (`/timeline`):
  - **Filters:** time range, server, IP address, category, minimum severity, service, alert status,
    text and incident. They are kept in the address, so the dashboard, servers, alerts and events
    can link straight to a prepared view.
  - **Display:** an events-over-time chart by severity, and a list grouped by day (newest or oldest
    first).
- **Event detail:**
  - what happened, with its recorded details;
  - everything else on the same server within 15 minutes;
  - all events of the same incident;
  - CPU and memory for ±30 minutes;
  - the packet captures that ran then.
- **Missing data is stated, not hidden.** The page says how much metric data exists for the period
  and lists each missing interval ("Missing: 17:57:37 to 18:01:39 (4 min)"). With no data at all, it
  says that nothing can be concluded and does not draw an empty chart.
- **Alerts** (`/alerts`):
  - counts by status and severity;
  - active / resolved / all lists;
  - acknowledge and resolve dialogs;
  - alert detail with the condition, peak value, chart, data coverage and the timeline around it.
- **Alert rules** (`/alerts/rules`): thresholds in friendly units (%, Mb/s, × average, per second,
  attempts), window, cooldown, severity, one server or all, and an optional mount, interface,
  service, collector or address.
- **Elsewhere:**
  - an alert bell in the top bar;
  - an "Active alerts" tile and "Recent warnings and errors" on the dashboard;
  - Timeline and Alerts buttons on each server page.
- **Texts:** 243 new texts in each language. Pashto uses "خبرتیا / پېښه", Dari uses
  "هشدار / رویداد". Times, addresses and technical values stay left-to-right.

## 4. Limits and next steps

- **Retention and flood protection:** events are kept 180 days (`EVENTS_RETENTION`) and resolved
  alerts 365 days. At most 600 events per server per hour are kept; the overflow is recorded once.
- **History before Phase 5:** events start with Phase 5, so earlier periods have no events.
- **Service events depend on systemd:** on servers without systemd (some containers), services show
  *Unknown* and produce no events. This build machine is one of them, so service events were
  verified with recorded reports in the tests instead.
- **Notifications:** no email or other notification channel yet. The brief makes it optional; it can
  be added as an integration.
- **Not built yet (shown as "Phase 5b" in the menu):**
  - **Centralized logs:** collecting journald / Windows Event Log through the agent, a log viewer,
    redaction and export.
  - **Hosted-application inventory:** applications, owners, health checks.

  They come from sections 13 and 15 of the brief, which the build order does not place in Phase 5.
- **Firewall-change events and rules** arrive with Phase 7.
