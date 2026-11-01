package gov.mot.netmon.agent.linux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.CommandRunner;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/**
 * State of the systemd units on the platform's allowlist, read with
 * {@code systemctl show <unit> --property=...}. Read-only: the agent cannot start, stop or restart services.
 */
public class ServiceCollector implements Collector {

    static final Pattern UNIT = Pattern.compile("^[A-Za-z0-9@._:-]{1,128}$");
    private static final String PROPERTIES = "--property=LoadState,ActiveState,SubState,StateChangeTimestampMonotonic";

    private final CommandRunner systemctl;
    private final Supplier<List<String>> units;

    public ServiceCollector(CommandRunner systemctl, Supplier<List<String>> units) {
        this.systemctl = systemctl;
        this.units = units;
    }

    @Override
    public String name() {
        return "services";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        List<Report.Service> out = new ArrayList<>();
        for (String unit : units.get()) {
            if (out.size() >= 100) break;
            if (!UNIT.matcher(unit).matches()) {
                continue; // never pass anything unexpected to systemctl
            }
            CommandRunner.Result r = systemctl.run(List.of("show", unit, PROPERTIES, "--no-pager"));
            if (r.exitCode() != 0) {
                out.add(new Report.Service(unit, "UNKNOWN", null, null, null, null));
                continue;
            }
            Map<String, String> props = parse(r.stdout());
            Instant since = null;
            String mono = props.get("StateChangeTimestampMonotonic");
            if (b.bootTime != null && mono != null && mono.matches("\\d+") && !mono.equals("0")) {
                since = b.bootTime.plusNanos(Long.parseLong(mono) * 1000);
            }
            out.add(new Report.Service(unit, status(props.get("LoadState"), props.get("ActiveState")),
                    props.get("LoadState"), props.get("ActiveState"), props.get("SubState"), since));
        }
        b.services = out;
    }

    static Map<String, String> parse(String out) {
        Map<String, String> m = new HashMap<>();
        for (String line : out.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) m.put(line.substring(0, eq), line.substring(eq + 1).trim());
        }
        return m;
    }

    static String status(String loadState, String activeState) {
        if ("not-found".equals(loadState)) return "NOT_FOUND";
        if (activeState == null) return "UNKNOWN";
        return switch (activeState) {
            case "active", "reloading" -> "RUNNING";
            case "inactive" -> "STOPPED";
            case "failed" -> "FAILED";
            case "activating" -> "STARTING";
            case "deactivating" -> "STOPPING";
            default -> "UNKNOWN";
        };
    }
}
