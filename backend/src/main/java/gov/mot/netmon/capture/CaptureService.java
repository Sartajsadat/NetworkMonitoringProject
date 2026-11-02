package gov.mot.netmon.capture;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.AgentProtocol;
import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.capture.CaptureDtos.Capability;
import gov.mot.netmon.capture.CaptureDtos.CaptureView;
import gov.mot.netmon.capture.CaptureDtos.CreateCaptureRequest;
import gov.mot.netmon.capture.CaptureDtos.Limits;
import gov.mot.netmon.capture.CaptureDtos.PacketBytes;
import gov.mot.netmon.capture.CaptureDtos.PacketDetail;
import gov.mot.netmon.capture.CaptureDtos.PacketPage;
import gov.mot.netmon.capture.CaptureDtos.PacketRow;
import gov.mot.netmon.capture.CaptureDtos.PacketSearch;
import gov.mot.netmon.capture.CaptureDtos.Statistics;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.PageRequests;
import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.metrics.SnapshotStore;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.server.Server;
import gov.mot.netmon.server.ServerEnums.ConnectionStatus;
import gov.mot.netmon.server.ServerEnums.MonitoringStatus;
import gov.mot.netmon.server.ServerRepository;

/** Capture requests and analysis for signed-in users. Role checks are in {@link CaptureController}. */
@Service
public class CaptureService {

    /** BPF capture filters: printable ASCII only (dumpcap on the server does the real validation). */
    static final Pattern BPF_TEXT = Pattern.compile("^[\\x20-\\x7E]{0,512}$");
    static final int DISPLAY_FILTER_MAX_MATCHES = 50_000;
    static final Duration VIEW_AUDIT_INTERVAL = Duration.ofMinutes(30);

