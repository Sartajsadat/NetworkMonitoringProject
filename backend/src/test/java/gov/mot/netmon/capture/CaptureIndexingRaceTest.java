package gov.mot.netmon.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

/**
 * Regression: background indexing of the last piece used to save the whole capture row and
 * overwrite the COMPLETED status the agent reported meanwhile (seen in a real end-to-end run).
 */
class CaptureIndexingRaceTest extends AgentTestSupport {

    @MockitoSpyBean
    Tshark tshark;

    @Test
    void agentStatusSurvivesConcurrentIndexing() throws Exception {
        assumeTrue(tshark.available(), "TShark not installed");
        CountDownLatch analysing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            analysing.countDown();
            release.await(30, TimeUnit.SECONDS);
            return inv.callRealMethod();
        }).when(tshark).summaries(any());

        String infra = tokenFor("infra", RoleCode.INFRASTRUCTURE_ADMIN);
        String netAdmin = tokenFor("netadmin", RoleCode.NETWORK_ADMIN);
        String serverId = createServer(infra, "web-01");
        String agent = enroll(infra, serverId, "web-01");
        Map<String, Object> r = report(Instant.now(), 5.0, 100.0);
        r.put("capture", Map.of("enabled", true, "available", true, "interfaces", List.of(Map.of("name", "lo")),
                "maxDurationSeconds", 600, "maxBytes", 104857600));
        mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(r))).andExpect(status().isOk());
        String id = body(mvc.perform(post("/api/v1/captures").header("Authorization", netAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("serverId", serverId, "name", "race",
                        "reason", "Regression test for concurrent indexing", "interfaceName", "lo", "durationSeconds", 60,
                        "maxMegabytes", 10)))).andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(post("/api/v1/agent/tasks").header("Authorization", agent)).andExpect(status().isOk());
        byte[] data;
        try (InputStream in = getClass().getResourceAsStream("/capture/segment1.pcapng")) {
            data = in.readAllBytes();
        }
        // upload schedules background indexing, which loads the capture and then waits inside TShark
        mvc.perform(put("/api/v1/agent/captures/" + id + "/segments/1").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(data)).andExpect(status().isOk());
        assertThat(analysing.await(10, TimeUnit.SECONDS)).isTrue();
        mvc.perform(post("/api/v1/agent/captures/" + id + "/status").header("Authorization", agent)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("state", "COMPLETED", "packets", 16))))
                .andExpect(status().isOk());
        release.countDown();

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline
                && jdbc.queryForObject("SELECT packets_indexed FROM packet_captures WHERE id = ?::uuid", Long.class, id) == 0) {
            Thread.sleep(100);
        }
        String json = mvc.perform(get("/api/v1/captures/" + id).header("Authorization", netAdmin)).andReturn().getResponse()
                .getContentAsString();
        assertThat(json).contains("\"status\":\"COMPLETED\"").contains("\"packetsIndexed\":16");
    }
}
