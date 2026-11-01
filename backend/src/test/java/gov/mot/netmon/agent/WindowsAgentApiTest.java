package gov.mot.netmon.agent;

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
import org.springframework.http.MediaType;

import gov.mot.netmon.support.AgentTestSupport;
import gov.mot.netmon.user.RoleCode;

/** Windows agents use the same protocol; names and values that only occur on Windows must be accepted. */
class WindowsAgentApiTest extends AgentTestSupport {

    private String admin;
    private String serverId;

    @BeforeEach
    void setUp() throws Exception {
        admin = tokenFor("infra", RoleCode.INFRASTRUCTURE_ADMIN);
        serverId = body(mvc.perform(post("/api/v1/servers").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "app-win-01", "hostname", "APP-WIN-01.mot.local", "osFamily", "WINDOWS",
                        "environment", "PRODUCTION", "organization", "Ministry of Finance"))))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    private Map<String, Object> windowsReport() {
        Map<String, Object> r = report(Instant.now(), 30.0, 1000.0);
        r.put("host", Map.of("hostname", "APP-WIN-01", "platform", "WINDOWS", "osName", "Microsoft Windows Server 2022 Standard",
                "osVersion", "10.0.20348", "kernel", "build 20348", "architecture", "64-bit", "uptimeSeconds", 86400, "cpuCount", 8));
        Map<String, Object> cpu = new HashMap<>();
        cpu.put("usagePercent", 30.0);
        cpu.put("runQueue", 2); // no load average on Windows
        r.put("cpu", cpu);
        r.put("disks", List.of(Map.of("mount", "C:\\", "device", "C:", "fsType", "NTFS", "totalBytes", 100, "usedBytes", 70,
                "freeBytes", 30, "usedPercent", 70.0)));
        r.put("diskIo", List.of(Map.of("device", "0_C:", "readBytesPerSec", 10.0)));
        r.put("network", List.of(
                Map.of("name", "vEthernet (Default Switch)", "operState", "up", "rxBitsPerSec", 800.0, "addresses", List.of("172.20.0.1/20")),
                Map.of("name", "Local Area Connection* 1", "operState", "down")));
        r.put("services", List.of(Map.of("name", "MSSQL$SQLEXPRESS", "status", "RUNNING", "loadState", "Auto", "activeState", "Running")));
        return r;
    }

    @Test
    void windowsReportWithWindowsStyleNamesIsStored() throws Exception {
        String agent = enroll(admin, serverId, "APP-WIN-01");
        mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(windowsReport()))).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(true));
        mvc.perform(get("/api/v1/servers/" + serverId + "/metrics").header("Authorization", admin)
                .param("metric", "net.rx_bps", "disk.used_pct"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series[0].resource").value("C:\\"))
                .andExpect(jsonPath("$.series[1].resource").value("vEthernet (Default Switch)"));
        mvc.perform(get("/api/v1/servers/" + serverId + "/metrics/latest").header("Authorization", admin))
                .andExpect(jsonPath("$.report.host.platform").value("WINDOWS"))
                .andExpect(jsonPath("$.report.cpu.load1").doesNotExist())
                .andExpect(jsonPath("$.report.cpu.runQueue").value(2));
        mvc.perform(get("/api/v1/servers/" + serverId + "/agent").header("Authorization", admin))
                .andExpect(jsonPath("$.platform").value("WINDOWS"))
                .andExpect(jsonPath("$.platformMismatch").value(false));
    }

    @Test
    void controlCharactersInNamesAreStillRejected() throws Exception {
        String agent = enroll(admin, serverId, "APP-WIN-01");
        Map<String, Object> r = windowsReport();
        r.put("network", List.of(Map.of("name", "evil\nname", "operState", "up")));
        mvc.perform(post("/api/v1/agent/report").header("Authorization", agent).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(r))).andExpect(status().isBadRequest());
    }

    @Test
    void windowsServiceNamesCanBeConfigured() throws Exception {
        mvc.perform(put("/api/v1/servers/" + serverId + "/agent/config").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reportIntervalSeconds", 30, "services", List.of("W3SVC", "MSSQL$SQLEXPRESS")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.config.services.length()").value(2));
        mvc.perform(put("/api/v1/servers/" + serverId + "/agent/config").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("reportIntervalSeconds", 30, "services", List.of("W3SVC'; Stop-Service *")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void linuxAgentOnWindowsServerIsFlagged() throws Exception {
        String token = createToken(admin, serverId);
        mvc.perform(post("/api/v1/agent/enroll").contentType(MediaType.APPLICATION_JSON)
                .content(toJson(enrollBody(token, "APP-WIN-01")))).andExpect(status().isOk()); // enrollBody sends LINUX
        mvc.perform(get("/api/v1/servers/" + serverId + "/agent").header("Authorization", admin))
                .andExpect(jsonPath("$.platformMismatch").value(true));
    }
}