    private final PacketCaptureRepository captures;
    private final CaptureSegmentRepository segments;
    private final AgentTaskRepository tasks;
    private final ServerRepository servers;
    private final SnapshotStore snapshots;
    private final PacketStore packets;
    private final CaptureStorage storage;
    private final Tshark tshark;
    private final AuditService audit;
    private final Clock clock;
    private final AppProperties.Capture cfg;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private gov.mot.netmon.ops.RetentionPolicies retentionPolicies;
    private final Duration onlineThreshold;
    private final Map<String, Instant> lastViewAudit = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> filterCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Long>> e) {
            return size() > 20;
        }
    });

    private final CaptureEvents captureEvents;

    public CaptureService(PacketCaptureRepository captures, CaptureSegmentRepository segments, AgentTaskRepository tasks,
            ServerRepository servers, SnapshotStore snapshots, PacketStore packets, CaptureStorage storage, Tshark tshark,
            AuditService audit, Clock clock, AppProperties props, CaptureEvents captureEvents) {
        this.captureEvents = captureEvents;
        this.captures = captures;
        this.segments = segments;
        this.tasks = tasks;
        this.servers = servers;
        this.snapshots = snapshots;
        this.packets = packets;
        this.storage = storage;
        this.tshark = tshark;
        this.audit = audit;
        this.clock = clock;
        this.cfg = props.capture();
        this.onlineThreshold = props.monitoring().onlineThreshold();
    }

    // ---------------- capability & limits ----------------

    @Transactional(readOnly = true)
    public Limits limits() {
        return new Limits(cfg.maxDurationSeconds(), cfg.maxBytesPerCapture(), cfg.defaultSnaplen(), cfg.defaultRetentionDays(),
                maxRetentionDays(), cfg.quotaBytes(), captures.totalStoredBytes(), reservedBytes(), tshark.available());
    }

    @Transactional(readOnly = true)
    public List<Capability> capabilities() {
        Map<UUID, SnapshotStore.Snapshot> snaps = snapshots.findAll();
        Set<UUID> busy = captures.findByStatusIn(CaptureStatus.ACTIVE).stream().map(PacketCapture::getServerId)
                .collect(Collectors.toSet());
        return servers.findAll().stream()
                .filter(s -> s.getMonitoringStatus() != MonitoringStatus.DECOMMISSIONED)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(s -> capability(s, snaps.get(s.getId()), busy.contains(s.getId())))
                .toList();
    }

    Capability capability(Server s, SnapshotStore.Snapshot snap, boolean busy) {
        ConnectionStatus conn = s.connectionStatus(clock.instant(), onlineThreshold);
        AgentProtocol.Capture c = snap == null ? null : snap.report().capture();
        String state;
        String message = null;
        if (conn == ConnectionStatus.NO_AGENT) state = "NO_AGENT";
        else if (conn == ConnectionStatus.OFFLINE) state = "OFFLINE";
        else if (c == null || !c.enabled()) {
            state = "NOT_ENABLED";
            message = c == null ? null : c.message();
        } else if (!c.available()) {
            state = "UNAVAILABLE";
            message = c.message();
        } else state = "AVAILABLE";
        return new Capability(s.getId(), s.getName(), s.getHostname(), s.getOsFamily(), conn, state, message,
                CaptureDtos.interfaces(c), c == null ? null : c.maxDurationSeconds(), c == null ? null : c.maxBytes(), busy);
    }

    private long reservedBytes() {
        // Active captures may still grow up to their limit.
        return captures.findByStatusIn(CaptureStatus.ACTIVE).stream()
                .mapToLong(c -> Math.max(0, c.getMaxBytes() - c.getBytesStored())).sum();
    }

    // ---------------- lifecycle ----------------

    @Transactional
    public CaptureView create(CreateCaptureRequest req) {
        Server server = servers.findById(req.serverId()).orElseThrow(() -> ApiException.notFound("Server"));
        Capability cap = capability(server, snapshots.find(server.getId()).orElse(null),
                !captures.findByServerIdAndStatusIn(server.getId(), CaptureStatus.ACTIVE).isEmpty());
        if (!"AVAILABLE".equals(cap.state())) {
            throw ApiException.conflict("CAPTURE_NOT_AVAILABLE", switch (cap.state()) {
                case "NO_AGENT" -> "This server has no monitoring agent.";
                case "OFFLINE" -> "The server's agent is offline.";
                case "NOT_ENABLED" -> "Packet capture is not enabled on this server (an operator must enable it in the agent configuration).";
                default -> "Packet capture is not available on this server: " + cap.message();
            });
        }
        if (cap.busy()) throw ApiException.conflict("CAPTURE_BUSY", "Another capture is already active on this server");
        String iface = req.interfaceName().trim();
        if (cap.interfaces().stream().noneMatch(i -> i.name().equals(iface))) {
            throw ApiException.badRequest("INTERFACE_NOT_ALLOWED", "Interface is not on the server's capture allowlist");
        }
        String filter = req.captureFilter() == null ? null : req.captureFilter().trim();
        if (filter != null && filter.isEmpty()) filter = null;
        if (filter != null && !BPF_TEXT.matcher(filter).matches()) {
            throw ApiException.badRequest("INVALID_CAPTURE_FILTER", "Capture filter must be printable ASCII, at most 512 characters");
        }
        int maxDuration = Math.min(cfg.maxDurationSeconds(), cap.maxDurationSeconds() == null ? Integer.MAX_VALUE : cap.maxDurationSeconds());
        if (req.durationSeconds() > maxDuration) {
            throw ApiException.badRequest("DURATION_TOO_LONG", "Duration may be at most " + maxDuration + " seconds");
        }
        long maxBytes = req.maxMegabytes() * 1024L * 1024L;
        long allowedBytes = Math.min(cfg.maxBytesPerCapture(), cap.maxBytes() == null ? Long.MAX_VALUE : cap.maxBytes());
        if (maxBytes > allowedBytes) {
            throw ApiException.badRequest("SIZE_TOO_LARGE", "Maximum size may be at most " + allowedBytes / (1024 * 1024) + " MB");
        }
        int retention = req.retentionDays() == null ? cfg.defaultRetentionDays() : req.retentionDays();
        if (retention > maxRetentionDays()) {
            throw ApiException.badRequest("RETENTION_TOO_LONG", "Retention may be at most " + maxRetentionDays() + " days");
        }
        if (captures.totalStoredBytes() + reservedBytes() + maxBytes > cfg.quotaBytes()) {
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "CAPTURE_QUOTA_EXCEEDED",
                    "Not enough capture storage quota for this size. Delete old captures or lower the maximum size.");
        }
        Instant now = clock.instant();
        PacketCapture c = captures.save(new PacketCapture(server.getId(), req.name().trim(), blank(req.description()),
                blank(req.reference()), req.reason().trim(), iface, filter, req.snaplen() == null ? cfg.defaultSnaplen() : req.snaplen(),
                req.durationSeconds(), maxBytes, retention, CurrentUser.require().username(), now));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("interfaceName", iface);
        payload.put("captureFilter", filter);
        payload.put("snaplen", c.getSnaplen());
        payload.put("durationSeconds", c.getDurationSeconds());
        payload.put("maxBytes", c.getMaxBytes());
        tasks.save(new AgentTask(server.getId(), c.getId(), "START_CAPTURE", payload, now));
        audit.event(AuditAction.CAPTURE_REQUESTED).target("CAPTURE", c.getId()).reason(c.getReason())
                .detail("server", server.getName()).detail("interface", iface).detail("captureFilter", filter)
                .detail("durationSeconds", c.getDurationSeconds()).detail("maxBytes", c.getMaxBytes())
                .detail("snaplen", c.getSnaplen()).detail("reference", c.getReference()).success();
        captureEvents.requested(c, c.getRequestedBy());
        return view(c, server);
    }

    @Transactional
    public CaptureView stop(UUID id) {
        PacketCapture c = find(id);
        Instant now = clock.instant();
        if (!c.getStatus().isActive()) throw ApiException.conflict("CAPTURE_NOT_ACTIVE", "This capture is not running");
        String user = CurrentUser.require().username();
        c.requestStop(user, now);
        List<AgentTask> pendingStart = tasks.findByCaptureIdAndStatus(id, "PENDING");
        captureEvents.stopRequested(c, user);
        if (c.getStatus() == CaptureStatus.PENDING && !pendingStart.isEmpty()) {
            pendingStart.forEach(AgentTask::cancel); // the agent never saw it
            c.transition(CaptureStatus.STOPPED, "Stopped by " + user + " before the agent started it", now);
            captureEvents.statusChanged(c, CaptureStatus.PENDING, CaptureStatus.STOPPED);
        } else if (c.getStatus() != CaptureStatus.STOPPING) {
            c.transition(CaptureStatus.STOPPING, null, now);
            tasks.save(new AgentTask(c.getServerId(), c.getId(), "STOP_CAPTURE", Map.of(), now));
        }
        audit.event(AuditAction.CAPTURE_STOP_REQUESTED).target("CAPTURE", id).detail("name", c.getName()).success();
        return view(c, servers.findById(c.getServerId()).orElse(null));
    }

    // ---------------- queries ----------------

    public record ListFilter(UUID serverId, CaptureStatus status, String search) {
    }

    @Transactional(readOnly = true)
    public PageResponse<CaptureView> list(ListFilter f, int page, int size) {
        Specification<PacketCapture> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.serverId() != null) p.add(cb.equal(root.get("serverId"), f.serverId()));
            if (f.status() != null) p.add(cb.equal(root.get("status"), f.status()));
            if (f.search() != null && !f.search().isBlank()) {
                String like = "%" + f.search().trim().toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("name")), like), cb.like(cb.lower(root.get("reference")), like),
                        cb.like(cb.lower(root.get("requestedBy")), like)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        var pageable = PageRequests.of(page, size, null, Set.of(), Sort.by(Sort.Direction.DESC, "requestedAt"));
        var result = captures.findAll(spec, pageable);
        Map<UUID, Server> byId = servers.findAllById(result.getContent().stream().map(PacketCapture::getServerId).toList())
                .stream().collect(Collectors.toMap(Server::getId, Function.identity()));
        return PageResponse.of(result, c -> view(c, byId.get(c.getServerId())));
    }

    @Transactional(readOnly = true)
    public CaptureView get(UUID id) {
        PacketCapture c = find(id);
        auditView(c);
        return view(c, servers.findById(c.getServerId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public long activeCount() {
        return captures.countByStatusIn(CaptureStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public PacketPage packets(UUID id, PacketSearch s) {
        PacketCapture c = find(id);
        int page = s.page() == null ? 0 : s.page();
        int size = s.size() == null ? 100 : s.size();
        List<Long> frames = null;
        boolean truncated = false;
        String df = s.displayFilter() == null || s.displayFilter().isBlank() ? null : s.displayFilter().trim();
        if (df != null) {
            requireFiles(c);
            frames = framesMatching(c, df);
            truncated = frames.size() >= DISPLAY_FILTER_MAX_MATCHES;
        }
        PacketStore.Filter f = new PacketStore.Filter(s.src(), s.dst(), s.host(), s.protocol(), s.port(), s.minLength(),
                s.maxLength(), s.from(), s.to(), s.text(), frames);
        long total = packets.count(id, f);
        List<PacketRow> rows = packets.page(id, f, page, size, Boolean.TRUE.equals(s.descending()));
        return new PacketPage(rows, page, size, total, (int) Math.ceil(total / (double) size), df, truncated,
                c.getPacketsIndexed(), c.isIndexTruncated());
    }

    private List<Long> framesMatching(PacketCapture c, String filter) {
        String key = c.getId() + "|" + c.getSegmentsCount() + "|" + filter;
        List<Long> cached = filterCache.get(key);
        if (cached != null) return cached;
        String error = tshark.validateDisplayFilter(filter);
        if (error != null) throw ApiException.badRequest("INVALID_FILTER", error);
        List<Long> out = new ArrayList<>();
        for (CaptureSegment seg : segments.findByCaptureIdOrderBySeq(c.getId())) {
            if (!"DONE".equals(seg.getIndexStatus()) || out.size() >= DISPLAY_FILTER_MAX_MATCHES) continue;
            Path file = storage.segmentFile(c.getId(), seg.getFileName());
            for (int n : tshark.matchingFrames(file, filter, DISPLAY_FILTER_MAX_MATCHES - out.size())) {
                out.add(seg.getFirstFrame() + n - 1);
            }
        }
        filterCache.put(key, out);
        return out;
    }

    @Transactional(readOnly = true)
    public CaptureDtos.FilterCheck checkDisplayFilter(String filter) {
        String error = tshark.validateDisplayFilter(filter);
        return new CaptureDtos.FilterCheck(error == null, error);
    }

    @Transactional(readOnly = true)
    public PacketDetail detail(UUID id, long frameNo) {
        PacketCapture c = find(id);
        requireFiles(c);
        Located l = locate(c, frameNo);
        return new PacketDetail(packets.one(id, frameNo), PdmlTree.parse(tshark.pdml(l.file(), l.segmentFrame())));
    }

    @Transactional(readOnly = true)
    public PacketBytes bytes(UUID id, long frameNo) {
        PacketCapture c = find(id);
        requireFiles(c);
        Located l = locate(c, frameNo);
        audit.event(AuditAction.CAPTURE_PACKET_BYTES_VIEWED).target("CAPTURE", id).detail("frame", frameNo).success();
        return new PacketBytes(frameNo, tshark.hexDump(l.file(), l.segmentFrame()));
    }

    @Transactional(readOnly = true)
    public Statistics statistics(UUID id) {
        PacketCapture c = find(id);
        Map<String, Object> t = packets.totals(id);
        Instant first = toInstant(t.get("first"));
        Instant last = toInstant(t.get("last"));
        return new Statistics(num(t.get("packets")), num(t.get("bytes")), first, last, num(t.get("resets")),
                num(t.get("retransmissions")), num(t.get("syns")), packets.protocols(id), packets.topAddresses(id, "src"),
                packets.topAddresses(id, "dst"), packets.topPorts(id), packets.conversations(id),
                packets.timeline(id, first, last), packets.dnsQueries(id), c.isIndexTruncated());
    }

    /** Streams all segments as one PCAPNG file (PCAPNG allows several sections in one file). */
    @Transactional(readOnly = true)
    public PacketCapture prepareDownload(UUID id) {
        return prepareDownload(id, null, null);
    }

    /** Checks the files exist and audits the download (actor given explicitly for ticket downloads). */
    public PacketCapture prepareDownload(UUID id, UUID actorId, String actorUsername) {
        PacketCapture c = find(id);
        requireFiles(c);
        if (c.getSegmentsCount() == 0) throw ApiException.conflict("NO_DATA", "No packets have been received yet");
        var ev = audit.event(AuditAction.CAPTURE_DOWNLOADED);
        if (actorUsername != null) ev.actor(actorId, actorUsername).detail("via", "ticket");
        ev.target("CAPTURE", id).detail("name", c.getName()).detail("bytes", c.getBytesStored()).success();
        return c;
    }

    /** Validates that a download is possible before a ticket is handed out (not audited yet). */
    public void checkDownloadable(UUID id) {
        PacketCapture c = find(id);
        requireFiles(c);
        if (c.getSegmentsCount() == 0) throw ApiException.conflict("NO_DATA", "No packets have been received yet");
    }

    public void writeDownload(UUID id, OutputStream out) throws IOException {
        for (CaptureSegment seg : segments.findByCaptureIdOrderBySeq(id)) {
            Files.copy(storage.segmentFile(id, seg.getFileName()), out);
        }
    }

    @Transactional
    public CaptureView deleteFiles(UUID id, String reason) {
        PacketCapture c = find(id);
        if (c.getStatus().isActive()) throw ApiException.conflict("CAPTURE_ACTIVE", "Stop the capture first");
        if (c.getFilesDeletedAt() != null) throw ApiException.conflict("ALREADY_DELETED", "Files were already deleted");
        purge(c, "manual", reason);
        return view(c, servers.findById(c.getServerId()).orElse(null));
    }

    /** The lower of the deployment limit and the CAPTURES retention policy. */
    int maxRetentionDays() {
        if (retentionPolicies == null) return cfg.maxRetentionDays();
        try {
            return Math.min(cfg.maxRetentionDays(), retentionPolicies.days("CAPTURES"));
        } catch (RuntimeException e) {
            return cfg.maxRetentionDays();
        }
    }

    /** Deletes files and packet metadata; the capture record itself stays as history. */
    void purge(PacketCapture c, String how, String reason) {
        try {
            storage.deleteCapture(c.getId());
        } catch (IOException e) {
            throw new IllegalStateException("Could not delete capture files: " + e.getMessage(), e);
        }
        packets.deleteForCapture(c.getId());
        long bytes = c.getBytesStored();
        c.markFilesDeleted(clock.instant());
        var ev = audit.event(AuditAction.CAPTURE_FILES_DELETED).target("CAPTURE", c.getId()).detail("name", c.getName())
                .detail("bytes", bytes).detail("how", how).reason(reason);
        if (CurrentUser.usernameOrNull() == null) ev.actor(null, "system");
        ev.success();
        captureEvents.filesDeleted(c, how, bytes);
    }

    // ---------------- helpers ----------------

    record Located(Path file, int segmentFrame) {
    }

    private Located locate(PacketCapture c, long frameNo) {
        PacketStore.Location loc = packets.locate(c.getId(), frameNo);
        if (loc == null) throw ApiException.notFound("Packet");
        CaptureSegment seg = segments.findByCaptureIdAndSeq(c.getId(), loc.segmentSeq())
                .orElseThrow(() -> ApiException.notFound("Capture segment"));
        return new Located(storage.segmentFile(c.getId(), seg.getFileName()), loc.segmentFrame());
    }

    private void requireFiles(PacketCapture c) {
        if (c.getFilesDeletedAt() != null) {
            throw new ApiException(HttpStatus.GONE, "FILES_DELETED", "The capture files were deleted (retention or manual deletion)");
        }
    }

    private void auditView(PacketCapture c) {
        String user = CurrentUser.usernameOrNull();
        if (user == null) return;
        String key = user + "|" + c.getId();
        Instant now = clock.instant();
        Instant last = lastViewAudit.get(key);
        if (last == null || last.isBefore(now.minus(VIEW_AUDIT_INTERVAL))) {
            lastViewAudit.put(key, now);
            audit.event(AuditAction.CAPTURE_VIEWED).target("CAPTURE", c.getId()).detail("name", c.getName()).success();
        }
    }

    PacketCapture find(UUID id) {
        return captures.findById(id).orElseThrow(() -> ApiException.notFound("Capture"));
    }

    CaptureView view(PacketCapture c, Server s) {
        return new CaptureView(c.getId(), c.getServerId(), s == null ? null : s.getName(), s == null ? null : s.getHostname(),
                c.getName(), c.getDescription(), c.getReference(), c.getReason(), c.getInterfaceName(), c.getCaptureFilter(),
                c.getSnaplen(), c.getDurationSeconds(), c.getMaxBytes(), c.getRetentionDays(), c.getStatus(),
                c.getStatusMessage(), c.getRequestedBy(), c.getRequestedAt(), c.getStartedAt(), c.getEndedAt(),
                c.getStopRequestedBy(), c.getStopRequestedAt(), c.getAgentPackets(), c.getBytesStored(), c.getSegmentsCount(),
                c.getPacketsIndexed(), c.isIndexTruncated(), c.getExpiresAt(), c.getFilesDeletedAt(),
                c.getFilesDeletedAt() == null && c.getSegmentsCount() > 0);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static Instant toInstant(Object o) {
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return null;
    }

    Optional<PacketCapture> findOptional(UUID id) {
        return captures.findById(id);
    }
}
