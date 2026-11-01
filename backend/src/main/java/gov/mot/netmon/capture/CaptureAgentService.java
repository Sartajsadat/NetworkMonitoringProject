package gov.mot.netmon.capture;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.agent.AgentPrincipal;
import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.capture.CaptureDtos.AgentStatusUpdate;
import gov.mot.netmon.capture.CaptureDtos.AgentTaskView;
import gov.mot.netmon.capture.CaptureDtos.AgentTasks;
import gov.mot.netmon.capture.CaptureDtos.SegmentAck;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.server.ServerRepository;

/** Capture endpoints called by agents: task delivery, status reports and segment uploads. */
@Service
public class CaptureAgentService {

    /** Tolerance above the requested maximum (the last piece may finish slightly over the limit). */
    static final long SIZE_TOLERANCE = 16L * 1024 * 1024;
    /** Largest single piece accepted (the agent rotates files at 32 MB; nginx allows 70 MB). */
    static final long MAX_SEGMENT_BYTES = 64L * 1024 * 1024;

    private final AgentTaskRepository tasks;
    private final PacketCaptureRepository captures;
    private final CaptureSegmentRepository segments;
    private final CaptureStorage storage;
    private final CaptureIndexer indexer;
    private final ServerRepository servers;
    private final AuditService audit;
    private final Clock clock;

    private final CaptureEvents captureEvents;

    public CaptureAgentService(AgentTaskRepository tasks, PacketCaptureRepository captures, CaptureSegmentRepository segments,
            CaptureStorage storage, CaptureIndexer indexer, ServerRepository servers, AuditService audit, Clock clock,
            CaptureEvents captureEvents) {
        this.captureEvents = captureEvents;
        this.tasks = tasks;
        this.captures = captures;
        this.segments = segments;
        this.storage = storage;
        this.indexer = indexer;
        this.servers = servers;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AgentTasks pollTasks(AgentPrincipal agent) {
        Instant now = clock.instant();
        List<AgentTask> pending = tasks.findByServerIdAndStatusOrderByCreatedAt(agent.serverId(), "PENDING");
        List<AgentTaskView> out = pending.stream().map(t -> {
            t.delivered(now);
            Map<String, Object> p = t.getPayload();
            return new AgentTaskView(t.getId(), t.getTaskType(), t.getCaptureId(), (String) p.get("interfaceName"),
                    (String) p.get("captureFilter"), asInt(p.get("snaplen")), asInt(p.get("durationSeconds")),
                    p.get("maxBytes") instanceof Number n ? n.longValue() : null);
        }).toList();
        return new AgentTasks(out);
    }

    @Transactional
    public SegmentAck status(AgentPrincipal agent, UUID captureId, AgentStatusUpdate update) {
        PacketCapture c = owned(agent, captureId);
        CaptureStatus next;
        try {
            next = CaptureStatus.valueOf(update.state());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_STATE", "Unknown capture state");
        }
        if (next == CaptureStatus.PENDING || next == CaptureStatus.STOPPED) {
            throw ApiException.badRequest("INVALID_STATE", "Agents cannot set this state");
        }
        // While a stop is pending, a RUNNING report must not hide the STOPPING state.
        if (next == CaptureStatus.RUNNING && c.getStatus() == CaptureStatus.STOPPING) next = CaptureStatus.STOPPING;
        CaptureStatus before = c.getStatus();
        c.setAgentPackets(update.packets());
        boolean changed = c.transition(next, update.message(), clock.instant());
        if (changed && before != c.getStatus()) captureEvents.statusChanged(c, before, c.getStatus());
        if (changed && before != next && (!next.isActive() || next == CaptureStatus.RUNNING)) {
            String server = servers.findById(agent.serverId()).map(s -> s.getName()).orElse("?");
            var ev = audit.event(AuditAction.CAPTURE_STATUS_CHANGED).actor(null, "agent@" + server).target("CAPTURE", captureId)
                    .detail("from", before.name()).detail("to", next.name()).detail("message", update.message())
                    .detail("packets", update.packets());
            if (next == CaptureStatus.FAILED) ev.failure(); else ev.success();
        }
        return ack(c, true);
    }

    @Transactional
    public SegmentAck upload(AgentPrincipal agent, UUID captureId, int seq, InputStream body) throws IOException {
        PacketCapture c = owned(agent, captureId);
        if (!c.getStatus().isActive()) {
            throw ApiException.conflict("CAPTURE_NOT_ACTIVE", "This capture no longer accepts data");
        }
        if (seq < 1 || seq > 100_000) throw ApiException.badRequest("INVALID_SEQ", "Invalid segment number");
        var existing = segments.findByCaptureIdAndSeq(captureId, seq);
        if (existing.isPresent()) {
            return ack(c, true); // retried upload of a piece we already have
        }
        long remaining = c.getMaxBytes() + SIZE_TOLERANCE - c.getBytesStored();
        if (remaining <= 0) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "CAPTURE_SIZE_LIMIT", "The capture reached its size limit");
        }
        CaptureStorage.Stored stored = storage.storeSegment(captureId, seq, body, Math.min(remaining, MAX_SEGMENT_BYTES));
        Instant now = clock.instant();
        segments.save(new CaptureSegment(captureId, seq, stored.fileName(), stored.size(), stored.sha256(), now));
        c.addSegment(stored.size(), now);
        if (c.getStatus() == CaptureStatus.STARTING) {
            c.transition(CaptureStatus.RUNNING, null, now);
            captureEvents.statusChanged(c, CaptureStatus.STARTING, CaptureStatus.RUNNING);
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        indexer.schedule(captureId);
                    }
                });
        return ack(c, true);
    }

    private SegmentAck ack(PacketCapture c, boolean accepted) {
        boolean stop = c.getStatus() == CaptureStatus.STOPPING || c.getStopRequestedAt() != null;
        return new SegmentAck(accepted, stop, c.getBytesStored(), c.getMaxBytes());
    }

    private PacketCapture owned(AgentPrincipal agent, UUID captureId) {
        PacketCapture c = captures.findById(captureId).orElseThrow(() -> ApiException.notFound("Capture"));
        if (!c.getServerId().equals(agent.serverId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "This capture belongs to another server");
        }
        return c;
    }

    private static Integer asInt(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }
}
