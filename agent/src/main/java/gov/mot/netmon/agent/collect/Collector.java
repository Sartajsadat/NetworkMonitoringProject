package gov.mot.netmon.agent.collect;

import java.time.Instant;

/**
 * One source of telemetry. Collectors keep their own previous counters to compute rates;
 * the first call therefore reports totals but no rates (null).
 */
public interface Collector {

    String name();

    void collect(ReportBuilder report, Instant now) throws Exception;
}
