package gov.mot.netmon.agent.websites;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import gov.mot.netmon.agent.model.Report;

/**
 * Keeps NetMon's section of the hosts file equal to the platform's blocked list and reports what is
 * really in the file. A list that has not arrived yet (null) changes nothing, so a restart without a
 * connection to the platform keeps the current blocking. Removing a website from the list unblocks it.
 */
public class WebsiteBlocker {

    private static final Logger log = Logger.getLogger(WebsiteBlocker.class.getName());
    static final Duration HELPER_GRACE = Duration.ofSeconds(90);

    private final WebsiteSettings settings;
    private final Supplier<List<String>> desired;
    private final Clock clock;
    private volatile String lastError;
    private volatile Instant requestedAt;

    public WebsiteBlocker(WebsiteSettings settings, Supplier<List<String>> desired, Clock clock) {
        this.settings = settings;
        this.desired = desired;
        this.clock = clock;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** Applies the current list if it differs from the hosts file. Safe to call often. */
    public synchronized void sync() {
        if (!settings.enabled()) return;
        List<String> raw = desired.get();
        if (raw == null) return; // not received from the platform yet
        List<String> want = HostsFile.sanitize(raw);
        try {
            String content = read();
            if (HostsFile.blocked(content).equals(want)) {
                lastError = null;
                requestedAt = null;
                return;
            }
            if (settings.direct()) {
                // In place (not a rename): the agent may be allowed to change this one file only.
                Files.writeString(settings.hostsFile(), HostsFile.render(content, want), StandardCharsets.ISO_8859_1,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                log.info("Website blocking: hosts file now blocks " + want.size() + " website(s)");
            } else {
                String body = String.join("\n", want) + "\n";
                String previous = Files.exists(settings.requestFile()) ? Files.readString(settings.requestFile()) : null;
                if (!body.equals(previous)) {
                    var tmp = settings.requestFile().resolveSibling(settings.requestFile().getFileName() + ".tmp");
                    Files.writeString(tmp, body, StandardCharsets.UTF_8);
                    Files.move(tmp, settings.requestFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    requestedAt = clock.instant();
                    log.info("Website blocking: asked the hosts helper to block " + want.size() + " website(s)");
                } else if (requestedAt == null) {
                    requestedAt = clock.instant();
                }
            }
            lastError = null;
        } catch (IOException | RuntimeException e) {
            lastError = "Cannot update the hosts file (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "). Run the website-blocking setup on this device.";
            log.warning(lastError);
        }
    }

    private String read() throws IOException {
        return Files.exists(settings.hostsFile()) ? Files.readString(settings.hostsFile(), StandardCharsets.ISO_8859_1) : "";
    }

    /** What the report says: whether blocking is on, any problem, and the websites read back from the hosts file. */
    public Report.Websites status() {
        if (!settings.enabled()) {
            return new Report.Websites(false, "Website blocking is not enabled on this device", null);
        }
        sync();
        try {
            List<String> blocked = HostsFile.blocked(read());
            String message = lastError;
            Instant asked = requestedAt;
            if (message == null && asked != null && clock.instant().isAfter(asked.plus(HELPER_GRACE))
                    && !blocked.equals(HostsFile.sanitize(desired.get()))) {
                message = "The NetMon hosts helper has not applied the list: run deploy/linux/enable-website-blocking.sh on this device.";
            }
            return new Report.Websites(true, cut(message), blocked);
        } catch (IOException e) {
            return new Report.Websites(true, cut("Cannot read the hosts file: " + e.getMessage()), List.of());
        }
    }

    private static String cut(String s) {
        return s == null || s.length() <= 300 ? s : s.substring(0, 297) + "...";
    }
}
