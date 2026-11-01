package gov.mot.netmon.agent.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import gov.mot.netmon.agent.collect.CommandRunner;
import gov.mot.netmon.agent.model.Json;
import gov.mot.netmon.agent.model.Report;
import gov.mot.netmon.agent.transport.PlatformClient;

/** Capture engine against a local fake platform and a simulated dumpcap. */
class CaptureManagerTest {

    @TempDir
    Path root;
    HttpServer server;
    final ConcurrentLinkedQueue<String> tasks = new ConcurrentLinkedQueue<>();
    final List<JsonNode> statuses = new CopyOnWriteArrayList<>();
    final Map<Integer, Integer> uploads = new ConcurrentHashMap<>();
    final AtomicInteger uploadStatus = new AtomicInteger(200);
    final AtomicBoolean stopRequested = new AtomicBoolean();
    final List<List<String>> launched = new CopyOnWriteArrayList<>();
    FakeCapture fake;
    String filterCheckOutput = "Capturing on 'eth0'\n(000) ldh      [12]\n(001) ret #0\n";
    CaptureSettings settings;
    CaptureManager manager;
    final UUID id = UUID.randomUUID();

    static final String INTERFACES = """
            1. eth0
            2. any
            3. lo (Loopback)
            4. nflog
            """;

    /** Simulated dumpcap: the test writes pieces and decides when it exits. */
    static final class FakeCapture implements Dumpcap.Capture {
        final Path dir;
        volatile boolean alive = true;
        volatile boolean stopCalled;
        volatile String output = "Capturing on 'eth0'\nFile: x\n";

        FakeCapture(Path dir) {
            this.dir = dir;
        }

        void piece(int n, int bytes) throws IOException {
            Files.write(dir.resolve(String.format("cap_%05d_20261101120000.pcapng", n)), new byte[bytes]);
        }

