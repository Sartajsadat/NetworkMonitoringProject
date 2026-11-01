package gov.mot.netmon.capture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.config.AppProperties;

/**
 * Runs TShark (Wireshark's command-line analyser) on stored capture files.
 * <ul>
 *   <li>Fixed executable path from configuration; arguments as a list, never through a shell.</li>
 *   <li>{@code -n}: no name resolution, so analysing a capture never triggers network lookups.</li>
 *   <li>Every run has a timeout and an output size limit.</li>
 *   <li>User input reaches TShark only as a display filter, passed as one argument after validation.</li>
 * </ul>
 */
@Component
public class Tshark {

    /** TShark exit code for an invalid display filter. */
    static final int EXIT_INVALID_FILTER = 4;
    static final int MAX_FILTER_LENGTH = 1000;
    static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]*$");

    /** Fields extracted for the packet table (order matters for {@link #parseSummary}). */
    static final List<String> SUMMARY_FIELDS = List.of("frame.number", "frame.time_epoch", "ip.src", "ip.dst",
            "ipv6.src", "ipv6.dst", "tcp.srcport", "tcp.dstport", "udp.srcport", "udp.dstport", "frame.len",
            "_ws.col.protocol", "tcp.flags", "tcp.analysis.retransmission", "tcp.flags.reset", "dns.qry.name",
            "_ws.col.info", "arp.src.proto_ipv4", "arp.dst.proto_ipv4");

    private final Path executable;
    private final Duration timeout;

    public Tshark(AppProperties props) {
        this.executable = Path.of(props.capture().tsharkPath());
        this.timeout = props.capture().tsharkTimeout();
    }

    public boolean available() {
        return Files.isExecutable(executable);
    }

    record Result(int exitCode, String stdout, String stderr) {
    }

    Result run(List<String> args, int maxOutputBytes) {
        if (!available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TSHARK_UNAVAILABLE",
                    "Packet analysis is not available: TShark is not installed on the platform server (" + executable + ")");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(executable.toString());
        cmd.addAll(args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().keySet().removeIf(k -> !k.equals("PATH") && !k.equals("HOME") && !k.equals("LANG"));
            Process p = pb.start();
            p.getOutputStream().close();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread errReader = new Thread(() -> copyLimited(p.getErrorStream(), err, 64 * 1024));
            errReader.setDaemon(true);
            errReader.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean truncated = copyLimited(p.getInputStream(), out, maxOutputBytes);
            if (truncated) p.destroyForcibly();
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "TSHARK_TIMEOUT", "Packet analysis took too long");
            }
            errReader.join(2000);
            return new Result(truncated ? 0 : p.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TSHARK_FAILED", "TShark could not run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TSHARK_FAILED", "Interrupted");
        }
    }

    /** Returns true when output was cut off at the limit. */
    private static boolean copyLimited(InputStream in, ByteArrayOutputStream out, int limit) {
        byte[] buf = new byte[64 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                int room = limit - out.size();
                if (room <= 0) return true;
                out.write(buf, 0, Math.min(n, room));
                if (n > room) return true;
            }
        } catch (IOException e) {
            // process ended
        }
        return false;
    }

    /** Null if the filter is valid, otherwise TShark's error message. */
    public String validateDisplayFilter(String filter) {
        String f = checkFilterText(filter);
        Result r = run(List.of("-n", "-Y", f, "-r", "/dev/null"), 4096);
        if (r.exitCode() == EXIT_INVALID_FILTER) {
            return cleanError(r.stderr());
        }
        return null;
    }

    static String checkFilterText(String filter) {
        if (filter == null || filter.isBlank()) throw ApiException.badRequest("INVALID_FILTER", "Filter is empty");
        String f = filter.trim();
        if (f.length() > MAX_FILTER_LENGTH || !PRINTABLE.matcher(f).matches()) {
            throw ApiException.badRequest("INVALID_FILTER", "Filter must be printable ASCII and at most " + MAX_FILTER_LENGTH + " characters");
        }
        return f;
    }

    static String cleanError(String stderr) {
        return stderr.lines().filter(l -> !l.startsWith("Running as user")).map(l -> l.replaceFirst("^tshark: ", ""))
                .filter(l -> !l.isBlank()).reduce((a, b) -> a + " " + b).orElse("Invalid filter");
    }

    public record SummaryRow(int frame, Instant time, String src, String dst, Integer srcPort, Integer dstPort,
            int length, String protocol, String tcpFlags, boolean retransmission, boolean reset, String dnsQuery,
            String info) {
    }

    /** Metadata of every packet in one file (no payload). */
    public List<SummaryRow> summaries(Path file) {
        List<String> args = new ArrayList<>(List.of("-n", "-r", file.toString(), "-T", "fields", "-E", "header=n",
                "-E", "separator=/t", "-E", "occurrence=f", "-E", "quote=n"));
        for (String f : SUMMARY_FIELDS) {
            args.add("-e");
            args.add(f);
        }
        Result r = run(args, 512 * 1024 * 1024);
        if (r.exitCode() != 0 && r.stdout().isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TSHARK_FAILED", cleanError(r.stderr()));
        }
        List<SummaryRow> rows = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            SummaryRow row = parseSummary(line);
            if (row != null) rows.add(row);
        }
        return rows;
    }

    static SummaryRow parseSummary(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < SUMMARY_FIELDS.size() || f[0].isBlank()) return null;
        try {
            int frame = Integer.parseInt(f[0]);
            Instant time = epoch(f[1]);
            String src = first(f[2], f[4], f[17]);
            String dst = first(f[3], f[5], f[18]);
            Integer sport = intOrNull(first(f[6], f[8]));
            Integer dport = intOrNull(first(f[7], f[9]));
            int len = Integer.parseInt(f[10]);
            boolean retrans = !f[13].isBlank() && !f[13].equalsIgnoreCase("False");
            boolean reset = f[14].equalsIgnoreCase("True") || f[14].equals("1");
            return new SummaryRow(frame, time, src, dst, sport, dport, len, cut(f[11], 40), cut(f[12], 10), retrans, reset,
                    cut(blankToNull(f[15]), 255), cut(blankToNull(f[16]), 300));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Frame numbers (within one file) that match a display filter. */
    public List<Integer> matchingFrames(Path file, String filter, int limit) {
        String f = checkFilterText(filter);
        Result r = run(List.of("-n", "-r", file.toString(), "-Y", f, "-T", "fields", "-e", "frame.number"), 16 * 1024 * 1024);
        if (r.exitCode() == EXIT_INVALID_FILTER) {
            throw ApiException.badRequest("INVALID_FILTER", cleanError(r.stderr()));
        }
        List<Integer> out = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            if (out.size() >= limit) break;
            if (!line.isBlank()) out.add(Integer.parseInt(line.trim()));
        }
        return out;
    }

    /** Wireshark-style protocol tree of one packet as PDML (XML). */
    public String pdml(Path file, int frame) {
        return run(List.of("-n", "-r", file.toString(), "-Y", "frame.number==" + frame, "-T", "pdml"), 8 * 1024 * 1024).stdout();
    }

    /** Hex/ASCII dump of one packet. */
    public String hexDump(Path file, int frame) {
        return run(List.of("-n", "-r", file.toString(), "-Y", "frame.number==" + frame, "-x"), 1024 * 1024).stdout();
    }

    private static Instant epoch(String s) {
        String[] p = s.trim().split("\\.");
        long sec = Long.parseLong(p[0]);
        long nanos = 0;
        if (p.length > 1) {
            String frac = (p[1] + "000000000").substring(0, 9);
            nanos = Long.parseLong(frac);
        }
        return Instant.ofEpochSecond(sec, nanos);
    }

    private static String first(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s.trim();
        return null;
    }

    private static Integer intOrNull(String s) {
        return s == null ? null : Integer.parseInt(s);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String clean = s.replaceAll("\\p{Cntrl}", " ").trim();
        return clean.length() > max ? clean.substring(0, max) : clean;
    }
}
