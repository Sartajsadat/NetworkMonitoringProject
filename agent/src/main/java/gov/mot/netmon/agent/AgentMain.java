package gov.mot.netmon.agent;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import gov.mot.netmon.agent.collect.CollectorRunner;
import gov.mot.netmon.agent.config.AgentSettings;
import gov.mot.netmon.agent.config.CredentialStore;
import gov.mot.netmon.agent.config.CredentialStore.Credentials;
import gov.mot.netmon.agent.model.AgentConfig;
import gov.mot.netmon.agent.model.Json;
import gov.mot.netmon.agent.model.Report;
import gov.mot.netmon.agent.transport.PlatformClient;

/**
 * Command line:
 * <pre>
 *   netmon-agent enroll --token TOKEN [--url https://netmon.example] [--config FILE]
 *   netmon-agent run    [--config FILE]
 *   netmon-agent collect-once [--config FILE]     (prints one report locally, sends nothing)
 *   netmon-agent version
 * </pre>
 */
public final class AgentMain {

    private AgentMain() {
    }

    public static void main(String[] argv) throws Exception {
        configureLogging();
        if (argv.length == 0) {
            usage();
            System.exit(2);
        }
        Map<String, String> opts = options(argv);
        String command = argv[0];
        if (command.equals("version")) {
            System.out.println("netmon-agent " + AgentInfo.VERSION);
            return;
        }
        Platform platform = Platform.detect();
        if (platform == null) {
            System.err.println("UNSUPPORTED: this agent supports Linux and Windows only (found " + System.getProperty("os.name") + ").");
            System.exit(3);
        }
        AgentSettings settings = AgentSettings.load(Path.of(opts.getOrDefault("config", AgentSettings.defaultFile().toString())));
        if (opts.containsKey("url")) {
            settings = settings.withPlatformUrl(URI.create(opts.get("url").replaceAll("/+$", "")));
        }
        switch (command) {
            case "enroll" -> enroll(platform, settings, opts.get("token"));
            case "run" -> run(platform, settings);
            case "collect-once" -> collectOnce(platform, settings);
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    private static void enroll(Platform platform, AgentSettings settings, String token) throws Exception {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("--token is required");
        }
        settings.requireSecureTransport();
        PlatformClient client = new PlatformClient(settings.platformUrl(), settings.caCertificate());
        Report.Host host = platform.hostInfo(settings);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token.trim());
        body.put("hostname", host == null ? "unknown" : host.hostname());
        body.put("platform", platform.name());
        body.put("osName", host == null ? null : host.osName());
        body.put("osVersion", host == null ? null : host.osVersion());
        body.put("kernel", host == null ? null : host.kernel());
        body.put("architecture", host != null && host.architecture() != null ? host.architecture() : System.getProperty("os.arch"));
        body.put("agentVersion", AgentInfo.VERSION);
        body.put("machineId", platform.machineIdHash(settings));
        PlatformClient.Response res = client.post("/api/v1/agent/enroll", body, null);
        if (res.outcome() != PlatformClient.Outcome.OK) {
            System.err.println("Enrollment failed (HTTP " + res.status() + "): "
                    + (res.message() != null ? res.message() : "platform not reachable"));
            System.exit(1);
        }
        var j = res.body();
        Credentials c = new Credentials(UUID.fromString(j.get("agentId").asText()), j.get("secret").asText(),
                UUID.fromString(j.get("serverId").asText()), j.get("serverName").asText(), settings.platformUrl().toString(),
                Instant.now(), null);
        CredentialStore store = new CredentialStore(settings.stateDir());
        store.save(c);
        System.out.println("Enrolled as server '" + c.serverName() + "'. Credential saved to " + store.file() + ".");
    }

    private static void run(Platform platform, AgentSettings settings) throws Exception {
        CredentialStore store = new CredentialStore(settings.stateDir());
        Credentials creds = store.load().orElseThrow(() -> new IllegalStateException(
                "Not enrolled. Run: netmon-agent enroll --token <TOKEN> --url <platform URL>"));
        if (settings.platformUrl() == null) {
            settings = settings.withPlatformUrl(URI.create(creds.platformUrl()));
        }
        settings.requireSecureTransport();
        PlatformClient client = new PlatformClient(settings.platformUrl(), settings.caCertificate());
        AgentRunner[] holder = new AgentRunner[1];
        CollectorRunner collectors = new CollectorRunner(platform.collectors(settings,
                () -> holder[0] == null ? List.of() : holder[0].monitoredServices()));
        AgentRunner runner = new AgentRunner(settings, store, creds, client, collectors, Clock.systemUTC());
        holder[0] = runner;
        Runtime.getRuntime().addShutdownHook(new Thread(runner::stop));
        runner.runForever();
    }

    private static void collectOnce(Platform platform, AgentSettings settings) throws Exception {
        CollectorRunner collectors = new CollectorRunner(platform.collectors(settings, List::of));
        collectors.run(Instant.now());
        Thread.sleep(2000); // second sample so rates can be calculated
        Instant now = Instant.now();
        Report r = collectors.run(now).build(AgentInfo.VERSION, now, AgentConfig.defaults().reportIntervalSeconds());
        System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(r));
    }

    static Map<String, String> options(String[] argv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < argv.length; i++) {
            if (argv[i].startsWith("--") && i + 1 < argv.length) {
                m.put(argv[i].substring(2), argv[++i]);
            }
        }
        return m;
    }

    private static void usage() {
        System.err.println("""
                Usage:
                  netmon-agent enroll --token TOKEN [--url https://netmon.example] [--config FILE]
                  netmon-agent run [--config FILE]
                  netmon-agent collect-once [--config FILE]
                  netmon-agent version""");
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        for (var h : root.getHandlers()) root.removeHandler(h);
        ConsoleHandler h = new ConsoleHandler();
        h.setFormatter(new SimpleFormatter() {
            @Override
            public synchronized String format(LogRecord r) {
                String msg = formatMessage(r);
                String thrown = r.getThrown() == null ? "" : " - " + r.getThrown();
                return Instant.ofEpochMilli(r.getMillis()) + " " + r.getLevel() + " " + msg + thrown + System.lineSeparator();
            }
        });
        h.setLevel(Level.INFO);
        root.addHandler(h);
        root.setLevel(Level.INFO);
    }
}
