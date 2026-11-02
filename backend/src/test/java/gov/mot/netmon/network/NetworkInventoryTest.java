package gov.mot.netmon.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

class NetworkInventoryTest extends AgentTestSupport {

    @Autowired
    IpInventorySync sync;

    String net;
    String viewer;
    String infra;

    @BeforeEach
    void setUp() throws Exception {
        net = tokenFor("netadmin", RoleCode.NETWORK_ADMIN);
        viewer = tokenFor("viewer", RoleCode.READ_ONLY);
        infra = tokenFor("infra", RoleCode.INFRASTRUCTURE_ADMIN);
    }

    private String subnet(String cidr, String name) throws Exception {
        return body(mvc.perform(post("/api/v1/subnets").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("cidr", cidr, "name", name)))).andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    private JsonNode ips(String token, String query) throws Exception {
        return body(mvc.perform(get("/api/v1/ip-addresses?" + query).header("Authorization", token)).andExpect(status().isOk()).andReturn());
    }

    @Test
    void subnetsAreValidatedNestedAndOnlyNetworkAdminsChangeThem() throws Exception {
        mvc.perform(post("/api/v1/subnets").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("cidr", "10.10.1.7/24", "name", "x")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CIDR")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("10.10.1.0/24")));
        mvc.perform(post("/api/v1/subnets").header("Authorization", infra).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("cidr", "10.10.1.0/24", "name", "x")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/subnets").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("cidr", "10.10.1.0/24", "name", "x", "gateway", "10.10.2.1")))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GATEWAY_OUTSIDE"));
        String site = subnet("10.10.0.0/16", "Kabul DC1");
        String vlan = subnet("10.10.1.0/24", "Servers VLAN 10");
        subnet("2001:db8:10::/48", "IPv6 DC1");
        mvc.perform(post("/api/v1/subnets").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("cidr", "10.10.1.0/24", "name", "dup")))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/ip-addresses").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("address", "10.10.1.20", "allocation", "RESERVED", "hostname", "printer-1")))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.subnetId").value(vlan)).andExpect(jsonPath("$.seen").value("NEVER_SEEN"));
        mvc.perform(post("/api/v1/ip-addresses").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("address", "10.10.9.1")))).andExpect(jsonPath("$.subnetId").value(site));
        mvc.perform(get("/api/v1/subnets").header("Authorization", viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.cidr=='10.10.1.0/24')].knownAddresses").value(1))
                .andExpect(jsonPath("$[?(@.cidr=='10.10.1.0/24')].usable").value("254"))
                .andExpect(jsonPath("$[?(@.cidr=='2001:db8:10::/48')].usedPercent").doesNotExist());
        // deleting the /24 moves its addresses to the enclosing /16
        mvc.perform(delete("/api/v1/subnets/" + vlan).header("Authorization", net)).andExpect(status().isNoContent());
        assertThat(ips(viewer, "q=10.10.1.20").get("content").get(0).get("subnetId").asText()).isEqualTo(site);
        assertThat(auditCount("SUBNET_CREATED", "SUCCESS")).isPositive();
    }

    @Test
    void agentsAndTheRegistryFillTheInventoryAndMacChangesAreFlagged() throws Exception {
        subnet("10.0.0.0/24", "Servers");
        String serverId = createServer(infra, "web-01");
        mvc.perform(put("/api/v1/servers/" + serverId).header("Authorization", infra).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "web-01", "hostname", "web-01.mot.local", "osFamily", "LINUX", "environment",
                        "TESTING", "organization", "Ministry IT", "ipAddresses", List.of(Map.of("address", "10.0.0.50"))))))
                .andExpect(status().isOk());
        JsonNode reg = ips(viewer, "q=10.0.0.50").get("content").get(0);
        assertThat(reg.get("serverName").asText()).isEqualTo("web-01");
        assertThat(reg.get("seen").asText()).isEqualTo("NEVER_SEEN"); // registered is not the same as seen

        String agent = enroll(infra, serverId, "web-01");
        Map<String, Object> r = report(Instant.now(), 5.0, 100.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> nic = new HashMap<>(((List<Map<String, Object>>) r.get("network")).get(0));
        nic.put("macAddress", "52:54:00:12:34:56");
        nic.put("addresses", List.of("10.0.0.5/24", "fe80::1/64", "127.0.0.1/8"));
        r.put("network", List.of(nic));
        r.put("neighbors", List.of(Map.of("ip", "10.0.0.1", "mac", "00:11:22:33:44:55", "interfaceName", "eth0", "state", "REACHABLE"),
                Map.of("ip", "10.0.0.77", "mac", "00:11:22:33:44:66", "state", "FAILED")));
        mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(r))).andExpect(status().isOk());
        JsonNode own = ips(viewer, "q=10.0.0.5&seen=SEEN_NOW").get("content");
        assertThat(own.size()).isEqualTo(1);
        assertThat(own.get(0).get("macAddress").asText()).isEqualTo("52:54:00:12:34:56");
        assertThat(own.get(0).get("sources").toString()).contains("AGENT_INTERFACE");
        assertThat(ips(viewer, "q=fe80").get("totalElements").asLong()).isZero(); // link-local and loopback ignored
        assertThat(ips(viewer, "q=10.0.0.77").get("totalElements").asLong()).isZero(); // failed neighbour entries ignored
        JsonNode gw = ips(viewer, "source=NEIGHBOR").get("content").get(0);
        assertThat(gw.get("address").asText()).isEqualTo("10.0.0.1");

        // the gateway answers from another MAC later: possible spoofing, shown on the timeline
        Map<String, Object> r2 = report(Instant.now(), 5.0, 100.0);
        r2.put("network", List.of(nic));
        r2.put("neighbors", List.of(Map.of("ip", "10.0.0.1", "mac", "de:ad:be:ef:00:01", "state", "REACHABLE")));
        mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(r2))).andExpect(status().isOk());
        String sec = tokenFor("sec", RoleCode.SECURITY_ANALYST);
        JsonNode ev = body(mvc.perform(get("/api/v1/events?type=IP_MAC_CHANGED").header("Authorization", sec)).andReturn());
        assertThat(ev.get("totalElements").asLong()).isEqualTo(1);
        assertThat(ev.get("content").get(0).get("details").get("oldMac").asText()).isEqualTo("00:11:22:33:44:55");
    }

    @Test
    void manualEntriesAndCsvImport() throws Exception {
        subnet("192.168.50.0/24", "Office");
        String csv = "address,mac,hostname,allocation\n192.168.50.10,AA-BB-CC-DD-EE-01,pc-10,DHCP\n192.168.50.11,,pc-11\n"
                + "not-an-ip,,x\n192.168.50.12,zz,pc-12\n";
        mvc.perform(post("/api/v1/ip-addresses/import").header("Authorization", net).contentType(MediaType.TEXT_PLAIN).content(csv))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(2)).andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(post("/api/v1/ip-addresses/import").header("Authorization", viewer).contentType(MediaType.TEXT_PLAIN).content(csv))
                .andExpect(status().isForbidden());
        JsonNode pc = ips(viewer, "q=pc-10").get("content").get(0);
        assertThat(pc.get("macAddress").asText()).isEqualTo("aa:bb:cc:dd:ee:01");
        assertThat(pc.get("allocation").asText()).isEqualTo("DHCP");
        mvc.perform(put("/api/v1/ip-addresses/" + pc.get("id").asText()).header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("address", "192.168.50.10", "hostname", "finance-pc", "allocation", "ASSIGNED", "macAddress", "aa:bb:cc:dd:ee:01"))))
                .andExpect(jsonPath("$.hostname").value("finance-pc"));
        mvc.perform(post("/api/v1/ip-addresses").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("address", "192.168.50.10")))).andExpect(status().isConflict());
        assertThat(auditCount("IP_ADDRESSES_IMPORTED", "SUCCESS")).isPositive();
    }

    @Test
    void devicesValidateExporterAddresses() throws Exception {
        Map<String, Object> d = new HashMap<>(Map.of("name", "core-fw", "deviceType", "FIREWALL", "vendor", "Fortinet",
                "managementIp", "10.255.0.1", "flowExport", true));
        mvc.perform(post("/api/v1/network-devices").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(d))).andExpect(status().isCreated()).andExpect(jsonPath("$.exporterIp").value("10.255.0.1"));
        Map<String, Object> d2 = new HashMap<>(d);
        d2.put("name", "edge-router");
        d2.put("deviceType", "ROUTER");
        mvc.perform(post("/api/v1/network-devices").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(d2))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXPORTER_IN_USE"));
        d2.put("deviceType", "TOASTER");
        mvc.perform(post("/api/v1/network-devices").header("Authorization", net).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(d2))).andExpect(status().isBadRequest());
        // management address appears in the inventory, linked to the device
        assertThat(ips(viewer, "q=10.255.0.1").get("content").get(0).get("deviceName").asText()).isEqualTo("core-fw");
        mvc.perform(get("/api/v1/network-flows/summary").header("Authorization", viewer)).andExpect(status().isForbidden());
    }
}
