package gov.mot.netmon.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HostnameMismatchTest {

    @Test
    void comparesShortNamesCaseInsensitively() {
        assertThat(AgentEnrollmentService.hostnameMismatch("web-01.mot.local", "WEB-01")).isFalse();
        assertThat(AgentEnrollmentService.hostnameMismatch("web-01.mot.local", "web-01.mot.local")).isFalse();
        assertThat(AgentEnrollmentService.hostnameMismatch("web-01.mot.local", "db-02")).isTrue();
    }
}
