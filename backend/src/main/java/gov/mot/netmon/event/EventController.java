package gov.mot.netmon.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.event.EventDtos.EventContext;
import gov.mot.netmon.event.EventDtos.EventFilter;
import gov.mot.netmon.event.EventDtos.EventView;
import gov.mot.netmon.event.EventDtos.Histogram;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Event timeline. Readable by every signed-in user; security and capture events only for their roles. */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events")
public class EventController {

    private final EventQueryService service;

    public EventController(EventQueryService service) {
        this.service = service;
    }

    private static EventFilter filter(Instant from, Instant to, UUID serverId, String ip, List<EventCategory> category,
            EventSeverity minSeverity, String service, String alertStatus, String text, UUID correlationId, String type) {
        return new EventFilter(from, to, serverId, trim(ip, 45), category, minSeverity, trim(service, 255),
                trim(alertStatus, 20), trim(text, 200), correlationId, trim(type, 40));
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    @GetMapping
    @Operation(summary = "Search the event timeline")
    public PageResponse<EventView> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID serverId, @RequestParam(required = false) String ip,
            @RequestParam(required = false) List<EventCategory> category,
            @RequestParam(required = false) EventSeverity minSeverity, @RequestParam(required = false) String service,
            @RequestParam(required = false) String alertStatus, @RequestParam(required = false) String text,
            @RequestParam(required = false) UUID correlationId, @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "desc") String order) {
        return this.service.search(filter(from, to, serverId, ip, category, minSeverity, service, alertStatus, text,
                correlationId, type), page, size, "asc".equalsIgnoreCase(order));
    }

    @GetMapping("/histogram")
    @Operation(summary = "Event counts over time (by severity) and totals by category, for the same filters")
    public Histogram histogram(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID serverId, @RequestParam(required = false) String ip,
            @RequestParam(required = false) List<EventCategory> category,
            @RequestParam(required = false) EventSeverity minSeverity, @RequestParam(required = false) String service,
            @RequestParam(required = false) String alertStatus, @RequestParam(required = false) String text,
            @RequestParam(required = false) UUID correlationId, @RequestParam(required = false) String type) {
        return this.service.histogram(filter(from, to, serverId, ip, category, minSeverity, service, alertStatus, text,
                correlationId, type));
    }

    @GetMapping("/{id}")
    public EventView get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/context")
    @Operation(summary = "Related events, metric coverage (gaps) and captures around an event")
    public EventContext context(@PathVariable long id) {
        return service.context(id);
    }
}
