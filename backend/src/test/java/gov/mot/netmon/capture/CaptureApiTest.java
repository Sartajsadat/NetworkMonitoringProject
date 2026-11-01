package gov.mot.netmon.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

/**
 * Capture lifecycle with a simulated agent and real TShark analysis of recorded PCAPNG pieces.
 * Analysis tests are skipped when TShark is not installed on the machine running the tests.
 */
class CaptureApiTest extends AgentTestSupport {

    @Autowired
    CaptureIndexer indexer;
    @Autowired
    CaptureJobs jobs;
    @Autowired
    Tshark tshark;

    private String netAdmin;
    private String analyst;
    private String serverId;
    private String agent;

    @BeforeEach
    void setUp() throws Exception {
        String infra = tokenFor("infra", RoleCode.INFRASTRUCTURE_ADMIN);
        netAdmin = tokenFor("netadmin", RoleCode.NETWORK_ADMIN);
        analyst = tokenFor("analyst", RoleCode.SECURITY_ANALYST);
        serverId = createServer(infra, "web-01");
        agent = enroll(infra, serverId, "web-01");
        reportCapability(true, true);
    }

    private void reportCapability(boolean enabled, boolean available) throws Exception {
        Map<String, Object> r = report(Instant.now(), 5.0, 100.0);
        r.put("capture", Map.of("enabled", enabled, "available", available, "maxDurationSeconds", 3600,
                "maxBytes", 104857600, "interfaces", List.of(Map.of("name", "eth0"), Map.of("name", "lo", "description", "Loopback"))));
        mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(r))).andExpect(status().isOk());
    }

    private Map<String, Object> request(String iface) {
        Map<String, Object> m = new HashMap<>();
        m.put("serverId", serverId);
        m.put("name", "web-01 slow responses");
        m.put("reference", "INC-2026-0042");
        m.put("reason", "Investigating slow HTTP responses reported by the Ministry of Health");
        m.put("interfaceName", iface);
        m.put("captureFilter", "tcp port 80 or udp port 53");
        m.put("durationSeconds", 300);
        m.put("maxMegabytes", 50);
        return m;
    }

    private String createCapture() throws Exception {
        return body(mvc.perform(post("/api/v1/captures").header("Authorization", netAdmin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request("lo")))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn()).get("id").asText();
    }

    private void agentStatus(String id, String state) throws Exception {
        mvc.perform(post("/api/v1/agent/captures/" + id + "/status").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("state", state, "packets", 28))))
                .andExpect(status().isOk());
    }

    private void upload(String id, int seq) throws Exception {
        byte[] data;
        try (InputStream in = getClass().getResourceAsStream("/capture/segment" + seq + ".pcapng")) {
            data = in.readAllBytes();
        }
        mvc.perform(put("/api/v1/agent/captures/" + id + "/segments/" + seq).header("Authorization", agent)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(data)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    /** Full happy path up to two analysed pieces. */
    private String runningCaptureWithData() throws Exception {
        assumeTrue(tshark.available(), "TShark not installed: analysis tests skipped");
        String id = createCapture();
        mvc.perform(post("/api/v1/agent/tasks").header("Authorization", agent)).andExpect(status().isOk());
        agentStatus(id, "STARTING");
        agentStatus(id, "RUNNING");
        upload(id, 1);
        upload(id, 2);
        indexer.indexPending(UUID.fromString(id));
        return id;
    }

    @Test
    void onlyNetworkAndSuperAdminsCanStartCapturesAndOnlyViewersSeeThem() throws Exception {
        String readOnly = tokenFor("viewer", RoleCode.READ_ONLY);
        mvc.perform(post("/api/v1/captures").header("Authorization", analyst).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request("lo")))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/captures").header("Authorization", readOnly)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/captures").header("Authorization", analyst)).andExpect(status().isOk());
        createCapture();
        assertThat(auditCount("CAPTURE_REQUESTED", "SUCCESS")).isPositive();
    }

    @Test
    void requestIsValidatedAgainstAgentCapabilityAndLimits() throws Exception {
        mvc.perform(post("/api/v1/captures").header("Authorization", netAdmin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request("wlan0")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INTERFACE_NOT_ALLOWED"));
        Map<String, Object> tooBig = request("lo");
        tooBig.put("maxMegabytes", 500);
        mvc.perform(post("/api/v1/captures").header("Authorization", netAdmin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(tooBig))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SIZE_TOO_LARGE"));
        Map<String, Object> badFilter = request("lo");
        badFilter.put("captureFilter", "tcp\nport 80");
        mvc.perform(post("/api/v1/captures").header("Authorization", netAdmin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(badFilter))).andExpect(status().isBadRequest());
        Map<String, Object> noReason = request("lo");
        noReason.put("reason", "short");
        mvc.perform(post("/api/v1/captures").header("Authorization", netAdmin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(noReason))).andExpect(status().isBadRequest());

        createCapture();
        mvc.perform(post("/api/v1/captures").header("Authorization", netAdmin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request("lo")))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAPTURE_BUSY"));
    }

    @Test
    void captureIsRefusedWhenNotEnabledOnTheServer() throws Exception {
        reportCapability(false, false);
        mvc.perform(get("/api/v1/captures/capabilities").header("Authorization", netAdmin))
                .andExpect(jsonPath("$[0].state").value("NOT_ENABLED"));
        mvc.perform(post("/api/v1/captures").header("Authorization", netAdmin).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request("lo")))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPTURE_NOT_AVAILABLE"));
    }

    @Test
    void agentReceivesTypedTaskOnceAndStopBeforeStartCancelsIt() throws Exception {
        String id = createCapture();
        mvc.perform(post("/api/v1/agent/tasks").header("Authorization", agent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[0].type").value("START_CAPTURE"))
                .andExpect(jsonPath("$.tasks[0].interfaceName").value("lo"))
                .andExpect(jsonPath("$.tasks[0].captureFilter").value("tcp port 80 or udp port 53"))
                .andExpect(jsonPath("$.tasks[0].snaplen").value(256))
                .andExpect(jsonPath("$.tasks[0].maxBytes").value(50 * 1024 * 1024));
        mvc.perform(post("/api/v1/agent/tasks").header("Authorization", agent)).andExpect(jsonPath("$.tasks.length()").value(0));
        agentStatus(id, "COMPLETED");

        String second = createCapture();
        mvc.perform(post("/api/v1/captures/" + second + "/stop").header("Authorization", netAdmin))
                .andExpect(jsonPath("$.status").value("STOPPED"));
        mvc.perform(post("/api/v1/agent/tasks").header("Authorization", agent)).andExpect(jsonPath("$.tasks.length()").value(0));
    }

    @Test
    void packetsAreIndexedFilteredAndExplained() throws Exception {
        String id = runningCaptureWithData();
        mvc.perform(get("/api/v1/captures/" + id).header("Authorization", analyst))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.segmentsCount").value(2))
                .andExpect(jsonPath("$.packetsIndexed").value(28));
        mvc.perform(get("/api/v1/captures/" + id + "/packets").header("Authorization", analyst).param("size", "5"))
                .andExpect(jsonPath("$.totalElements").value(28))
                .andExpect(jsonPath("$.content[0].frameNo").value(1))
                .andExpect(jsonPath("$.content[0].src").value("127.0.0.1"))
                .andExpect(jsonPath("$.content[0].protocol").value("TCP"));
        // second piece continues the numbering
        mvc.perform(get("/api/v1/captures/" + id + "/packets").header("Authorization", analyst).param("protocol", "HTTP"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[2].frameNo").value(20));
        mvc.perform(get("/api/v1/captures/" + id + "/packets").header("Authorization", analyst).param("protocol", "DNS"))
                .andExpect(jsonPath("$.content[0].dnsQuery").value("example.gov.af"));
        // Wireshark display filter, evaluated by TShark over the stored files
        mvc.perform(get("/api/v1/captures/" + id + "/packets").header("Authorization", analyst)
                .param("displayFilter", "tcp.flags.reset == 1"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].frameNo").value(14))
                .andExpect(jsonPath("$.content[0].reset").value(true));
        mvc.perform(get("/api/v1/captures/" + id + "/packets").header("Authorization", analyst)
                .param("displayFilter", "ip.addr == ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILTER"));
        mvc.perform(post("/api/v1/captures/display-filter/validate").header("Authorization", analyst)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("filter", "http.request.method == \"GET\""))))
                .andExpect(jsonPath("$.valid").value(true));
        // protocol tree
        String detail = mvc.perform(get("/api/v1/captures/" + id + "/packets/4").header("Authorization", analyst))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).contains("Internet Protocol Version 4").contains("Hypertext Transfer Protocol").contains("GET / HTTP/1.1");
    }

    @Test
    void rawBytesAndDownloadAreRestrictedAndAudited() throws Exception {
        String id = runningCaptureWithData();
        mvc.perform(get("/api/v1/captures/" + id + "/packets/1/bytes").header("Authorization", analyst)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/captures/" + id + "/download").header("Authorization", analyst)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/captures/" + id + "/packets/1/bytes").header("Authorization", netAdmin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hex").value(org.hamcrest.Matchers.startsWith("0000")));
        var res = mvc.perform(get("/api/v1/captures/" + id + "/download").header("Authorization", netAdmin))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(res)).andExpect(status().isOk());
        byte[] file = res.getResponse().getContentAsByteArray();
        long expected;
        try (InputStream a = getClass().getResourceAsStream("/capture/segment1.pcapng");
             InputStream b = getClass().getResourceAsStream("/capture/segment2.pcapng")) {
            expected = a.readAllBytes().length + b.readAllBytes().length;
        }
        assertThat((long) file.length).isEqualTo(expected);
        assertThat(file[0]).isEqualTo((byte) 0x0A); // PCAPNG section header block
        assertThat(res.getResponse().getHeader("Content-Disposition")).contains(".pcapng");
        assertThat(auditCount("CAPTURE_DOWNLOADED", "SUCCESS")).isPositive();
        assertThat(auditCount("CAPTURE_PACKET_BYTES_VIEWED", "SUCCESS")).isPositive();
    }

    @Test
    void statisticsAndConversations() throws Exception {
        String id = runningCaptureWithData();
        JsonNode stats = body(mvc.perform(get("/api/v1/captures/" + id + "/statistics").header("Authorization", analyst))
                .andExpect(status().isOk()).andReturn());
        assertThat(stats.get("packets").asLong()).isEqualTo(28);
        assertThat(stats.get("tcpResets").asLong()).isEqualTo(1);
        assertThat(stats.get("protocols").toString()).contains("TCP").contains("HTTP").contains("DNS");
        assertThat(stats.get("conversations").get(0).get("a").asText()).isEqualTo("127.0.0.1");
        assertThat(stats.get("dnsQueries").get(0).get("key").asText()).isEqualTo("example.gov.af");
        assertThat(stats.get("timeline").size()).isPositive();
    }

    @Test
    void stopIsDeliveredToAgentAndFinishedCapturesRejectData() throws Exception {
        String id = runningCaptureWithData();
        mvc.perform(post("/api/v1/captures/" + id + "/stop").header("Authorization", netAdmin))
                .andExpect(jsonPath("$.status").value("STOPPING"));
        mvc.perform(post("/api/v1/agent/tasks").header("Authorization", agent))
                .andExpect(jsonPath("$.tasks[0].type").value("STOP_CAPTURE"));
        agentStatus(id, "COMPLETED");
        mvc.perform(get("/api/v1/captures/" + id).header("Authorization", analyst))
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.agentPackets").value(28));
        mvc.perform(put("/api/v1/agent/captures/" + id + "/segments/3").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[] {1, 2, 3}))
                .andExpect(status().isConflict());
    }

    @Test
    void anotherServersAgentCannotUploadIntoThisCapture() throws Exception {
        String id = createCapture();
        String infra = tokenFor("infra2", RoleCode.INFRASTRUCTURE_ADMIN);
        String other = enroll(infra, createServer(infra, "db-02"), "db-02");
        mvc.perform(put("/api/v1/agent/captures/" + id + "/segments/1").header("Authorization", other)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[] {1})).andExpect(status().isForbidden());
    }

    @Test
    void retentionDeletesFilesAndPacketMetadata() throws Exception {
        String id = runningCaptureWithData();
        agentStatus(id, "COMPLETED");
        jdbc.update("UPDATE packet_captures SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", id);
        jobs.retention();
        mvc.perform(get("/api/v1/captures/" + id).header("Authorization", analyst))
                .andExpect(jsonPath("$.filesAvailable").value(false)).andExpect(jsonPath("$.filesDeletedAt").exists());
        mvc.perform(get("/api/v1/captures/" + id + "/packets").header("Authorization", analyst))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/captures/" + id + "/download").header("Authorization", netAdmin)).andExpect(status().isGone());
        assertThat(Files.exists(Path.of(System.getProperty("java.io.tmpdir"), "netmon-test-captures", id))).isFalse();
        assertThat(auditCount("CAPTURE_FILES_DELETED", "SUCCESS")).isPositive();
    }

    @Test
    void pendingCaptureFailsWhenAgentNeverPicksItUp() throws Exception {
        String id = createCapture();
        jdbc.update("UPDATE packet_captures SET requested_at = now() - interval '1 hour' WHERE id = ?::uuid", id);
        jobs.timeouts();
        mvc.perform(get("/api/v1/captures/" + id).header("Authorization", analyst))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.statusMessage").value(org.hamcrest.Matchers.containsString("did not pick up")));
        mvc.perform(delete("/api/v1/captures/" + id + "/files").header("Authorization", netAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reason", "cleanup")))).andExpect(status().isForbidden());
    }
}
