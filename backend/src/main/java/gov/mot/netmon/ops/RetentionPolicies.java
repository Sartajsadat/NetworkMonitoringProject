package gov.mot.netmon.ops;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads the current retention policy values (used by other modules, e.g. capture limits). */
@Component
public class RetentionPolicies {

    private final JdbcTemplate jdbc;

    public RetentionPolicies(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int days(String type) {
        Integer d = jdbc.queryForObject("SELECT retention_days FROM retention_policies WHERE data_type = ?", Integer.class, type);
        if (d == null) throw new IllegalStateException("No retention policy " + type);
        return d;
    }
}
