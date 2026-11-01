package gov.mot.netmon.agent.windows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/** State of the Windows services on the platform's allowlist (Win32_Service). Read-only. */
public class WinServiceCollector implements Collector {

    private final Supplier<List<String>> wanted;

    public WinServiceCollector(Supplier<List<String>> wanted) {
        this.wanted = wanted;
    }

    @Override
    public String name() {
        return "services";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        List<String> names = PowerShellQuery.safeServices(wanted.get());
        Map<String, JsonNode> found = new HashMap<>();
        List<JsonNode> rows = WinData.section(b, "services"); // also fails honestly when the query did not run
        if (!names.isEmpty()) {
            for (JsonNode r : rows) {
                String n = WinData.str(r, "Name");
                if (n != null) found.put(n.toLowerCase(Locale.ROOT), r);
            }
        }
        List<Report.Service> out = new ArrayList<>();
        for (String name : names) {
            JsonNode r = found.get(name.toLowerCase(Locale.ROOT)); // Windows service names are case-insensitive
            if (r == null) {
                out.add(new Report.Service(name, "NOT_FOUND", null, null, null, null));
                continue;
            }
            String state = WinData.str(r, "State");
            out.add(new Report.Service(name, status(state), WinData.str(r, "StartMode"), state, WinData.str(r, "Status"), null));
        }
        b.services = out;
    }

    static String status(String state) {
        if (state == null) return "UNKNOWN";
        return switch (state) {
            case "Running" -> "RUNNING";
            case "Stopped" -> "STOPPED";
            case "Start Pending", "Continue Pending" -> "STARTING";
            case "Stop Pending", "Pause Pending" -> "STOPPING";
            default -> "UNKNOWN"; // Paused, Unknown
        };
    }
}
