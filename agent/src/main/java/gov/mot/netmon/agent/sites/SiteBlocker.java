package gov.mot.netmon.agent.sites;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import gov.mot.netmon.agent.collect.CommandRunner;
import gov.mot.netmon.agent.model.Report;

/**
 * Keeps NetMon's section of the hosts file equal to the list of blocked websites the platform
 * sends. The list is typed data (validated host names only); nothing else from the platform is
 * written or executed. Off unless {@code sites.enabled=true}.
 */
public class SiteBlocker {

    private static final Logger log = Logger.getLogger(SiteBlocker.class.getName());

    /** Writes the new hosts file content (Windows) or asks the root helper to (Linux). */
    public interface Writer {
        void write(String content, List<String> domains) throws IOException, InterruptedException;
    }

    private final SiteSettings settings;
    private final Writer writer;
    private volatile String problem;

    public SiteBlocker(SiteSettings settings, Writer writer) {
        this.settings = settings;
        this.writer = writer;
    }

    public static SiteBlocker forPlatform(SiteSettings s, boolean windows) {
        Writer w;
        if (windows) {
            CommandRunner ipconfig = CommandRunner.fixed(s.ipconfig(), 15);
            w = (content, domains) -> {
                Files.writeString(s.hostsFile(), content, StandardCharsets.ISO_8859_1);
                try {
                    ipconfig.run(List.of("/flushdns")); // so the change takes effect now, not when the cache expires
                } catch (IOException e) {
                    log.warning("ipconfig /flushdns failed: " + e.getMessage());
                }
            };
        } else {
            CommandRunner sudo = CommandRunner.fixed(s.sudo(), 15);
            w = (content, domains) -> {
                List<String> args = new ArrayList<>(List.of("-n", s.helper().toString()));
                args.addAll(domains);
                CommandRunner.Result r = sudo.run(args);
                if (r.exitCode() != 0) {
                    throw new IOException("netmon-hosts helper failed (exit " + r.exitCode() + "): " + r.stdout().strip()
                            + ". Run deploy/linux/enable-sites.sh.");
                }
            };
        }
        return new SiteBlocker(s, w);
    }

    public boolean enabled() {
        return settings.enabled();
    }

    private String read() throws IOException {
        return Files.exists(settings.hostsFile()) ? Files.readString(settings.hostsFile(), StandardCharsets.ISO_8859_1) : "";
    }

    /**
     * Brings the hosts file in line with {@code wanted}. {@code null} means the platform has not sent
     * a list (yet, or an older platform): nothing is changed.
     */
    public synchronized void apply(List<String> wanted) {
        if (!settings.enabled() || wanted == null) return;
        List<String> domains = HostsFile.clean(wanted);
        try {
            String text = read();
            if (HostsFile.blocked(text).equals(domains)) {
                problem = null;
                return;
            }
            writer.write(HostsFile.render(text, domains), domains);
            problem = null;
            log.info("Blocked websites updated: " + (domains.isEmpty() ? "none" : String.join(", ", domains)));
        } catch (AccessDeniedException e) {
            problem = "No permission to change " + settings.hostsFile() + " (on Windows the agent service must run as LocalSystem)";
            log.warning(problem);
        } catch (IOException e) {
            problem = "Could not change the hosts file: " + e.getMessage();
            log.warning(problem);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** What is blocked right now, read back from the hosts file (sent with every report). */
    public Report.Sites status() {
        if (!settings.enabled()) {
            return new Report.Sites(false, "Website blocking is off on this computer (sites.enabled=true in agent.properties)", List.of());
        }
        try {
            List<String> blocked = HostsFile.blocked(read());
            String msg = problem != null ? problem : "Websites are blocked through " + settings.hostsFile();
            return new Report.Sites(true, msg.length() > 300 ? msg.substring(0, 300) : msg, blocked);
        } catch (IOException e) {
            return new Report.Sites(true, "Cannot read " + settings.hostsFile() + ": " + e.getMessage(), List.of());
        }
    }
}
