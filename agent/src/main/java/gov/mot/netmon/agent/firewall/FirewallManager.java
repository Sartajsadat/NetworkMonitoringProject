package gov.mot.netmon.agent.firewall;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.model.Report;
import gov.mot.netmon.agent.transport.PlatformClient;
import gov.mot.netmon.agent.transport.PlatformClient.Outcome;

/**
 * Applies, verifies and removes platform-approved firewall rules on this server.
 *
 * <ul>
 * <li>Nothing changes unless the server's administrator set {@code firewall.enabled} AND
 *     {@code firewall.enforcement}; otherwise every rule is only checked (dry run).
 * <li>Every rule is re-validated and checked against local safety before anything runs.
 * <li>"APPLIED" is reported only after the rule was read back from the firewall; if it cannot be
 *     found the result is UNVERIFIED.
 * <li>Expired rules are removed locally even if the platform is unreachable.
 * </ul>
 */
public class FirewallManager {

    private static final Logger log = Logger.getLogger(FirewallManager.class.getName());
    static final List<String> LOOPBACK = List.of("127.0.0.0/8", "::1/128");

    private final FirewallSettings settings;
    private final Supplier<FirewallBackend> detector;
    private final PlatformClient client;
    private final Supplier<String> authorization;
    private final Clock clock;
    private final List<String> platformAddresses;
    private final Supplier<List<String>> localSessions;
    private final FirewallState state;
    private final ConcurrentLinkedQueue<JsonNode> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Map<String, Object>> outbox = new ConcurrentLinkedQueue<>();
    private volatile boolean selfPoll;
    private volatile boolean running = true;
    private volatile FirewallBackend backend;
    private volatile Instant backendAt;
    private volatile Report.Firewall capability;

