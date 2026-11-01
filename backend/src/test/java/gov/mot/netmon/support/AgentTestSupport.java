package gov.mot.netmon.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

/** Helpers for tests that act as a monitoring agent. */
public abstract class AgentTestSupport extends IntegrationTest {

    protected String createServer(String adminToken, String name) throws Exception {
        return body(mvc.perform(post("/api/v1/servers").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", name, "hostname", name + ".mot.local", "osFamily", "LINUX",
                        "environment", "TESTING", "organization", "Ministry IT"))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    protected String createToken(String adminToken, String serverId) throws Exception {
        return body(mvc.perform(post("/api/v1/servers/" + serverId + "/agent/enrollment-token")
                .header("Authorization", adminToken)).andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    protected Map<String, Object> enrollBody(String token, String hostname) {
        Map<String, Object> m = new HashMap<>();
        m.put("token", token);
        m.put("hostname", hostname);
        m.put("platform", "LINUX");
        m.put("osName", "Ubuntu");
        m.put("osVersion", "24.04");
        m.put("kernel", "6.8.0");
        m.put("architecture", "amd64");
        m.put("agentVersion", "0.2.0");
        return m;
    }

    /** Enrolls and returns the value for an "Authorization: Agent ..." header. */
    protected String enroll(String adminToken, String serverId, String hostname) throws Exception {
        String token = createToken(adminToken, serverId);
        JsonNode res = body(mvc.perform(post("/api/v1/agent/enroll").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(enrollBody(token, hostname)))).andExpect(status().isOk()).andReturn());
        return "Agent " + res.get("agentId").asText() + ":" + res.get("secret").asText();
    }

    protected Map<String, Object> report(Instant collectedAt, Double cpu, Double rxBps) {
        Map<String, Object> host = new HashMap<>(Map.of("hostname", "web-01", "platform", "LINUX", "osName", "Ubuntu",
                "uptimeSeconds", 3600, "cpuCount", 4));
        Map<String, Object> cpuMap = new HashMap<>();
        cpuMap.put("usagePercent", cpu);
        cpuMap.put("load1", 0.5);
        cpuMap.put("perCore", List.of(10.0, 20.0, 30.0, 40.0));
        Map<String, Object> nic = new HashMap<>(Map.of("name", "eth0", "operState", "up", "rxBytes", 1000, "txBytes", 2000,
                "addresses", List.of("10.0.0.5/24")));
        if (rxBps != null) {
            nic.put("rxBitsPerSec", rxBps);
            nic.put("txBitsPerSec", rxBps / 2);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("agentVersion", "0.2.0");
        m.put("collectedAt", collectedAt.toString());
        m.put("intervalSeconds", 30);
        m.put("host", host);
        m.put("cpu", cpuMap);
        m.put("memory", Map.of("totalBytes", 8_000_000_000L, "availableBytes", 6_000_000_000L,
                "usedBytes", 2_000_000_000L, "usedPercent", 25.0, "swapTotalBytes", 0, "swapUsedBytes", 0));
        m.put("disks", List.of(Map.of("mount", "/", "device", "/dev/sda1", "fsType", "ext4", "totalBytes", 100,
                "usedBytes", 40, "freeBytes", 60, "usedPercent", 40.0)));
        m.put("network", new ArrayList<>(List.of(nic)));
        m.put("services", List.of(Map.of("name", "nginx.service", "status", "RUNNING", "activeState", "active")));
        m.put("processes", List.of(Map.of("pid", 1, "name", "systemd", "user", "root", "cpuPercent", 0.1, "memoryBytes", 1000)));
        m.put("collectors", List.of(Map.of("name", "cpu", "status", "OK")));
        return m;
    }
}
