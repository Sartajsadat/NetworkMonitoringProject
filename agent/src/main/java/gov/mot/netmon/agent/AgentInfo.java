package gov.mot.netmon.agent;

import java.io.InputStream;
import java.util.Properties;

public final class AgentInfo {

    public static final String VERSION = load();

    private AgentInfo() {
    }

    private static String load() {
        try (InputStream in = AgentInfo.class.getResourceAsStream("/netmon-agent.properties")) {
            Properties p = new Properties();
            if (in != null) p.load(in);
            return p.getProperty("agent.version", "dev");
        } catch (Exception e) {
            return "dev";
        }
    }
}
