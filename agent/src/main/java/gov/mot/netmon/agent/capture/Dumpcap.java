package gov.mot.netmon.agent.capture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gov.mot.netmon.agent.collect.CommandRunner;
import gov.mot.netmon.agent.model.Report;

/**
 * The only way the agent touches packets: Wireshark's dumpcap at a fixed, locally configured path,
 * started with an argument list built here (no shell). The capture filter is passed as one
 * argument after {@code -f}, so it can never become another option or command.
 */
public class Dumpcap {

    /** Interfaces that are not network cards; offered only when listed in capture.interfaces. */
    static final Set<String> PSEUDO = Set.of("any", "bluetooth-monitor", "nflog", "nfqueue", "dbus-system", "dbus-session",
            "randpkt", "ciscodump", "sshdump", "udpdump", "wifidump", "etwdump", "sdjournal", "dpauxmon", "androiddump");
    private static final Pattern IFACE_LINE = Pattern.compile("^\\s*\\d+\\.\\s+(\\S+)(?:\\s+\\((.*)\\))?\\s*$");
    private static final Pattern BPF_INSTRUCTION = Pattern.compile("(?m)^\\(\\d{3}\\)");
    private static final Pattern PIECE = Pattern.compile("^cap_(\\d{5,})_\\d{14}\\.pcapng$");
    private static final Pattern PACKETS = Pattern.compile("Packets captured: (\\d+)");

    /** A running dumpcap; abstracted so tests can simulate it. */
    public interface Capture {
        boolean isAlive();

        /** Asks dumpcap to finish (SIGTERM on Linux: it closes the current file cleanly). */
        void stop();

        int exitCode();

        /** Last lines dumpcap wrote to its error output (status and problems). */
        String output();
    }

    /** Starts the dumpcap process; tests replace this. */
    public interface Launcher {
        Capture launch(List<String> command, Path workDir) throws IOException;
    }

    public record Piece(int seq, Path file) {
    }

    private final Path executable;
    private final CommandRunner runner;
    private final Launcher launcher;

    public Dumpcap(Path executable) {
        this(executable, CommandRunner.fixed(executable, 20), Dumpcap::launchProcess);
    }

    public Dumpcap(Path executable, CommandRunner runner, Launcher launcher) {
        this.executable = executable;
        this.runner = runner;
        this.launcher = launcher;
    }

    public boolean installed() {
        return Files.isExecutable(executable);
    }

    public Path executable() {
        return executable;
    }

    public String version() {
        try {
            String out = runner.run(List.of("-v")).stdout();
            String first = out.lines().findFirst().orElse("").trim();
            return first.length() > 80 ? first.substring(0, 80) : first;
        } catch (Exception e) {
            return null;
        }
    }

    /** Interfaces dumpcap can open, as reported by {@code dumpcap -D}. Throws if dumpcap fails. */
    public List<Report.CaptureInterface> interfaces() throws IOException, InterruptedException {
        CommandRunner.Result r = runner.run(List.of("-D"));
        if (r.exitCode() != 0) {
            String first = r.stdout().lines().filter(l -> !l.isBlank()).findFirst().orElse("exit code " + r.exitCode());
            throw new IOException(first.trim());
        }
        return parseInterfaces(r.stdout());
    }

    static List<Report.CaptureInterface> parseInterfaces(String output) {
        List<Report.CaptureInterface> out = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher m = IFACE_LINE.matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1);
            if (name.length() > 128 || name.chars().anyMatch(Character::isISOControl)) continue;
            String desc = m.group(2);
            if (desc != null && desc.length() > 200) desc = desc.substring(0, 200);
            out.add(new Report.CaptureInterface(name, desc));
        }
        return out;
    }

    /** Interfaces allowed by the local allowlist (or every real interface if the allowlist is empty). */
    static List<Report.CaptureInterface> allowed(List<Report.CaptureInterface> all, List<String> allowlist) {
        if (allowlist.isEmpty()) {
            return all.stream().filter(i -> !PSEUDO.contains(i.name())).toList();
        }
        return all.stream().filter(i -> allowlist.contains(i.name())
                || (i.description() != null && allowlist.contains(i.description()))).toList();
    }

    /**
     * Compiles the filter for the interface ({@code dumpcap -d}). dumpcap 4.2 exits 0 even for an
     * invalid filter, so success is recognised by the printed BPF instructions. Returns null if
     * valid, otherwise dumpcap's explanation.
     */
    public String checkFilter(String iface, String filter) throws IOException, InterruptedException {
        if (filter == null || filter.isBlank()) return null;
        CommandRunner.Result r = runner.run(List.of("-i", iface, "-f", filter, "-d"));
        if (r.exitCode() == 0 && BPF_INSTRUCTION.matcher(r.stdout()).find()) return null;
        return r.stdout().lines().filter(l -> l.contains("Invalid capture filter") || l.contains("can't parse")
                || l.contains("isn't a valid")).findFirst().orElse("The capture filter was rejected by dumpcap").trim();
    }

    /** Fixed argument list: interface, optional BPF filter, snaplen, total duration, piece rotation, pcapng. */
    static List<String> command(Path executable, String iface, String filter, int snaplen, int durationSeconds,
            int rotateSeconds, Path dir) {
        List<String> cmd = new ArrayList<>(List.of(executable.toString(), "-i", iface));
        if (filter != null && !filter.isBlank()) {
            cmd.add("-f");
            cmd.add(filter);
        }
        cmd.addAll(List.of("-s", Integer.toString(snaplen), "-n", "-q",
                "-a", "duration:" + durationSeconds,
                "-b", "duration:" + rotateSeconds,
                "-b", "filesize:" + CaptureSettings.ROTATE_KILOBYTES,
                "-w", dir.resolve("cap.pcapng").toString()));
        return cmd;
    }

    public Capture start(String iface, String filter, int snaplen, int durationSeconds, int rotateSeconds, Path dir)
            throws IOException {
        return launcher.launch(command(executable, iface, filter, snaplen, durationSeconds, rotateSeconds, dir), dir);
    }

    /** Pieces written by dumpcap in the folder, in order (cap_00001_YYYYMMDDhhmmss.pcapng ...). */
    public static List<Piece> pieces(Path dir) throws IOException {
        List<Piece> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            s.forEach(f -> {
                Matcher m = PIECE.matcher(f.getFileName().toString());
                if (m.matches()) out.add(new Piece(Integer.parseInt(m.group(1)), f));
            });
        }
        out.sort((a, b) -> Integer.compare(a.seq(), b.seq()));
        return out;
    }

    static Long packetsCaptured(String output) {
        Matcher m = PACKETS.matcher(output == null ? "" : output);
        Long last = null;
        while (m.find()) last = Long.parseLong(m.group(1));
        return last;
    }

    private static Capture launchProcess(List<String> command, Path workDir) throws IOException {
        Path log = workDir.resolve("dumpcap.log");
        Process p = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        p.getOutputStream().close();
        return new Capture() {
            @Override
            public boolean isAlive() {
                return p.isAlive();
            }

            @Override
            public void stop() {
                p.destroy();
            }

            @Override
            public int exitCode() {
                return p.isAlive() ? -1 : p.exitValue();
            }

            @Override
            public String output() {
                try {
                    String s = Files.readString(log);
                    return s.length() > 4000 ? s.substring(s.length() - 4000) : s;
                } catch (IOException e) {
                    return "";
                }
            }
        };
    }
}