        void exit(long packets) {
            output += "\rPackets captured: " + packets + "\nPackets received/dropped on interface 'eth0': " + packets + "/0 (x)\n";
            alive = false;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void stop() {
            stopCalled = true;
        }

        @Override
        public int exitCode() {
            return alive ? -1 : 0;
        }

        @Override
        public String output() {
            return output;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agent/tasks", ex -> {
            String list = String.join(",", tasks);
            tasks.clear();
            respond(ex, 200, "{\"tasks\":[" + list + "]}");
        });
        server.createContext("/api/v1/agent/captures/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/status")) {
                statuses.add(Json.MAPPER.readTree(ex.getRequestBody()));
                respond(ex, 200, "{\"accepted\":true,\"stopRequested\":false}");
            } else {
                int seq = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
                byte[] body = ex.getRequestBody().readAllBytes();
                if (uploadStatus.get() == 200) uploads.put(seq, body.length);
                respond(ex, uploadStatus.get(), "{\"accepted\":true,\"stopRequested\":" + stopRequested.get() + "}");
            }
        });
        server.start();
        settings = settings("true", "");
        manager = manager(settings);
    }

    CaptureSettings settings(String enabled, String allow) {
        Properties p = new Properties();
        p.setProperty("capture.enabled", enabled);
        p.setProperty("capture.interfaces", allow);
        p.setProperty("capture.max-megabytes", "100");
        p.setProperty("capture.min-free-disk-megabytes", "0");
        p.setProperty("capture.dumpcap-path", "/bin/sh"); // any executable file: commands are simulated
        return CaptureSettings.fromProperties(p, root.resolve("state"), false);
    }

    CaptureManager manager(CaptureSettings s) throws Exception {
        CommandRunner runner = args -> switch (args.get(0)) {
            case "-v" -> new CommandRunner.Result(0, "Dumpcap (Wireshark) 4.2.2\n");
            case "-D" -> new CommandRunner.Result(0, INTERFACES);
            default -> new CommandRunner.Result(0, filterCheckOutput);
        };
        Dumpcap.Launcher launcher = (cmd, dir) -> {
            launched.add(cmd);
            fake = new FakeCapture(dir);
            return fake;
        };
        PlatformClient client = new PlatformClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null);
        return new CaptureManager(s, new Dumpcap(s.dumpcap(), runner, launcher), client, () -> "Agent a:b", Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    void startTask(String iface, String filter, long maxBytes) {
        tasks.add("{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"START_CAPTURE\",\"captureId\":\"" + id
                + "\",\"interfaceName\":\"" + iface + "\",\"captureFilter\":" + (filter == null ? "null" : "\"" + filter + "\"")
                + ",\"snaplen\":256,\"durationSeconds\":60,\"maxBytes\":" + maxBytes + "}");
    }

    List<String> states() {
        List<String> out = new ArrayList<>();
        statuses.forEach(s -> out.add(s.get("state").asText()));
        return out;
    }

    @Test
    void disabledCaptureReportsNotEnabledAndNeverContactsThePlatform() throws Exception {
        manager = manager(settings("false", ""));
        Report.Capture c = manager.capability();
        assertFalse(c.enabled());
        assertFalse(c.available());
        startTask("eth0", null, 1_000_000);
        manager.tick();
        assertEquals(1, tasks.size(), "tasks must not even be fetched when capture is disabled");
    }

    @Test
    void capabilityListsRealInterfacesOrTheAllowlist() throws Exception {
        Report.Capture c = manager.capability();
        assertTrue(c.available());
        assertEquals(List.of("eth0", "lo"), c.interfaces().stream().map(Report.CaptureInterface::name).toList());
        assertEquals("Dumpcap (Wireshark) 4.2.2", c.dumpcapVersion());
        assertEquals(100L * 1024 * 1024, c.maxBytes());

        CaptureManager onlyLoopbackByDescription = manager(settings("true", "Loopback, any"));
        assertEquals(List.of("any", "lo"),
                onlyLoopbackByDescription.capability().interfaces().stream().map(Report.CaptureInterface::name).toList());
    }

    @Test
    void parsesWindowsInterfaceList() {
        var list = Dumpcap.parseInterfaces("""
                1. \\Device\\NPF_{8D1E3C0A-1111-2222-3333-444455556666} (Ethernet0)
                2. \\Device\\NPF_Loopback (Adapter for loopback traffic capture)
                3. etwdump (Event Tracing for Windows (ETW) reader)
                """);
        assertEquals("\\Device\\NPF_{8D1E3C0A-1111-2222-3333-444455556666}", list.get(0).name());
        assertEquals("Ethernet0", list.get(0).description());
        assertEquals(2, Dumpcap.allowed(list, List.of()).size());
        assertEquals(1, Dumpcap.allowed(list, List.of("Ethernet0")).size());
    }

    @Test
    void dumpcapArgumentsAreFixedAndTheFilterStaysOneArgument() {
        List<String> cmd = Dumpcap.command(Path.of("/usr/bin/dumpcap"), "eth0", "tcp port 80; rm -rf /", 256, 60, 15, Path.of("/s"));
        assertEquals(List.of("/usr/bin/dumpcap", "-i", "eth0", "-f", "tcp port 80; rm -rf /", "-s", "256", "-n", "-q", "-a",
                "duration:60", "-b", "duration:15", "-b", "filesize:32768", "-w", Path.of("/s").resolve("cap.pcapng").toString()), cmd);
        assertFalse(Dumpcap.command(Path.of("/usr/bin/dumpcap"), "eth0", " ", 256, 60, 15, Path.of("/s")).contains("-f"));
    }

    @Test
    void capturesUploadsFinishedPiecesInOrderAndReportsCompletion() throws Exception {
        startTask("eth0", "tcp port 80", 50L * 1024 * 1024);
        manager.tick();
        assertEquals(List.of("STARTING", "RUNNING"), states());
        assertEquals("tcp port 80", launched.get(0).get(4));
        Path dir = fake.dir;
        assertTrue(Files.isDirectory(dir));

        fake.piece(1, 1000);
        fake.piece(2, 500); // still being written
        manager.tick();
        assertEquals(Map.of(1, 1000), uploads);
        assertFalse(Files.exists(dir.resolve("cap_00001_20261101120000.pcapng")), "uploaded pieces are deleted");

        fake.piece(3, 200);
        fake.exit(42);
        manager.tick();
        assertEquals(Map.of(1, 1000, 2, 500, 3, 200), uploads);
        JsonNode last = statuses.get(statuses.size() - 1);
        assertEquals("COMPLETED", last.get("state").asText());
        assertEquals(42, last.get("packets").asLong());
        assertFalse(Files.exists(dir), "spool folder removed");
        assertNull(manager.active());
    }

    @Test
    void requestsOutsideLocalLimitsAreRefusedWithoutStartingDumpcap() throws Exception {
        startTask("docker0", null, 1_000_000);
        manager.tick();
        assertEquals("FAILED", statuses.get(0).get("state").asText());
        assertTrue(statuses.get(0).get("message").asText().contains("not allowed"));

        startTask("eth0", null, 500L * 1024 * 1024); // agent allows 100 MB
        manager.tick();
        assertTrue(statuses.get(1).get("message").asText().contains("100 MB"));

        startTask("eth0", "tcp\\u0000port 80", 1_000_000);
        manager.tick();
        assertTrue(statuses.get(2).get("message").asText().contains("not allowed"));
        assertTrue(launched.isEmpty());
    }

    @Test
    void invalidCaptureFilterIsReportedWithDumpcapsExplanation() throws Exception {
        filterCheckOutput = "Capturing on 'eth0'\ndumpcap: Invalid capture filter \"tcp portx 80\" for interface 'eth0'.\n";
        startTask("eth0", "tcp portx 80", 1_000_000);
        manager.tick();
        assertEquals(List.of("STARTING", "FAILED"), states());
        assertTrue(statuses.get(1).get("message").asText().contains("Invalid capture filter"));
        assertTrue(launched.isEmpty());
    }

    @Test
    void stopFromPlatformEndsDumpcapAndCompletes() throws Exception {
        startTask("eth0", null, 50L * 1024 * 1024);
        manager.tick();
        tasks.add("{\"type\":\"STOP_CAPTURE\",\"captureId\":\"" + id + "\"}");
        manager.pollTasks();
        assertTrue(fake.stopCalled);
        fake.piece(1, 300);
        fake.exit(7);
        manager.tick();
        JsonNode last = statuses.get(statuses.size() - 1);
        assertEquals("COMPLETED", last.get("state").asText());
        assertEquals("Stopped on request", last.get("message").asText());
    }

    @Test
    void sizeLimitStopsTheCapture() throws Exception {
        startTask("eth0", null, 1500);
        manager.tick();
        fake.piece(1, 1000);
        fake.piece(2, 600);
        manager.tick();
        assertTrue(fake.stopCalled);
        fake.exit(10);
        manager.tick();
        assertEquals("Size limit reached", statuses.get(statuses.size() - 1).get("message").asText());
    }

    @Test
    void piecesAreKeptAndRetriedWhilePlatformIsUnreachable() throws Exception {
        startTask("eth0", null, 50L * 1024 * 1024);
        manager.tick();
        uploadStatus.set(503);
        fake.piece(1, 100);
        fake.exit(3);
        manager.tick();
        assertTrue(uploads.isEmpty());
        assertTrue(Files.exists(fake.dir.resolve("cap_00001_20261101120000.pcapng")));
        assertTrue(manager.busy());
        uploadStatus.set(200);
        manager.tick();
        assertEquals(Map.of(1, 100), uploads);
        assertFalse(manager.busy());
    }

    @Test
    void onlyOneCaptureAtATimeAndLeftoversAreCleanedOnStart() throws Exception {
        startTask("eth0", null, 1_000_000);
        manager.tick();
        UUID other = UUID.randomUUID();
        tasks.add("{\"type\":\"START_CAPTURE\",\"captureId\":\"" + other + "\",\"interfaceName\":\"eth0\",\"snaplen\":256,"
                + "\"durationSeconds\":60,\"maxBytes\":1000}");
        manager.pollTasks();
        assertTrue(statuses.get(statuses.size() - 1).get("message").asText().contains("already running"));

        Path leftover = settings.spoolDir().resolve(UUID.randomUUID().toString());
        Files.createDirectories(leftover);
        Files.write(leftover.resolve("cap_00001_20261101120000.pcapng"), new byte[10]);
        manager(settings).cleanupSpool();
        assertFalse(Files.exists(leftover));
        assertEquals("FAILED", statuses.get(statuses.size() - 1).get("state").asText());
    }
}
