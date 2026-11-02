package gov.mot.netmon.flow;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import gov.mot.netmon.common.Cidr;

/** Registered subnets in memory, so each flow address can be checked without a query. */
@Component
public class SubnetIndex {

    private final JdbcTemplate jdbc;
    private volatile List<Cidr> subnets = List.of();

    public SubnetIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT0S")
    public void reload() {
        List<Cidr> l = new ArrayList<>();
        jdbc.query("SELECT cidr FROM subnets", rs -> {
            l.add(Cidr.parse(rs.getString(1)));
        });
        subnets = l;
    }

    public boolean inRegisteredSubnet(String canonical) {
        for (Cidr c : subnets) {
            try {
                if (c.contains(canonical)) return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return false;
    }
}
