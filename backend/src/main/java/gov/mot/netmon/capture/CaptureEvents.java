package gov.mot.netmon.capture;

import org.springframework.stereotype.Component;

import gov.mot.netmon.event.EventCategory;
import gov.mot.netmon.event.EventService;
import gov.mot.netmon.event.EventSeverity;
import gov.mot.netmon.server.ServerRepository;

/** Puts the capture lifecycle on the event timeline (correlated by the capture id). */
@Component
public class CaptureEvents {

    private final EventService events;
    private final ServerRepository servers;

    public CaptureEvents(EventService events, ServerRepository servers) {
        this.events = events;
        this.servers = servers;
    }

    private EventService.Builder base(PacketCapture c, String type, EventSeverity severity) {
        String server = servers.findById(c.getServerId()).map(s -> s.getName()).orElse(null);
        return events.event(EventCategory.CAPTURE, type, severity).server(c.getServerId(), server)
                .resource(c.getInterfaceName()).capture(c.getId()).detail("name", c.getName())
                .detail("interface", c.getInterfaceName()).detail("captureFilter", c.getCaptureFilter());
    }

    public void requested(PacketCapture c, String user) {
        base(c, "CAPTURE_REQUESTED", EventSeverity.INFO).at(c.getRequestedAt())
                .summary("Packet capture \"" + c.getName() + "\" requested by " + user).detail("by", user)
                .detail("reference", c.getReference()).record();
    }

    public void stopRequested(PacketCapture c, String user) {
        base(c, "CAPTURE_STOP_REQUESTED", EventSeverity.INFO)
                .summary("Stop of packet capture \"" + c.getName() + "\" requested by " + user).detail("by", user).record();
    }

    /** Called after every status transition that changed the status. */
    public void statusChanged(PacketCapture c, CaptureStatus from, CaptureStatus to) {
        switch (to) {
            case RUNNING -> base(c, "CAPTURE_STARTED", EventSeverity.INFO)
                    .at(c.getStartedAt() != null ? c.getStartedAt() : null)
                    .summary("Packet capture \"" + c.getName() + "\" started on " + c.getInterfaceName()).record();
            case COMPLETED -> base(c, "CAPTURE_COMPLETED", EventSeverity.INFO)
                    .summary("Packet capture \"" + c.getName() + "\" finished"
                            + (c.getAgentPackets() == null ? "" : " (" + c.getAgentPackets() + " packets)"))
                    .detail("packets", c.getAgentPackets()).detail("message", c.getStatusMessage()).record();
            case FAILED, UNSUPPORTED -> base(c, "CAPTURE_FAILED", EventSeverity.ERROR)
                    .summary("Packet capture \"" + c.getName() + "\" failed"
                            + (c.getStatusMessage() == null ? "" : ": " + c.getStatusMessage()))
                    .detail("status", to.name()).detail("message", c.getStatusMessage()).record();
            case STOPPED -> base(c, "CAPTURE_CANCELLED", EventSeverity.INFO)
                    .summary("Packet capture \"" + c.getName() + "\" cancelled before it started").record();
            default -> {
            }
        }
    }

    public void filesDeleted(PacketCapture c, String how, long bytes) {
        base(c, "CAPTURE_FILES_DELETED", EventSeverity.INFO)
                .summary("Files of packet capture \"" + c.getName() + "\" deleted (" + how + ")").detail("how", how)
                .detail("bytes", bytes).record();
    }
}
