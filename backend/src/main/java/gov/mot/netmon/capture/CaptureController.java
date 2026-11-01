package gov.mot.netmon.capture;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import gov.mot.netmon.capture.CaptureDtos.Capability;
import gov.mot.netmon.capture.CaptureDtos.CaptureView;
import gov.mot.netmon.capture.CaptureDtos.CreateCaptureRequest;
import gov.mot.netmon.capture.CaptureDtos.FilterCheck;
import gov.mot.netmon.capture.CaptureDtos.Limits;
import gov.mot.netmon.capture.CaptureDtos.PacketBytes;
import gov.mot.netmon.capture.CaptureDtos.PacketDetail;
import gov.mot.netmon.capture.CaptureDtos.PacketPage;
import gov.mot.netmon.capture.CaptureDtos.PacketSearch;
import gov.mot.netmon.capture.CaptureDtos.Statistics;
import gov.mot.netmon.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Packet captures. Viewing packet metadata: SUPER_ADMIN, NETWORK_ADMIN, SECURITY_ANALYST.
 * Starting/stopping, raw bytes and file download: SUPER_ADMIN, NETWORK_ADMIN only.
 */
@RestController
@RequestMapping("/api/v1/captures")
@Tag(name = "Packet captures")
@PreAuthorize(CaptureController.VIEW)
public class CaptureController {

    static final String VIEW = "hasAnyRole('SUPER_ADMIN','NETWORK_ADMIN','SECURITY_ANALYST')";
    static final String MANAGE = "hasAnyRole('SUPER_ADMIN','NETWORK_ADMIN')";

    private final CaptureService service;

    public CaptureController(CaptureService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<CaptureView> list(@RequestParam(required = false) UUID serverId,
            @RequestParam(required = false) CaptureStatus status, @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.list(new CaptureService.ListFilter(serverId, status, search), page, size);
    }

    @GetMapping("/limits")
    public Limits limits() {
        return service.limits();
    }

    @GetMapping("/capabilities")
    @Operation(summary = "Which servers can capture now, and why not")
    public List<Capability> capabilities() {
        return service.capabilities();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MANAGE)
    @Operation(summary = "Request a capture; it starts when the server's agent picks it up")
    public CaptureView create(@Valid @RequestBody CreateCaptureRequest req) {
        return service.create(req);
    }

    @GetMapping("/{id}")
    public CaptureView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize(MANAGE)
    public CaptureView stop(@PathVariable UUID id) {
        return service.stop(id);
    }

    @GetMapping("/{id}/packets")
    @Operation(summary = "Packet metadata, filtered and paginated")
    public PacketPage packets(@PathVariable UUID id, @RequestParam(required = false) String src,
            @RequestParam(required = false) String dst, @RequestParam(required = false) String host,
            @RequestParam(required = false) String protocol, @RequestParam(required = false) Integer port,
            @RequestParam(required = false) Integer minLength, @RequestParam(required = false) Integer maxLength,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String text, @RequestParam(required = false) String displayFilter,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "false") boolean descending) {
        if (size < 1 || size > 500 || page < 0) throw gov.mot.netmon.common.ApiException.badRequest("INVALID_PAGE", "Invalid page");
        return service.packets(id, new PacketSearch(src, dst, host, protocol, port, minLength, maxLength, from, to, text,
                displayFilter, page, size, descending));
    }

    @GetMapping("/{id}/packets/{frame}")
    @Operation(summary = "Protocol tree of one packet (Wireshark-style details)")
    public PacketDetail packet(@PathVariable UUID id, @PathVariable long frame) {
        return service.detail(id, frame);
    }

    @GetMapping("/{id}/packets/{frame}/bytes")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Raw bytes of one packet (audited)")
    public PacketBytes bytes(@PathVariable UUID id, @PathVariable long frame) {
        return service.bytes(id, frame);
    }

    @GetMapping("/{id}/statistics")
    public Statistics statistics(@PathVariable UUID id) {
        return service.statistics(id);
    }

    @GetMapping("/{id}/download")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Download the capture as PCAPNG (audited)")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id) {
        PacketCapture c = service.prepareDownload(id);
        String safeName = c.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        String file = "capture-" + safeName + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC)
                .format(c.getRequestedAt()) + ".pcapng";
        StreamingResponseBody body = out -> service.writeDownload(id, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("application/x-pcapng"))
                .contentLength(c.getBytesStored())
                .body(body);
    }

    public record FilterRequest(@NotBlank @Size(max = 1000) String filter) {
    }

    @PostMapping("/display-filter/validate")
    @Operation(summary = "Check a Wireshark display filter")
    public FilterCheck validate(@Valid @RequestBody FilterRequest req) {
        return service.checkDisplayFilter(req.filter());
    }

    public record DeleteRequest(@NotBlank @Size(min = 5, max = 500) String reason) {
    }

    @DeleteMapping("/{id}/files")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Delete capture files and packet metadata before retention ends (audited)")
    public CaptureView deleteFiles(@PathVariable UUID id, @Valid @RequestBody DeleteRequest req) {
        return service.deleteFiles(id, req.reason());
    }
}
