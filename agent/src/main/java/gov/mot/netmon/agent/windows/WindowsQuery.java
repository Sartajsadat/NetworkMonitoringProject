package gov.mot.netmon.agent.windows;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Source of one cycle of raw Windows data (real implementation: {@link PowerShellQuery}; tests: fixtures). */
public interface WindowsQuery {

    JsonNode run(List<String> services) throws Exception;
}
