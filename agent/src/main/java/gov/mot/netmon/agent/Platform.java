package gov.mot.netmon.agent;

import java.util.List;
import java.util.function.Supplier;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.config.AgentSettings;
import gov.mot.netmon.agent.model.Report;

/**
 * Operating-system specific part of the agent. Everything else (protocol, buffering, credentials,
 * configuration) is shared, so Linux and Windows servers report in exactly the same format.
 */
public interface Platform {

    /** LINUX or WINDOWS, as sent to the platform. */
    String name();

    List<Collector> collectors(AgentSettings settings, Supplier<List<String>> monitoredServices);

    /** Host facts sent with the enrollment request. */
    Report.Host hostInfo(AgentSettings settings) throws Exception;

    /** Hashed machine identifier (never the raw id), or null. */
    String machineIdHash(AgentSettings settings);

    static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) return new LinuxPlatform();
        if (os.contains("windows")) return new WindowsPlatform();
        return null;
    }

    static String hash(String id) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(("netmon:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (Exception e) {
            return null;
        }
    }
}
