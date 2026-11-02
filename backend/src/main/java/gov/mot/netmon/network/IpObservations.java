package gov.mot.netmon.network;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import gov.mot.netmon.common.IpAddresses;
import gov.mot.netmon.event.EventCategory;
import gov.mot.netmon.event.EventService;
import gov.mot.netmon.event.EventSeverity;

/**
 * Records that an address exists (and, for evidence sources, that it was seen in use). Writes are
 * throttled: the same address from the same source is written at most every few minutes unless its
 * MAC address changed. A MAC change on a known address is put on the timeline (it can mean a
 * replaced machine - or ARP spoofing).
 */
@Component
public class IpObservations {

    private static final Logger log = LoggerFactory.getLogger(IpObservations.class);
    static final Duration THROTTLE = Duration.ofMinutes(5);
    private static final Pattern MAC = Pattern.compile("^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$");

    private final JdbcTemplate jdbc;
    private final EventService events;
    private final Clock clock;
    private final Map<String, Object[]> recent = new ConcurrentHashMap<>(); // key -> [Instant written, mac]

    public IpObservations(JdbcTemplate jdbc, EventService events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    /** Normalises a MAC to aa:bb:cc:dd:ee:ff; returns null for missing, invalid or all-zero values. */
    static String mac(String raw) {
        if (raw == null) return null;
        String m = raw.trim().toLowerCase(Locale.ROOT).replace('-', ':');
        if (!MAC.matcher(m).matches() || m.equals("00:00:00:00:00:00") || m.equals("ff:ff:ff:ff:ff:ff")) return null;
        return m;
    }

    /** Addresses never worth recording (loopback, link-local, unspecified, multicast). */
    static boolean ignorable(String canonical, int version) {
        if (version == 4) {
            return canonical.startsWith("127.") || canonical.startsWith("169.254.") || canonical.equals("0.0.0.0")
                    || Integer.parseInt(canonical.substring(0, canonical.indexOf('.'))) >= 224;
        }
        return canonical.equals("::1") || canonical.equals("::") || canonical.startsWith("fe80:") || canonical.startsWith("ff");
    }

    public record Observation(String address, IpSource source, UUID serverId, UUID deviceId, String mac, String hostname,
            Instant seenAt) {
    }

    public void observeAll(List<Observation> list) {
        for (Observation o : list) observe(o);
    }

    public void observe(Observation o) {
        IpAddresses.ParsedIp ip;
        try {
            ip = IpAddresses.parse(o.address());
        } catch (IllegalArgumentException e) {
            return;
        }
        if (ignorable(ip.canonical(), ip.version())) return;
        String mac = mac(o.mac());
        Instant now = clock.instant();
        String key = ip.canonical() + "|" + o.source() + "|" + o.serverId();
        Object[] last = recent.get(key);
        if (last != null && ((Instant) last[0]).isAfter(now.minus(THROTTLE)) && java.util.Objects.equals(last[1], mac)) return;
        if (recent.size() > 200_000) recent.clear();
        recent.put(key, new Object[] {now, mac});
        try {
            write(ip, o, mac);
        } catch (RuntimeException e) {
            log.warn("Could not record address {}: {}", ip.canonical(), e.toString());
        }
    }

    private void write(IpAddresses.ParsedIp ip, Observation o, String mac) {
        List<Map<String, Object>> before = jdbc.queryForList(
                "SELECT mac_address, hostname FROM ip_addresses WHERE address = ?", ip.canonical());
        Timestamp seen = o.source().evidenceOfUse && o.seenAt() != null ? Timestamp.from(o.seenAt()) : null;
        Boolean inserted = jdbc.queryForObject("""
                INSERT INTO ip_addresses (id, address, ip_version, subnet_id, server_id, device_id, mac_address, hostname, sources,
                    first_seen_at, last_seen_at, last_seen_source, created_at, updated_at)
                VALUES (?, ?, ?, (SELECT s.id FROM subnets s WHERE ?::inet <<= s.cidr::inet ORDER BY s.prefix DESC LIMIT 1),
                    ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (address) DO UPDATE SET
                    server_id = COALESCE(EXCLUDED.server_id, ip_addresses.server_id),
                    device_id = COALESCE(EXCLUDED.device_id, ip_addresses.device_id),
                    mac_address = COALESCE(EXCLUDED.mac_address, ip_addresses.mac_address),
                    hostname = COALESCE(ip_addresses.hostname, EXCLUDED.hostname),
                    sources = CASE WHEN (',' || ip_addresses.sources || ',') LIKE ('%,' || EXCLUDED.sources || ',%') THEN ip_addresses.sources
                                   WHEN ip_addresses.sources = '' THEN EXCLUDED.sources
                                   ELSE ip_addresses.sources || ',' || EXCLUDED.sources END,
                    first_seen_at = COALESCE(ip_addresses.first_seen_at, EXCLUDED.first_seen_at),
                    last_seen_at = GREATEST(ip_addresses.last_seen_at, EXCLUDED.last_seen_at),
                    last_seen_source = CASE WHEN EXCLUDED.last_seen_at IS NOT NULL
                            AND (ip_addresses.last_seen_at IS NULL OR EXCLUDED.last_seen_at >= ip_addresses.last_seen_at)
                        THEN EXCLUDED.last_seen_source ELSE ip_addresses.last_seen_source END,
                    updated_at = now()
                RETURNING (xmax = 0)""", Boolean.class,
                UUID.randomUUID(), ip.canonical(), ip.version(), ip.canonical(), o.serverId(), o.deviceId(), mac,
                o.hostname() == null || o.hostname().isBlank() ? null : o.hostname().trim(), o.source().name(), seen, seen,
                seen == null ? null : o.source().name());
        String oldMac = before.isEmpty() ? null : (String) before.get(0).get("mac_address");
        if (oldMac != null && mac != null && !oldMac.equals(mac)) {
            events.event(EventCategory.NETWORK, "IP_MAC_CHANGED", EventSeverity.WARNING).ip(ip.canonical())
                    .server(o.serverId(), null).resource(ip.canonical()).at(o.seenAt())
                    .summary("Address " + ip.canonical() + " now answers from MAC " + mac + " (was " + oldMac + ")")
                    .detail("address", ip.canonical()).detail("oldMac", oldMac).detail("newMac", mac)
                    .detail("source", o.source().name()).record();
        } else if (Boolean.TRUE.equals(inserted) && o.source() == IpSource.NEIGHBOR) {
            events.event(EventCategory.NETWORK, "NEW_ADDRESS_SEEN", EventSeverity.INFO).ip(ip.canonical())
                    .server(o.serverId(), null).resource(ip.canonical()).at(o.seenAt())
                    .summary("New address " + ip.canonical() + " seen in a server's neighbour table" + (mac == null ? "" : " (MAC " + mac + ")"))
                    .detail("address", ip.canonical()).detail("mac", mac).record();
        }
    }

    /** After subnets change: put every address into its most specific subnet. */
    public int reassignSubnets() {
        return jdbc.update("""
                UPDATE ip_addresses i SET subnet_id = (SELECT s.id FROM subnets s WHERE i.address::inet <<= s.cidr::inet
                    ORDER BY s.prefix DESC LIMIT 1)""");
    }
}
