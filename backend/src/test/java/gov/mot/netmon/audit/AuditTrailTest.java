package gov.mot.netmon.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import gov.mot.netmon.support.IntegrationTest;
import gov.mot.netmon.user.RoleCode;

class AuditTrailTest extends IntegrationTest {

    @Test
    void auditRowsCannotBeModifiedOrDeleted() {
        jdbc.update("INSERT INTO audit_logs (occurred_at, action, result) VALUES (now(), 'TEST_EVENT', 'SUCCESS')");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET action='X' WHERE action='TEST_EVENT'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE action='TEST_EVENT'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_logs")).hasMessageContaining("append-only");
    }

    @Test
    void auditSearchFiltersByActionAndActor() throws Exception {
        String token = tokenFor("auditor", RoleCode.SECURITY_ANALYST);
        mvc.perform(get("/api/v1/audit-logs?action=AUTH_LOGIN&actor=auditor").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("AUTH_LOGIN"))
                .andExpect(jsonPath("$.content[0].actorUsername").value("auditor"))
                .andExpect(jsonPath("$.content[0].sourceIp").value("127.0.0.1"))
                .andExpect(jsonPath("$.content[0].requestId").isNotEmpty());
        mvc.perform(get("/api/v1/audit-logs?from=2030-01-02T00:00:00Z&to=2030-01-01T00:00:00Z")
                .header("Authorization", token)).andExpect(status().isBadRequest());
    }

    @Test
    void auditNeverContainsPasswords() throws Exception {
        tokenFor("secretive", RoleCode.READ_ONLY);
        Long leaks = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE details::text ILIKE ?", Long.class,
                "%" + PASSWORD + "%");
        assertThat(leaks).isZero();
    }
}
