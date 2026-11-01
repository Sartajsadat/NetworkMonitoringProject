package gov.mot.netmon.agent.windows;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.ReportBuilder;

/** Runs the single Windows query of this cycle; the other Windows collectors read its result. */
public class WindowsQueryCollector implements Collector {

    private final WindowsQuery query;
    private final Supplier<List<String>> services;

    public WindowsQueryCollector(WindowsQuery query, Supplier<List<String>> services) {
        this.query = query;
        this.services = services;
    }

    @Override
    public String name() {
        return "windows-query";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        b.platformData = query.run(services.get());
    }
}
