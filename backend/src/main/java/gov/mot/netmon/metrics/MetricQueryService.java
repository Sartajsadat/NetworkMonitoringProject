package gov.mot.netmon.metrics;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.AgentProtocol.Report;
import gov.mot.netmon.agent.ServerAgentConfigRepository;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.metrics.MetricDtos.FleetEntry;
import gov.mot.netmon.metrics.MetricDtos.History;
import gov.mot.netmon.metrics.MetricDtos.Latest;
import gov.mot.netmon.metrics.MetricDtos.Point;
import gov.mot.netmon.metrics.MetricDtos.Series;
import gov.mot.netmon.metrics.MetricDtos.Slice;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerEnums.AgentHealth;
import gov.mot.netmon.server.ServerEnums.ConnectionStatus;
import gov.mot.netmon.server.ServerRepository;

@Service
public class MetricQueryService {

    /** Ranges up to this length use raw samples; longer ranges use 5-minute rollups. */
    static final Duration RAW_MAX_RANGE = Duration.ofHours(24);
    static final Duration MAX_RANGE = Duration.ofDays(90);
    static final int MAX_METRICS_PER_QUERY = 12;
    static final int MAX_ROWS = 100_000;

    private final JdbcTemplate jdbc;
    private final ServerRepository servers;
    private final ServerAgentConfigRepository configs;
    private final SnapshotStore snapshots;
    private final Clock clock;
    private final Duration onlineThreshold;
    private final Duration rawRetention;
    private final int defaultInterval;

    public MetricQueryService(JdbcTemplate jdbc, ServerRepository servers, ServerAgentConfigRepository configs,
            SnapshotStore snapshots, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.servers = servers;
        this.configs = configs;
        this.snapshots = snapshots;
        this.clock = clock;
        this.onlineThreshold = props.monitoring().onlineThreshold();
        this.rawRetention = props.monitoring().rawRetention();
        this.defaultInterval = props.agents().defaultReportInterval();
    }

