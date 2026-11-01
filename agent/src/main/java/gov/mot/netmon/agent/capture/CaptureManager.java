package gov.mot.netmon.agent.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.model.Report;
import gov.mot.netmon.agent.transport.PlatformClient;
import gov.mot.netmon.agent.transport.PlatformClient.Outcome;

/**
 * Runs packet captures requested through the platform, one at a time.
 *
 * <p>Every request is checked against the LOCAL settings (capture enabled, interface allowlist,
 * duration and size limits) before dumpcap starts; the platform cannot widen them. dumpcap writes
 * short PCAPNG pieces; each finished piece is uploaded and then deleted, so the server keeps
 * little capture data and the platform can analyse packets while the capture runs.
 */
public class CaptureManager {

    private static final Logger log = Logger.getLogger(CaptureManager.class.getName());
    static final Duration CAPABILITY_REFRESH = Duration.ofMinutes(5);
    /** Give up a finished capture's pending uploads after this long (platform unreachable). */
    static final Duration UPLOAD_GIVE_UP = Duration.ofHours(24);
    private static final Pattern FILTER_TEXT = Pattern.compile("^[\\x20-\\x7E]{1,512}$");
    private static final Pattern DROPS = Pattern.compile("received/dropped on interface '[^']*': (\\d+)/(\\d+)");

    private final CaptureSettings settings;
    private final Dumpcap dumpcap;
    private final PlatformClient client;
    private final Supplier<String> authorization;
    private final Clock clock;
    private volatile Report.Capture capability;
    private Instant capabilityAt;
    private Active active;
    private volatile boolean running = true;

    /** State of the capture in progress. */
    static final class Active {
        final UUID id;
        final long maxBytes;
        final Instant deadline;
        final Path dir;
        Dumpcap.Capture process;
        long uploadedBytes;
        int nextSeq = 1;
        String endReason; // set when the agent ends the capture early (stop, size limit, disk)
        boolean abandoned; // platform no longer accepts data
        Instant exitedAt;

        Active(UUID id, long maxBytes, Instant deadline, Path dir) {
            this.id = id;
            this.maxBytes = maxBytes;
            this.deadline = deadline;
            this.dir = dir;
        }
    }

