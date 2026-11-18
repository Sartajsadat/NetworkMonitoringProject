package gov.mot.netmon.agent.websites;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * NetMon's section of a hosts file. Only the lines between {@link #BEGIN} and {@link #END} are ever
 * read or changed; everything else in the file stays exactly as it was. Each blocked website adds its
 * base name, "www." and "m." pointing to 0.0.0.0 (IPv4) and :: (IPv6), so browsers cannot connect.
 * The same format is written by the Linux helper deploy/linux/netmon-hosts-apply.
 */
public final class HostsFile {

    public static final String BEGIN = "# BEGIN NetMon website blocking (managed by the NetMon agent - do not edit)";
    public static final String END = "# END NetMon website blocking";
    public static final int MAX_SITES = 500;
    static final List<String> PREFIXES = List.of("", "www.", "m.");
    static final Pattern DOMAIN = Pattern.compile("^(?=.{1,249}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");

    private HostsFile() {
    }

    /** Valid, de-duplicated, sorted base domains (anything else from the platform is ignored). */
    public static List<String> sanitize(List<String> in) {
        Set<String> out = new TreeSet<>();
        if (in == null) return List.of();
        for (String d : in) {
            if (d == null) continue;
            String s = d.trim().toLowerCase(Locale.ROOT);
            if (DOMAIN.matcher(s).matches() && !"localhost".equals(s)) out.add(s);
            if (out.size() >= MAX_SITES) break;
        }
        return List.copyOf(out);
    }

    /** Host names inside NetMon's section. */
    static Set<String> names(String content) {
        Set<String> names = new LinkedHashSet<>();
        boolean inside = false;
        for (String raw : content.split("\\r?\\n", -1)) {
            String line = raw.strip();
            if (line.equals(BEGIN)) { inside = true; continue; }
            if (line.equals(END)) { inside = false; continue; }
            if (!inside || line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            for (int i = 1; i < parts.length; i++) names.add(parts[i].toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /** Base domains fully blocked in NetMon's section (the name, www. and m. are all present). */
    public static List<String> blocked(String content) {
        Set<String> names = names(content);
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (PREFIXES.stream().allMatch(p -> names.contains(p + n))) out.add(n);
        }
        return out.stream().sorted().toList();
    }

    /** The file with NetMon's section replaced by these domains (removed when the list is empty). */
    public static String render(String content, List<String> domains) {
        String eol = content.contains("\r\n") ? "\r\n" : "\n";
        List<String> kept = new ArrayList<>();
        boolean inside = false;
        for (String raw : content.split("\\r?\\n", -1)) {
            String line = raw.strip();
            if (line.equals(BEGIN)) { inside = true; continue; }
            if (line.equals(END)) { inside = false; continue; }
            if (!inside) kept.add(raw);
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isBlank()) kept.remove(kept.size() - 1);
        StringBuilder b = new StringBuilder(String.join(eol, kept));
        if (b.length() > 0) b.append(eol);
        List<String> sites = sanitize(domains);
        if (!sites.isEmpty()) {
            b.append(eol).append(BEGIN).append(eol);
            for (String d : sites) {
                for (String p : PREFIXES) b.append("0.0.0.0 ").append(p).append(d).append(eol);
                for (String p : PREFIXES) b.append(":: ").append(p).append(d).append(eol);
            }
            b.append(END).append(eol);
        }
        return b.toString();
    }
}
