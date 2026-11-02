package gov.mot.netmon.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.support.IntegrationTest;
import gov.mot.netmon.user.RoleCode;

/** Flow packets through the collector (without UDP), the writer and the summary API. */
class FlowIngestionTest extends IntegrationTest {

    @Autowired
    FlowCollector collector;
    @Autowired
    FlowWriter writer;
    @Autowired
    SubnetIndex subnets;

    @Test
    void flowsFromRegisteredExportersAreStoredSummarisedAndLinkedToTheInventory() throws Exception {
        String net = tokenFor("netadmin", RoleCode.NETWORK_ADMIN);
        String analyst = tokenFor("analyst", RoleCode.SECURITY_ANALYST);
        mvc.perform(post("/api/v1/subnets").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("cidr", "10.1.1.0/24", "name", "Users")))).andExpect(status().isCreated());
        String dev = body(mvc.perform(post("/api/v1/network-devices").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "edge-router", "deviceType", "ROUTER", "managementIp", "192.0.2.1", "flowExport", true))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        subnets.reload();
        long now = Instant.now().getEpochSecond();
        byte[] a = Packets.v5(now, "10.1.1.5", 51515, "93.184.216.34", 443, 50_000, 40, 0);
        byte[] b = Packets.v5(now, "93.184.216.34", 443, "10.1.1.5", 51515, 2_000_000, 1500, 0);
        byte[] c = Packets.v5(now, "10.1.1.6", 40000, "10.1.1.53", 53, 300, 2, 0);
        collector.handle("192.0.2.1", a, a.length);
        collector.handle("192.0.2.1", b, b.length);
        collector.handle("192.0.2.1", c, c.length);
        collector.handle("198.51.100.66", a, a.length); // not registered: dropped
        writer.drainOnce();

        JsonNode s = body(mvc.perform(get("/api/v1/network-flows/summary").header("Authorization", analyst)).andExpect(status().isOk()).andReturn());
        assertThat(s.get("flows").asLong()).isEqualTo(3);
        assertThat(s.get("bytes").asLong()).isEqualTo(2_050_300);
        assertThat(s.get("topSources").get(0).get("address").asText()).isEqualTo("93.184.216.34");
        assertThat(s.get("topPorts").get(0).get("port").asInt()).isEqualTo(443);
        JsonNode conv = s.get("conversations").get(0);
        assertThat(conv.get("a").asText()).isEqualTo("10.1.1.5");
        assertThat(conv.get("bytes").asLong()).isEqualTo(2_050_000);
        assertThat(conv.get("bytesBtoA").asLong()).isEqualTo(2_000_000);
        assertThat(s.get("exporters").get(0).get("name").asText()).isEqualTo("edge-router");
        assertThat(s.get("sampled").asBoolean()).isFalse();

        mvc.perform(get("/api/v1/network-flows?subnet=10.1.1.0/24&port=53").header("Authorization", analyst))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].dst").value("10.1.1.53"));
        mvc.perform(get("/api/v1/network-flows?subnet=10.1.1.7/24").header("Authorization", analyst)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/network-devices/" + dev).header("Authorization", net))
                .andExpect(jsonPath("$.flowsReceived").value(3)).andExpect(jsonPath("$.lastFlowVersion").value(5));
        assertThat(collector.rejectedPackets()).isPositive();

        // internal addresses from flows join the inventory; internet addresses do not
        JsonNode inv = body(mvc.perform(get("/api/v1/ip-addresses?source=FLOW").header("Authorization", net)).andReturn());
        assertThat(inv.toString()).contains("10.1.1.5").contains("10.1.1.53").doesNotContain("93.184.216.34");
        String id = inv.get("content").get(0).get("id").asText();
        JsonNode detail = body(mvc.perform(get("/api/v1/ip-addresses/" + id).header("Authorization", analyst)).andReturn());
        assertThat(detail.get("flows").get("flows").asLong()).isPositive();
        String viewer = tokenFor("viewer", RoleCode.READ_ONLY);
        mvc.perform(get("/api/v1/ip-addresses/" + id).header("Authorization", viewer))
                .andExpect(jsonPath("$.flowsHidden").value(true)).andExpect(jsonPath("$.flows").doesNotExist());
    }
}