    public CaptureManager(CaptureSettings settings, Dumpcap dumpcap, PlatformClient client, Supplier<String> authorization,
            Clock clock) {
        this.settings = settings;
        this.dumpcap = dumpcap;
        this.client = client;
        this.authorization = authorization;
        this.clock = clock;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public boolean busy() {
        return active != null;
    }

    public void stop() {
        running = false;
        Active a = active;
        if (a != null && a.process != null) a.process.stop();
    }

    /** Capability sent with every report. Refreshed every few minutes. */
    public Report.Capture capability() {
        Report.Capture c = capability;
        return c != null ? c : refreshCapability();
    }

    synchronized Report.Capture refreshCapability() {
        Report.Capture c;
        int maxDur = settings.maxDurationSeconds();
        long maxBytes = settings.maxBytes();
        if (!settings.enabled()) {
            c = new Report.Capture(false, false, "Packet capture is not enabled on this server (capture.enabled)", null, null,
                    null, null);
        } else if (!java.nio.file.Files.exists(dumpcap.executable())) {
            c = new Report.Capture(true, false, "dumpcap not found at " + dumpcap.executable()
                    + " (install Wireshark/TShark, and Npcap on Windows)", null, maxDur, maxBytes, null);
        } else if (!dumpcap.installed()) {
            c = new Report.Capture(true, false, "The agent account may not run " + dumpcap.executable()
                    + " (on Linux: run deploy/linux/enable-capture.sh, which adds the wireshark group)", null, maxDur, maxBytes, null);
        } else {
            String version = dumpcap.version();
            try {
                var all = dumpcap.interfaces();
                var allowed = Dumpcap.allowed(all, settings.interfaces());
                if (allowed.isEmpty()) {
                    c = new Report.Capture(true, false, all.isEmpty()
                            ? "dumpcap lists no interfaces (missing capture permission?)"
                            : "None of the interfaces in capture.interfaces exists on this server", null, maxDur, maxBytes, version);
                } else {
                    c = new Report.Capture(true, true, null, allowed.size() > 64 ? allowed.subList(0, 64) : allowed, maxDur,
                            maxBytes, version);
                }
            } catch (Exception e) {
                c = new Report.Capture(true, false, truncate("dumpcap cannot list interfaces: " + e.getMessage(), 300), null,
                        maxDur, maxBytes, version);
            }
        }
        capability = c;
        capabilityAt = clock.instant();
        return c;
    }

    /** Capture thread: poll tasks and drive the active capture. */
    public void runForever() {
        cleanupSpool();
        while (running) {
            try {
                tick();
                Thread.sleep(active != null ? 1000L : settings.pollSeconds() * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warning("Capture loop error: " + e);
            }
        }
    }

    private long lastPoll;

    /** One step: refresh capability, poll tasks (every pollSeconds), advance the active capture. */
    void tick() {
        if (!settings.enabled()) return;
        if (capabilityAt == null || Duration.between(capabilityAt, clock.instant()).compareTo(CAPABILITY_REFRESH) > 0) {
            refreshCapability();
        }
        long now = clock.millis();
        if (active == null || now - lastPoll >= settings.pollSeconds() * 1000L) {
            lastPoll = now;
            pollTasks();
        }
        if (active != null) advance(active);
    }

    void pollTasks() {
        PlatformClient.Response res = client.post("/api/v1/agent/tasks", null, authorization.get());
        if (res.outcome() != Outcome.OK || res.body() == null) return;
        for (JsonNode t : res.body().path("tasks")) {
            String type = t.path("type").asText("");
            UUID id;
            try {
                id = UUID.fromString(t.path("captureId").asText());
            } catch (IllegalArgumentException e) {
                continue;
            }
            switch (type) {
                case "START_CAPTURE" -> start(id, t);
                case "STOP_CAPTURE" -> requestStop(id);
                default -> log.warning("Ignoring unknown task type from platform: " + truncate(type, 40));
            }
        }
    }

    void start(UUID id, JsonNode t) {
        String iface = t.path("interfaceName").asText("");
        String filter = t.hasNonNull("captureFilter") ? t.get("captureFilter").asText().trim() : "";
        int snaplen = t.path("snaplen").asInt(256);
        int duration = t.path("durationSeconds").asInt(0);
        long maxBytes = t.path("maxBytes").asLong(0);
        log.info("Capture " + id + " requested on '" + iface + "'" + (filter.isEmpty() ? "" : " filter '" + filter + "'")
                + " for " + duration + "s");

        if (active != null) {
            status(id, "FAILED", "Another capture is already running on this server", null);
            return;
        }
        Report.Capture cap = refreshCapability();
        if (!cap.enabled()) {
            status(id, "UNSUPPORTED", cap.message(), null);
            return;
        }
        if (!cap.available()) {
            status(id, "FAILED", cap.message(), null);
            return;
        }
        if (cap.interfaces().stream().noneMatch(i -> i.name().equals(iface))) {
            status(id, "FAILED", "Interface '" + truncate(iface, 64) + "' is not allowed on this server", null);
            return;
        }
        if (duration < 10 || duration > settings.maxDurationSeconds()) {
            status(id, "FAILED", "Duration must be between 10 and " + settings.maxDurationSeconds() + " seconds on this server",
                    null);
            return;
        }
        if (maxBytes <= 0 || maxBytes > settings.maxBytes()) {
            status(id, "FAILED", "Size limit exceeds this server's maximum of " + settings.maxBytes() / CaptureSettings.MB + " MB",
                    null);
            return;
        }
        if (snaplen < 64 || snaplen > 65535) {
            status(id, "FAILED", "Invalid snapshot length", null);
            return;
        }
        if (!filter.isEmpty() && !FILTER_TEXT.matcher(filter).matches()) {
            status(id, "FAILED", "The capture filter contains characters that are not allowed", null);
            return;
        }
        status(id, "STARTING", null, null);
        try {
            String problem = dumpcap.checkFilter(iface, filter);
            if (problem != null) {
                status(id, "FAILED", truncate(problem, 500), null);
                return;
            }
            createPrivateDir(settings.spoolDir());
            long free = Files.getFileStore(settings.spoolDir()).getUsableSpace();
            long needed = settings.minFreeDiskBytes() + 2L * CaptureSettings.ROTATE_KILOBYTES * 1024;
            if (free < needed) {
                status(id, "FAILED", "Not enough free disk space on the server for the capture spool ("
                        + free / CaptureSettings.MB + " MB free, " + needed / CaptureSettings.MB + " MB needed)", null);
                return;
            }
            Path dir = settings.spoolDir().resolve(id.toString());
            createPrivateDir(dir);
            Active a = new Active(id, maxBytes, clock.instant().plusSeconds(duration + 60L), dir);
            a.process = dumpcap.start(iface, filter, snaplen, duration, settings.rotateSeconds(), dir);
            active = a;
            status(id, "RUNNING", null, null);
            log.info("Capture " + id + " running");
        } catch (Exception e) {
            status(id, "FAILED", truncate("Could not start dumpcap: " + e.getMessage(), 500), null);
        }
    }

    void requestStop(UUID id) {
        Active a = active;
        if (a != null && a.id.equals(id)) {
            if (a.endReason == null) a.endReason = "Stopped on request";
            if (a.process != null && a.process.isAlive()) {
                log.info("Stopping capture " + id + " on request");
                a.process.stop();
            }
        } else {
            status(id, "FAILED", "This capture was not running on the agent (was the agent restarted?)", null);
        }
    }

    /** Uploads finished pieces, enforces limits and reports the end of the capture. */
    void advance(Active a) {
        boolean alive = a.process != null && a.process.isAlive();
        if (!alive && a.exitedAt == null) a.exitedAt = clock.instant();

        if (alive && clock.instant().isAfter(a.deadline)) {
            a.endReason = "Stopped by the agent: duration exceeded";
            a.process.stop();
        }
        if (alive && a.endReason == null) {
            try {
                if (Files.getFileStore(a.dir).getUsableSpace() < settings.minFreeDiskBytes()) {
                    a.endReason = "Stopped by the agent: the server is low on disk space";
                    a.process.stop();
                }
            } catch (IOException e) {
                // keep going; the upload step reports real problems
            }
        }

        List<Dumpcap.Piece> pieces;
        try {
            pieces = Dumpcap.pieces(a.dir);
        } catch (IOException e) {
            pieces = List.of();
        }
        // While dumpcap runs, the newest piece is still being written.
        int ready = alive ? Math.max(0, pieces.size() - 1) : pieces.size();
        long pending = 0;
        for (Dumpcap.Piece p : pieces) pending += size(p.file());
        if (alive && a.endReason == null && a.uploadedBytes + pending >= a.maxBytes) {
            a.endReason = "Size limit reached";
            a.process.stop();
        }

        for (int i = 0; i < ready && !a.abandoned; i++) {
            Dumpcap.Piece p = pieces.get(i);
            if (!upload(a, p)) break;
        }

        if (!alive && (a.abandoned || allUploaded(a))) {
            finish(a);
        } else if (!alive && a.exitedAt != null && Duration.between(a.exitedAt, clock.instant()).compareTo(UPLOAD_GIVE_UP) > 0) {
            log.warning("Capture " + a.id + ": platform unreachable for too long, discarding unsent pieces");
            a.abandoned = true;
            finish(a);
        }
    }

    private boolean allUploaded(Active a) {
        try {
            return Dumpcap.pieces(a.dir).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /** Returns true if the piece was handled (uploaded or skipped), false to retry later. */
    private boolean upload(Active a, Dumpcap.Piece p) {
        long size = size(p.file());
        int seq = a.nextSeq; // contiguous numbering on the platform, whatever dumpcap's numbering
        PlatformClient.Response res = client.putFile("/api/v1/agent/captures/" + a.id + "/segments/" + seq, p.file(),
                authorization.get());
        if (res.outcome() == Outcome.OK) {
            a.uploadedBytes += size;
            a.nextSeq++;
            delete(p.file());
            if (res.body() != null && res.body().path("stopRequested").asBoolean(false) && a.process != null
                    && a.process.isAlive()) {
                if (a.endReason == null) a.endReason = "Stopped on request";
                a.process.stop();
            }
            return true;
        }
        int s = res.status();
        if (s == 404 || s == 409 || s == 410 || s == 413 || s == 400 || s == 403) {
            log.warning("Capture " + a.id + ": platform refused piece " + seq + " (HTTP " + s + " " + res.errorCode()
                    + "); ending capture");
            a.abandoned = true;
            if (a.endReason == null) a.endReason = s == 413 ? "Size limit reached" : "The platform stopped accepting data";
            if (a.process != null && a.process.isAlive()) a.process.stop();
            return false;
        }
        return false; // platform unreachable: keep the file and retry
    }

    private void finish(Active a) {
        String out = a.process == null ? "" : a.process.output();
        Long packets = Dumpcap.packetsCaptured(out);
        int exit = a.process == null ? -1 : a.process.exitCode();
        String state;
        String message = a.endReason;
        var drops = DROPS.matcher(out);
        String dropInfo = null;
        while (drops.find()) {
            if (!"0".equals(drops.group(2))) dropInfo = drops.group(2) + " packets dropped by the server";
        }
        if (a.endReason != null || exit == 0 || packets != null) {
            state = "COMPLETED";
            if (a.endReason == null && exit != 0) message = "dumpcap ended with code " + exit;
        } else {
            state = "FAILED";
            message = "dumpcap failed: " + lastMeaningfulLine(out);
        }
        if (dropInfo != null) message = message == null ? dropInfo : message + "; " + dropInfo;
        if (status(a.id, state, message == null ? null : truncate(message, 900), packets)
                || a.abandoned || Duration.between(a.exitedAt, clock.instant()).compareTo(UPLOAD_GIVE_UP) > 0) {
            log.info("Capture " + a.id + " " + state + (message == null ? "" : ": " + message)
                    + (packets == null ? "" : " (" + packets + " packets)"));
            deleteDir(a.dir);
            active = null;
        }
    }

    /** Sends a status update; true if the platform accepted it (or refused it permanently). */
    boolean status(UUID id, String state, String message, Long packets) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        if (message != null) body.put("message", message);
        if (packets != null) body.put("packets", packets);
        PlatformClient.Response res = client.post("/api/v1/agent/captures/" + id + "/status", body, authorization.get());
        if (res.outcome() != Outcome.OK && !"STARTING".equals(state)) {
            log.warning("Capture " + id + ": status " + state + " not delivered (HTTP " + res.status() + ")");
        }
        return res.outcome() == Outcome.OK || (res.status() >= 400 && res.status() < 500);
    }

    /** Pieces left by an earlier run (agent restarted mid-capture) cannot be completed: remove them. */
    void cleanupSpool() {
        if (!Files.isDirectory(settings.spoolDir())) return;
        try (var s = Files.list(settings.spoolDir())) {
            s.filter(Files::isDirectory).forEach(d -> {
                try {
                    UUID id = UUID.fromString(d.getFileName().toString());
                    log.warning("Removing unfinished capture " + id + " left by an earlier agent run");
                    status(id, "FAILED", "The agent restarted during the capture; unsent data was discarded", null);
                    deleteDir(d);
                } catch (IllegalArgumentException e) {
                    // not ours
                }
            });
        } catch (IOException e) {
            log.warning("Could not clean the capture spool: " + e.getMessage());
        }
    }

    private static void createPrivateDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // Windows: the state folder's ACL (set by the installer) applies
        }
    }

    private static void deleteDir(Path dir) {
        try (var s = Files.list(dir)) {
            s.forEach(CaptureManager::delete);
        } catch (IOException e) {
            // already gone
        }
        delete(dir);
    }

    private static void delete(Path f) {
        try {
            Files.deleteIfExists(f);
        } catch (IOException e) {
            log.warning("Could not delete " + f + ": " + e.getMessage());
        }
    }

    private static long size(Path f) {
        try {
            return Files.size(f);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String lastMeaningfulLine(String out) {
        return out.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("File:") && !l.startsWith("Capturing on"))
                .reduce((x, y) -> y).map(l -> truncate(l.replace("\r", " "), 400)).orElse("no output");
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        s = s.chars().filter(ch -> !Character.isISOControl(ch) || ch == ' ').collect(StringBuilder::new,
                StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return s.length() > max ? s.substring(0, max) : s;
    }

    Active active() {
        return active;
    }
}
