package gov.mot.netmon.agent.linux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Small helpers for reading kernel pseudo-files. */
final class ProcFs {

    private ProcFs() {
    }

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    static List<String> lines(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    /** Reads a single numeric value; null if missing or not a number (e.g. "speed" on virtual NICs). */
    static Long longOrNull(Path p) {
        try {
            String s = read(p).trim();
            return s.isEmpty() ? null : Long.parseLong(s);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    static String stringOrNull(Path p) {
        try {
            String s = read(p).trim();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    /** Value of "Key:   123 kB" lines in /proc/meminfo-style files, in bytes when the unit is kB. */
    static Long kbValue(List<String> lines, String key) {
        for (String line : lines) {
            if (line.startsWith(key + ":")) {
                String[] parts = line.substring(key.length() + 1).trim().split("\\s+");
                long v = Long.parseLong(parts[0]);
                return parts.length > 1 && parts[1].equalsIgnoreCase("kB") ? v * 1024 : v;
            }
        }
        return null;
    }
}
