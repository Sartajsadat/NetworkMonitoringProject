package gov.mot.netmon.agent.sites;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The NetMon section of a hosts file. Blocked websites are written between two marker lines and
 * pointed at 0.0.0.0 / :: so the name no longer resolves. Everything outside the markers is kept
 * exactly as it was. The section is rewritten as a whole, so removing a site removes its lines.
 */
public final class HostsFile {

    public static final String BEGIN = "# BEGIN NetMon blocked websites (managed by the NetMon agent - do not edit)";
    public static final String END = "# END NetMon blocked websites";
    public static final int MAX_SITES = 200;
    /** Lower-case host name with at least one dot; no wildcards, no addresses. */
    static final Pattern DOMAIN = Pattern.compile("^(?=.{3,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$");
    /** Hosts files have no wildcards: these common prefixes are blocked together with the name. */
    static final List<String> PREFIXES = List.of("www.", "m.");

    private HostsFile() {
    }

    public static boolean valid(String domain) {
        return domain != null && DOMAIN.matcher(domain).matches();
    }

    /** Valid, distinct, sorted, at most MAX_SITES. Invalid entries are dropped. */
    public static List<String> clean(Collection<String> domains) {
        TreeSet<String> out = new TreeSet<>();
        if (domains != null) {
            for (String d : domains) {
                String x = d == null ? null : d.trim().toLowerCase(Locale.ROOT);
                if (valid(x) && out.size() < MAX_SITES) out.add(x);
            }
        }
        return List.copyOf(out);
    }

    /** The names written for one blocked site: the name itself, www. and m. (unless already prefixed). */
    static List<String> names(String domain) {
        List<String> out = new ArrayList<>();
        out.add(domain);
        if (PREFIXES.stream().noneMatch(domain::startsWith)) {
            for (String p : PREFIXES) out.add(p + domain);
        }
        return out;
    }

    /** The sites currently blocked by NetMon (the names in the section, without the added prefixes). */
    public static List<String> blocked(String text) {
        TreeSet<String> all = new TreeSet<>();
        boolean in = false;
        for (String line : text.split("\\R", -1)) {
            String t = line.trim();
            if (t.equals(BEGIN)) { in = true; continue; }
            if (t.equals(END)) { in = false; continue; }
            if (!in || t.isEmpty() || t.startsWith("#")) continue;
            String[] parts = t.split("\\s+");
            for (int i = 1; i < parts.length; i++) all.add(parts[i].toLowerCase(Locale.ROOT));
        }
        TreeSet<String> sites = new TreeSet<>(all);
        for (String n : all) {
            for (String p : PREFIXES) {
                if (n.startsWith(p) && all.contains(n.substring(p.length()))) sites.remove(n);
            }
        }
        return List.copyOf(sites);
    }

    /** The file with NetMon's section replaced by {@code domains} (removed when the list is empty). */
    public static String render(String text, List<String> domains) {
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder kept = new StringBuilder();
        boolean in = false;
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.equals(BEGIN)) { in = true; continue; }
            if (t.equals(END)) { in = false; continue; }
            if (in) continue;
            if (i == lines.length - 1 && lines[i].isEmpty()) break; // trailing newline
            kept.append(lines[i]).append(nl);
        }
        String out = kept.toString();
        while (out.endsWith(nl + nl)) out = out.substring(0, out.length() - nl.length());
        if (domains.isEmpty()) return out;
        StringBuilder b = new StringBuilder(out);
        if (!out.isEmpty()) b.append(nl);
        b.append(BEGIN).append(nl);
        for (String d : domains) {
            for (String n : names(d)) {
                b.append("0.0.0.0 ").append(n).append(nl);
                b.append(":: ").append(n).append(nl);
            }
        }
        b.append(END).append(nl);
        return b.toString();
    }
}