    public FirewallManager(FirewallSettings settings, Supplier<FirewallBackend> detector, PlatformClient client,
            Supplier<String> authorization, Clock clock, List<String> platformAddresses, Supplier<List<String>> localSessions) {
        this.settings = settings;
        this.detector = detector;
        this.client = client;
        this.authorization = authorization;
        this.clock = clock;
        this.platformAddresses = platformAddresses;
        this.localSessions = localSessions;
        this.state = new FirewallState(settings.stateFile());
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** True when no other component polls the platform for tasks (packet capture is off). */
    public void setSelfPoll(boolean v) {
        selfPoll = v;
    }

    public void stop() {
        running = false;
    }

    /** A firewall task delivered by the platform (possibly through the capture poller). */
    public void offer(JsonNode task) {
        queue.add(task);
    }

    /** Resolves the platform's host name once at start-up (the configured URL, not platform input). */
    public static List<String> resolve(String host) {
        List<String> out = new ArrayList<>();
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                out.add(Cidrs.format(a.getAddress()) + (a.getAddress().length == 4 ? "/32" : "/128"));
            }
        } catch (Exception e) {
            log.warning("Could not resolve the platform address " + host + ": " + e.getMessage());
        }
        return out;
    }

    List<String> protectedList() {
        Set<String> s = new LinkedHashSet<>(LOOPBACK);
        s.addAll(platformAddresses);
        s.addAll(settings.protectedAddresses());
        return new ArrayList<>(s);
    }

    List<String> sessions() {
        Set<String> s = new LinkedHashSet<>(localSessions.get());
        FirewallBackend b = backend;
        if (b != null) s.addAll(b.managementSessions());
        return new ArrayList<>(s);
    }

    FirewallBackend backend() {
        Instant now = clock.instant();
        if (backend == null || backendAt == null || now.isAfter(backendAt.plusSeconds(300))) {
            backend = detector.get();
            backendAt = now;
        }
        return backend;
    }

    static boolean real(FirewallBackend b) {
        return !(b instanceof FirewallBackend.None);
    }

    public Report.Firewall capability() {
        Report.Firewall c = capability;
        return c != null ? c : refresh();
    }

    /** Re-reads the NetMon rules present in the firewall (for the report and the platform's drift check). */
    synchronized Report.Firewall refresh() {
        Report.Firewall c;
        List<String> reported = new ArrayList<>(platformAddresses);
        reported.addAll(settings.protectedAddresses());
        if (reported.size() > 32) reported = reported.subList(0, 32);
        if (!settings.enabled()) {
            c = new Report.Firewall(false, false, null, "Firewall control is not enabled on this server (firewall.enabled)", reported, null, List.of());
        } else {
            FirewallBackend b = backend();
            List<String> tags = null;
            String message;
            if (b.problem() != null) {
                message = b.problem();
            } else {
                try {
                    tags = new ArrayList<>(b.tags());
                    tags.sort(null);
                    if (tags.size() > 500) tags = tags.subList(0, 500);
                    message = switch (b.name()) {
                        case "NFTABLES" -> "nftables: NetMon rules in table inet netmon";
                        case "UFW" -> "UFW: NetMon rules commented netmon:<tag>";
                        case "WINDOWS" -> "Windows Defender Firewall: NetMon rules in group NetMon"
                                + (b instanceof WindowsFirewallBackend w && !w.offProfiles().isEmpty()
                                        ? " (firewall OFF for profiles " + w.offProfiles() + ": rules are not enforced there)" : "");
                        default -> b.name();
                    };
                } catch (FirewallBackend.FirewallException e) {
                    message = e.getMessage();
                }
            }
            boolean enforce = settings.enforcement() && real(b) && b.problem() == null;
            message += enforce ? "; enforcement allowed" : "; dry run only" + (settings.enforcement() ? "" : " (firewall.enforcement=false)");
            c = new Report.Firewall(true, enforce, b.name(), message, reported, tags, b.problem() == null ? b.applicationControl() : List.of());
        }
        capability = c;
        return c;
    }

    public void runForever() {
        while (running) {
            try {
                tick();
                Thread.sleep(settings.pollSeconds() * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warning("Firewall loop error: " + e);
            }
        }
    }

    void tick() {
        if (selfPoll) pollTasks();
        JsonNode t;
        while ((t = queue.poll()) != null) handle(t);
        if (settings.enabled()) {
            expireLocally();
            refresh();
        }
        flush();
    }

    void pollTasks() {
        PlatformClient.Response res = client.post("/api/v1/agent/tasks", null, authorization.get());
        if (res.outcome() != Outcome.OK || res.body() == null) return;
        for (JsonNode t : res.body().path("tasks")) {
            String type = t.path("type").asText("");
            if (type.endsWith("_FIREWALL_RULE")) handle(t);
            else log.warning("Ignoring task " + type.substring(0, Math.min(40, type.length())) + ": packet capture is not enabled here");
        }
    }

    void handle(JsonNode task) {
        String type = task.path("type").asText("");
        JsonNode p = task.path("firewall");
        UUID id;
        try {
            id = UUID.fromString(p.path("ruleId").asText());
        } catch (IllegalArgumentException e) {
            log.warning("Ignoring a firewall task without a valid rule id");
            return;
        }
        if (!settings.enabled()) {
            send(id, "FAILED", "Firewall control is not enabled on this server (firewall.enabled=false)", null);
            return;
        }
        if ("REMOVE_FIREWALL_RULE".equals(type)) {
            String tag = p.path("tag").asText("");
            if (!FwRule.TAG.matcher(tag).matches()) {
                send(id, "FAILED", "Invalid rule tag", null);
                return;
            }
            remove(id, tag);
            return;
        }
        if (!"APPLY_FIREWALL_RULE".equals(type)) return;
        if ("APPLICATION".equals(p.path("kind").asText())) {
            AppPolicy a;
            try {
                a = AppPolicy.parse(p);
            } catch (RuntimeException e) {
                send(id, "FAILED", "Refused by the agent: " + e.getMessage(), null);
                return;
            }
            applyApp(a);
            return;
        }
        FwRule r;
        try {
            r = FwRule.parse(p);
        } catch (RuntimeException e) {
            send(id, "FAILED", "Refused by the agent: " + e.getMessage(), null);
            return;
        }
        apply(r);
    }

    void apply(FwRule r) {
        FirewallBackend b = backend();
        String cmd = b.describe(r);
        List<String> unsafe = LocalSafety.problems(r, protectedList(), sessions());
        if (!unsafe.isEmpty()) {
            log.warning("Refused firewall rule " + r.tag() + " (" + r.describe() + "): " + unsafe);
            send(r.ruleId(), "FAILED", "Refused by the agent: " + String.join("; ", unsafe), cmd);
            return;
        }
        boolean wantEnforce = "ENFORCE".equals(r.mode());
        boolean canEnforce = settings.enforcement() && real(b) && b.problem() == null;
        if (!wantEnforce || !canEnforce) {
            String why = !wantEnforce ? "Dry run requested by the platform"
                    : !settings.enforcement() ? "Enforcement is not allowed on this server (firewall.enforcement=false)"
                    : b.problem();
            if (real(b) && b.problem() == null) {
                try {
                    b.check(r);
                    send(r.ruleId(), "DRY_RUN", why + ". " + b.name() + " accepted the rule in check mode; nothing was changed.", cmd);
                } catch (FirewallBackend.FirewallException e) {
                    send(r.ruleId(), "FAILED", e.getMessage(), cmd);
                }
            } else {
                send(r.ruleId(), "DRY_RUN", why + ". Not checked: " + b.problem(), cmd);
            }
            return;
        }
        Instant now = clock.instant();
        if (r.expiresAt() != null && !r.expiresAt().isAfter(now)) {
            send(r.ruleId(), "FAILED", "The rule had already expired when it arrived", cmd);
            return;
        }
        try {
            if (!b.tags().contains(r.tag())) b.apply(r);
            if (b.tags().contains(r.tag())) {
                state.applied(r, now);
                log.info("Firewall rule " + r.tag() + " applied: " + r.describe());
                send(r.ruleId(), "APPLIED", "Verified: the rule is present in " + b.name(), cmd);
            } else {
                send(r.ruleId(), "UNVERIFIED", "The command succeeded but the rule was not found when the firewall was read back", cmd);
            }
        } catch (FirewallBackend.FirewallException e) {
            send(r.ruleId(), "FAILED", e.getMessage(), cmd);
        }
        refresh();
    }

    /** Same rules as for IP rules: local consent, safety, dry run or apply, then read back. */
    void applyApp(AppPolicy a) {
        FirewallBackend b = backend();
        String cmd = b.describeApp(a);
        String forbidden = a.forbidden(System.getProperty("user.name", ""));
        if (forbidden != null) {
            send(a.ruleId(), "FAILED", "Refused by the agent: restricting " + forbidden + " could cut off the server or the agent", cmd);
            return;
        }
        if (!b.applicationControl().contains(a.identityKind())) {
            send(a.ruleId(), "FAILED", "Not supported here: " + b.name() + " cannot match applications by " + a.identityKind()
                    + (b.problem() == null ? "" : " (" + b.problem() + ")"), cmd);
            return;
        }
        boolean wantEnforce = "ENFORCE".equals(a.mode());
        boolean canEnforce = settings.enforcement() && b.problem() == null;
        if (!wantEnforce || !canEnforce) {
            String why = !wantEnforce ? "Dry run requested by the platform" : "Enforcement is not allowed on this server (firewall.enforcement=false)";
            try {
                b.checkApp(a);
                send(a.ruleId(), "DRY_RUN", why + ". " + b.name() + " accepted the policy in check mode; nothing was changed.", cmd);
            } catch (FirewallBackend.FirewallException e) {
                send(a.ruleId(), "FAILED", e.getMessage(), cmd);
            }
            return;
        }
        Instant now = clock.instant();
        if (a.expiresAt() != null && !a.expiresAt().isAfter(now)) {
            send(a.ruleId(), "FAILED", "The policy had already expired when it arrived", cmd);
            return;
        }
        try {
            if (!b.tags().contains(a.tag())) b.applyApp(a);
            if (b.tags().contains(a.tag())) {
                state.applied(a.ruleId(), a.tag(), a.describe(), a.expiresAt(), now);
                log.info("Application policy " + a.tag() + " applied: " + a.describe());
                send(a.ruleId(), "APPLIED", "Verified: the policy is present in " + b.name(), cmd);
            } else {
                send(a.ruleId(), "UNVERIFIED", "The command succeeded but the policy was not found when the firewall was read back", cmd);
            }
        } catch (FirewallBackend.FirewallException e) {
            send(a.ruleId(), "FAILED", e.getMessage(), cmd);
        }
        refresh();
    }

    void remove(UUID id, String tag) {
        FirewallBackend b = backend();
        FirewallState.Entry e = state.find(tag);
        if (!real(b)) {
            send(id, "NOT_PRESENT", "No supported firewall on this server, so nothing was applied", null);
            return;
        }
        if (b.problem() != null) {
            send(id, "FAILED", b.problem(), null);
            return;
        }
        // Removing is always allowed (it restores the previous state), even when enforcement was switched off since.
        try {
            boolean found = b.remove(tag);
            if (b.tags().contains(tag)) {
                send(id, "FAILED", "The rule is still present after the delete command", null);
                return;
            }
            state.removed(tag, clock.instant());
            refresh();
            if (found) {
                log.info("Firewall rule " + tag + " removed");
                send(id, "REMOVED", "Removed from " + b.name() + " and verified absent", null);
            } else {
                send(id, "NOT_PRESENT", e != null && e.removedAt() != null
                        ? "Already removed by the agent at " + e.removedAt() + " (expiry)" : "The rule was not present", null);
            }
        } catch (FirewallBackend.FirewallException ex) {
            send(id, "FAILED", ex.getMessage(), null);
        }
    }

    /** Removes expired rules without waiting for the platform (it may be unreachable). */
    void expireLocally() {
        Instant now = clock.instant();
        FirewallBackend b = backend();
        if (!real(b) || b.problem() != null) return;
        for (FirewallState.Entry e : state.all()) {
            if (e.removedAt() != null || e.expiresAt() == null || e.expiresAt().isAfter(now)) continue;
            try {
                b.remove(e.tag());
                if (!b.tags().contains(e.tag())) {
                    state.removed(e.tag(), now);
                    log.info("Firewall rule " + e.tag() + " expired and was removed by the agent: " + e.description());
                }
            } catch (FirewallBackend.FirewallException ex) {
                log.warning("Could not remove expired firewall rule " + e.tag() + ": " + ex.getMessage());
            }
        }
    }

    void send(UUID id, String stateName, String message, String command) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleId", id.toString());
        m.put("state", stateName);
        if (message != null) m.put("message", message.length() > 1000 ? message.substring(0, 1000) : message);
        if (command != null) m.put("command", command.length() > 2000 ? command.substring(0, 2000) : command);
        outbox.add(m);
        flush();
    }

    /** Delivers results; kept and retried while the platform is unreachable. */
    void flush() {
        while (!outbox.isEmpty()) {
            Map<String, Object> m = outbox.peek();
            Map<String, Object> body = new LinkedHashMap<>(m);
            String id = (String) body.remove("ruleId");
            PlatformClient.Response res = client.post("/api/v1/agent/firewall/rules/" + id + "/status", body, authorization.get());
            if (res.outcome() == Outcome.RETRY_LATER) return;
            if (res.outcome() != Outcome.OK) {
                log.warning("Platform refused firewall status " + body.get("state") + " for rule " + id + ": " + res.message());
            }
            outbox.poll();
        }
    }

    // For tests.
    List<Map<String, Object>> pending() {
        return List.copyOf(outbox);
    }

    FirewallState state() {
        return state;
    }
}