    @Transactional(readOnly = true)
    public History history(UUID serverId, List<String> metrics, String resource, Instant from, Instant to) {
        servers.findById(serverId).orElseThrow(() -> ApiException.notFound("Server"));
        Instant now = clock.instant();
        Instant end = to == null ? now : to;
        Instant start = from == null ? end.minus(Duration.ofHours(1)) : from;
        if (!start.isBefore(end)) {
            throw ApiException.badRequest("INVALID_RANGE", "'from' must be before 'to'");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("RANGE_TOO_LONG", "The time range can be at most " + MAX_RANGE.toDays() + " days");
        }
        if (metrics == null || metrics.isEmpty() || metrics.size() > MAX_METRICS_PER_QUERY) {
            throw ApiException.badRequest("INVALID_METRIC", "Request between 1 and " + MAX_METRICS_PER_QUERY + " metrics");
        }
        for (String m : metrics) {
            if (!MetricNames.UNITS.containsKey(m)) {
                throw ApiException.badRequest("INVALID_METRIC", "Unknown metric '" + m + "'");
            }
        }
        boolean raw = Duration.between(start, end).compareTo(RAW_MAX_RANGE) <= 0 && !start.isBefore(now.minus(rawRetention));
        String placeholders = String.join(",", Collections.nCopies(metrics.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(serverId);
        args.addAll(metrics);
        args.add(Timestamp.from(start));
        args.add(Timestamp.from(end));
        String resourceFilter = "";
        if (resource != null && !resource.isBlank()) {
            resourceFilter = " AND resource = ?";
            args.add(resource);
        }
        Map<String, List<Point>> grouped = new LinkedHashMap<>();
        if (raw) {
            jdbc.query("SELECT metric, resource, collected_at, value FROM metric_samples WHERE server_id = ? AND metric IN ("
                    + placeholders + ") AND collected_at >= ? AND collected_at < ?" + resourceFilter
                    + " ORDER BY metric, resource, collected_at LIMIT " + MAX_ROWS,
                    rs -> {
                        grouped.computeIfAbsent(rs.getString(1) + "\u0000" + rs.getString(2), k -> new ArrayList<>())
                                .add(new Point(rs.getTimestamp(3).toInstant(), rs.getDouble(4), null, null));
                    }, args.toArray());
        } else {
            jdbc.query("SELECT metric, resource, bucket_start, avg_value, min_value, max_value FROM metric_rollups_5m "
                    + "WHERE server_id = ? AND metric IN (" + placeholders + ") AND bucket_start >= ? AND bucket_start < ?"
                    + resourceFilter + " ORDER BY metric, resource, bucket_start LIMIT " + MAX_ROWS,
                    rs -> {
                        grouped.computeIfAbsent(rs.getString(1) + "\u0000" + rs.getString(2), k -> new ArrayList<>())
                                .add(new Point(rs.getTimestamp(3).toInstant(), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)));
                    }, args.toArray());
        }
        List<Series> series = grouped.entrySet().stream().map(e -> {
            String[] key = e.getKey().split("\u0000", -1);
            return new Series(key[0], key[1], MetricNames.UNITS.get(key[0]), e.getValue());
        }).toList();
        int interval = raw ? configs.findById(serverId).map(c -> c.getReportIntervalSeconds()).orElse(defaultInterval) : 300;
        return new History(serverId, start, end, raw ? "RAW" : "5m", interval, series);
    }

    @Transactional(readOnly = true)
    public Latest latest(UUID serverId) {
        Server server = servers.findById(serverId).orElseThrow(() -> ApiException.notFound("Server"));
        Instant now = clock.instant();
        ConnectionStatus connection = server.connectionStatus(now, onlineThreshold);
        Optional<SnapshotStore.Snapshot> snap = snapshots.find(serverId);
        if (snap.isEmpty()) {
            String reason = server.getAgentHealth() == AgentHealth.NO_AGENT ? "NO_AGENT" : "NO_DATA";
            return new Latest(serverId, false, reason, connection, null, null, null, false, null);
        }
        SnapshotStore.Snapshot s = snap.get();
        long age = Math.max(0, Duration.between(s.collectedAt(), now).toSeconds());
        return new Latest(serverId, true, null, connection, s.collectedAt(), s.receivedAt(), age,
                age > onlineThreshold.toSeconds(), s.report());
    }

    @Transactional(readOnly = true)
    public <T> Slice<T> slice(UUID serverId, Function<Report, List<T>> extractor) {
        Latest l = latest(serverId);
        if (!l.available()) {
            return new Slice<>(serverId, false, null, false, List.of());
        }
        List<T> items = extractor.apply(l.report());
        return new Slice<>(serverId, true, l.collectedAt(), l.stale(), items == null ? List.of() : items);
    }

    /** Latest values for every server that has reported at least once. */
    @Transactional(readOnly = true)
    public List<FleetEntry> fleet() {
        Instant now = clock.instant();
        Map<UUID, SnapshotStore.Snapshot> all = snapshots.findAll();
        return servers.findAll().stream()
                .filter(s -> all.containsKey(s.getId()))
                .sorted(Comparator.comparing(s -> s.getName().toLowerCase()))
                .map(s -> {
                    SnapshotStore.Snapshot snap = all.get(s.getId());
                    Report r = snap.report();
                    boolean stale = Duration.between(snap.collectedAt(), now).compareTo(onlineThreshold) > 0;
                    return new FleetEntry(s.getId(), s.getName(), s.getHostname(), s.getOsFamily(), s.getEnvironment(),
                            s.getOrganization(), s.connectionStatus(now, onlineThreshold), snap.collectedAt(), stale,
                            r.host(), r.cpu(), r.memory(), r.disks(), r.diskIo(), r.network(), r.services(), r.collectors());
                }).toList();
    }
}
