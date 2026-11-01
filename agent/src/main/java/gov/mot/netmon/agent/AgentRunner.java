package gov.mot.netmon.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.CollectorRunner;
import gov.mot.netmon.agent.config.AgentSettings;
import gov.mot.netmon.agent.config.CredentialStore;
import gov.mot.netmon.agent.config.CredentialStore.Credentials;
import gov.mot.netmon.agent.model.AgentConfig;
import gov.mot.netmon.agent.model.Json;
import gov.mot.netmon.agent.model.Report;
import gov.mot.netmon.agent.transport.PlatformClient;
import gov.mot.netmon.agent.transport.PlatformClient.Outcome;
import gov.mot.netmon.agent.transport.ReportBuffer;

/**
 * Main loop: collect, queue, deliver (oldest first), apply the configuration returned by the
 * platform, rotate the credential periodically. Never executes instructions from the platform
 * other than the typed settings in {@link AgentConfig}.
 */
public class AgentRunner {

    private static final Logger log = Logger.getLogger(AgentRunner.class.getName());
    static final int MAX_SENDS_PER_CYCLE = 20;
    static final Duration REVOKED_BACKOFF = Duration.ofMinutes(10);

    private final AgentSettings settings;
    private final CredentialStore store;
    private final PlatformClient client;
    private final Clock clock;
    private final ReportBuffer buffer;
    private final AtomicReference<AgentConfig> config = new AtomicReference<>(AgentConfig.defaults());
    private final CollectorRunner collectors;
    private Credentials credentials;
    private volatile boolean running = true;
    private volatile boolean revoked;

    public AgentRunner(AgentSettings settings, CredentialStore store, Credentials credentials, PlatformClient client,
            CollectorRunner collectors, Clock clock) {
        this.settings = settings;
        this.store = store;
        this.credentials = credentials;
        this.client = client;
        this.collectors = collectors;
        this.clock = clock;
        this.buffer = new ReportBuffer(settings.maxBufferedReports());
    }

    /** Services the platform asked us to report on (read by the service collector each cycle). */
    public List<String> monitoredServices() {
        return config.get().services();
    }

    public AgentConfig config() {
        return config.get();
    }

    public void setConfig(AgentConfig c) {
        config.set(c.sanitized());
    }

    public ReportBuffer buffer() {
        return buffer;
    }

    public void stop() {
        running = false;
    }

    /**
     * Collection runs on a fixed schedule on this thread; delivery runs on a separate thread, so a slow
     * or unreachable platform never delays measurements (reports wait in the buffer instead).
     */
    public void runForever() throws InterruptedException {
        log.info("Agent " + AgentInfo.VERSION + " reporting to " + settings.platformUrl() + " as server '"
                + credentials.serverName() + "'");
        Thread sender = new Thread(this::senderLoop, "netmon-sender");
        sender.setDaemon(true);
        sender.start();
        while (running) {
            long started = System.nanoTime();
            if (!revoked) {
                collect();
                synchronized (signal) {
                    signal.notifyAll();
                }
            }
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            sleep(Duration.ofSeconds(config.get().reportIntervalSeconds()).minusMillis(elapsedMs));
        }
    }

    private final Object signal = new Object();

    private void senderLoop() {
        long backoffMs = 0;
        while (running) {
            try {
                if (revoked) {
                    log.severe("This agent's credential was revoked by an administrator. Re-enroll with a new token: "
                            + "sudo -u netmon-agent java -jar /opt/netmon-agent/netmon-agent.jar enroll --token <TOKEN>");
                    sleep(REVOKED_BACKOFF);
                    revoked = !tryOnce();
                    continue;
                }
                int before = buffer.size();
                deliver();
                maybeRotate();
                boolean stuck = buffer.size() > 0 && buffer.size() >= before;
                backoffMs = stuck ? Math.min(60_000, Math.max(5_000, backoffMs * 2)) : 0;
                synchronized (signal) {
                    signal.wait(stuck ? backoffMs : 30_000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warning("Sender error: " + e);
            }
        }
    }

    void collect() {
        Instant now = clock.instant();
        buffer.add(collectors.run(now).build(AgentInfo.VERSION, now, config.get().reportIntervalSeconds()));
    }

    /** One collection + delivery cycle. Package-visible for tests. */
    void cycle() {
        collect();
        deliver();
        maybeRotate();
    }

    void deliver() {
        for (int i = 0; i < MAX_SENDS_PER_CYCLE && buffer.size() > 0; i++) {
            Report next = buffer.peek();
            PlatformClient.Response res = client.post("/api/v1/agent/report", next, credentials.authorizationHeader());
            switch (res.outcome()) {
                case OK -> {
                    buffer.removeFirst();
                    applyConfig(res.body());
                }
                case REJECTED -> {
                    log.warning("Platform rejected a report (" + res.errorCode() + "): " + res.message() + " - dropped");
                    buffer.removeFirst();
                }
                case REVOKED -> {
                    revoked = true;
                    return;
                }
                case UNAUTHORIZED -> {
                    log.severe("Platform refused the agent credential (" + res.status() + "). Check enrollment.");
                    return;
                }
                case RETRY_LATER -> {
                    log.warning("Platform unreachable (" + (res.status() == 0 ? res.message() : "HTTP " + res.status())
                            + "); " + buffer.size() + " report(s) queued");
                    return;
                }
            }
        }
    }

    private boolean tryOnce() {
        try {
            store.load().ifPresent(c -> credentials = c);
        } catch (Exception e) {
            return false;
        }
        PlatformClient.Response res = client.post("/api/v1/agent/report",
                collectors.run(clock.instant()).build(AgentInfo.VERSION, clock.instant(), config.get().reportIntervalSeconds()),
                credentials.authorizationHeader());
        return res.outcome() == Outcome.OK;
    }

    private void applyConfig(JsonNode body) {
        if (body == null || !body.has("config")) return;
        try {
            AgentConfig next = Json.MAPPER.treeToValue(body.get("config"), AgentConfig.class).sanitized();
            AgentConfig prev = config.getAndSet(next);
            if (!next.equals(prev)) {
                log.info("Configuration updated: interval=" + next.reportIntervalSeconds() + "s, services="
                        + next.services() + (next.monitoringPaused() ? ", monitoring paused" : ""));
            }
        } catch (Exception e) {
            log.warning("Ignoring malformed configuration from platform: " + e.getMessage());
        }
    }

    void maybeRotate() {
        Instant last = credentials.rotatedAt() != null ? credentials.rotatedAt() : credentials.enrolledAt();
        if (last == null || Duration.between(last, clock.instant()).compareTo(settings.credentialRotation()) < 0) {
            return;
        }
        PlatformClient.Response res = client.post("/api/v1/agent/credentials/rotate", null, credentials.authorizationHeader());
        if (res.outcome() != Outcome.OK || res.body() == null || !res.body().hasNonNull("secret")) {
            log.warning("Credential rotation failed (" + res.status() + "); will retry next cycle");
            return;
        }
        Credentials rotated = credentials.withSecret(res.body().get("secret").asText(), clock.instant());
        try {
            store.save(rotated); // persist first: the old secret only works for a short grace period
            credentials = rotated;
            log.info("Agent credential rotated");
        } catch (Exception e) {
            log.severe("Could not save rotated credential: " + e.getMessage()
                    + ". Keeping the old one (valid during the grace period).");
        }
    }

    private void sleep(Duration d) throws InterruptedException {
        if (!d.isNegative() && !d.isZero()) Thread.sleep(d.toMillis());
    }
}
