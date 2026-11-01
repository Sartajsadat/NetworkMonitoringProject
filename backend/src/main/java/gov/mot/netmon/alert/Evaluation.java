package gov.mot.netmon.alert;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Result of evaluating one rule.
 * <ul>
 *   <li>{@code firing}: resources where the condition holds now;</li>
 *   <li>{@code checked}: servers (or the platform, {@link #PLATFORM}) whose data was fresh enough to
 *   decide. Open alerts on checked servers that are not firing are resolved; alerts on servers
 *   without fresh data stay as they are (missing data never resolves or opens anything).</li>
 * </ul>
 */
public final class Evaluation {

    public static final UUID PLATFORM = new UUID(0, 0);

    public record Hit(UUID serverId, String serverName, String resource, Double value, String condition) {
    }

    final Map<String, Hit> firing = new LinkedHashMap<>();
    final Set<UUID> checked = new LinkedHashSet<>();
    /** Latest value per key for checked, non-firing resources (used in resolution notes). */
    final Map<String, Double> values = new LinkedHashMap<>();

    static String key(AlertRule rule, UUID serverId, String resource) {
        return rule.getId() + "|" + (serverId == null ? "-" : serverId) + "|" + (resource == null ? "" : resource);
    }

    void fire(AlertRule rule, Hit hit) {
        firing.put(key(rule, hit.serverId(), hit.resource()), hit);
    }

    void value(AlertRule rule, UUID serverId, String resource, Double v) {
        values.put(key(rule, serverId, resource), v);
    }
}
