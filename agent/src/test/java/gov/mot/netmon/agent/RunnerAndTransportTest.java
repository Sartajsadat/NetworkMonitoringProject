package gov.mot.netmon.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import gov.mot.netmon.agent.collect.CollectorRunner;
import gov.mot.netmon.agent.config.AgentSettings;
import gov.mot.netmon.agent.config.CredentialStore;
import gov.mot.netmon.agent.config.CredentialStore.Credentials;
import gov.mot.netmon.agent.linux.MemoryCollector;
import gov.mot.netmon.agent.model.Json;
import gov.mot.netmon.agent.transport.PlatformClient;

/** Runs the agent loop against a tiny local HTTP server that plays the platform. */
class RunnerAndTransportTest {

    @TempDir
    Path root;
    HttpServer server;
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<String> responseBody = new AtomicReference<>(
            "{\"accepted\":true,\"config\":{\"reportIntervalSeconds\":15,\"services\":[\"nginx.service\"],\"monitoringPaused\":false}}");
    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    AgentSettings settings;
    CredentialStore store;
    Credentials creds;
    FakeHost host;

    @BeforeEach
    void setUp() throws Exception {
        host = new FakeHost(root);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agent/report", ex -> {
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            received.add(Json.MAPPER.readTree(ex.getRequestBody()));
            byte[] out = responseBody.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/api/v1/agent/credentials/rotate", ex -> {
            byte[] out = "{\"secret\":\"rotated-secret\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        Properties p = new Properties();
        p.setProperty("platform.url", "http://127.0.0.1:" + server.getAddress().getPort());
        p.setProperty("state.dir", root.resolve("state").toString());
        p.setProperty("buffer.max-reports", "3");
        settings = AgentSettings.fromProperties(p);
        store = new CredentialStore(settings.stateDir());
        creds = new Credentials(UUID.randomUUID(), "s3cret", UUID.randomUUID(), "web-01", settings.platformUrl().toString(),
                Instant.now(), null);
        store.save(creds);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private AgentRunner runner(Clock clock) throws Exception {
        return new AgentRunner(settings, store, creds, new PlatformClient(settings.platformUrl(), null),
                new CollectorRunner(List.of(new MemoryCollector(host.proc))), clock);
    }

    @Test
    void sendsReportWithAgentCredentialAndAppliesConfig() throws Exception {
        AgentRunner r = runner(Clock.systemUTC());
        r.cycle();
        assertEquals(1, received.size());
        assertEquals("Agent " + creds.agentId() + ":s3cret", authHeaders.get(0));
        assertEquals(25.0, received.get(0).at("/memory/usedPercent").asDouble());
        assertEquals("memory", received.get(0).at("/collectors/0/name").asText());
        assertEquals(15, r.config().reportIntervalSeconds());
        assertEquals(List.of("nginx.service"), r.monitoredServices());
    }

    @Test
    void buffersWhilePlatformIsDownAndDeliversOldestFirstLater() throws Exception {
        AgentRunner r = runner(Clock.systemUTC());
        status.set(503);
        r.cycle();
        r.cycle();
        assertEquals(2, r.buffer().size());
        status.set(200);
        received.clear();
        r.cycle();
        assertEquals(0, r.buffer().size());
        assertEquals(3, received.size());
        Instant a = Instant.parse(received.get(0).get("collectedAt").asText());
        Instant c = Instant.parse(received.get(2).get("collectedAt").asText());
        assertTrue(!a.isAfter(c), "oldest report first");
    }

    @Test
    void bufferIsBoundedAndDropsOldest() throws Exception {
        AgentRunner r = runner(Clock.systemUTC());
        status.set(500);
        for (int i = 0; i < 5; i++) r.cycle();
        assertEquals(3, r.buffer().size());
        assertEquals(2, r.buffer().dropped());
    }

    @Test
    void rejectedReportIsDroppedNotRetriedForever() throws Exception {
        AgentRunner r = runner(Clock.systemUTC());
        status.set(400);
        responseBody.set("{\"code\":\"REPORT_TOO_OLD\",\"message\":\"too old\"}");
        r.cycle();
        assertEquals(0, r.buffer().size());
    }

    @Test
    void rotatesCredentialAndPersistsItBeforeUse() throws Exception {
        Instant later = Instant.now().plus(java.time.Duration.ofDays(31));
        AgentRunner r = runner(Clock.fixed(later, ZoneOffset.UTC));
        r.cycle();
        Credentials saved = store.load().orElseThrow();
        assertEquals("rotated-secret", saved.secret());
        r.cycle();
        assertEquals("Agent " + creds.agentId() + ":rotated-secret", authHeaders.get(authHeaders.size() - 1));
    }

    @Test
    void credentialFileIsOwnerOnlyAndGroupReadableFileIsRefused() throws Exception {
        Path file = store.file();
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IOException.class, store::load);
    }

    @Test
    void plainHttpOnlyAllowedForLocalhostOrExplicitLab() {
        Properties p = new Properties();
        p.setProperty("platform.url", "http://netmon.mot.local");
        assertThrows(IllegalStateException.class, () -> AgentSettings.fromProperties(p).requireSecureTransport());
        p.setProperty("platform.url", "https://netmon.mot.local/");
        AgentSettings ok = AgentSettings.fromProperties(p);
        ok.requireSecureTransport();
        assertEquals(URI.create("https://netmon.mot.local"), ok.platformUrl());
        p.setProperty("platform.url", "http://lab-server");
        p.setProperty("tls.allow-insecure-http", "true");
        AgentSettings.fromProperties(p).requireSecureTransport();
    }

    @Test
    void reportJsonOmitsUnmeasuredValues() throws Exception {
        AgentRunner r = runner(Clock.systemUTC());
        r.cycle();
        JsonNode report = received.get(0);
        assertFalse(report.has("cpu"), "no CPU collector configured, so no cpu object (not zeros)");
        assertTrue(report.has("agentVersion"));
    }
}
