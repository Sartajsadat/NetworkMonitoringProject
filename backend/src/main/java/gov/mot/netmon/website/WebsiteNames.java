package gov.mot.netmon.website;

import java.net.IDN;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns what an administrator types ("https://www.Facebook.com/home") into the base domain that is
 * blocked ("facebook.com"), and refuses anything that is not a plain public host name.
 * Devices block the base domain plus its "www." and "m." names (see {@link #namesOnDevice}).
 */
public final class WebsiteNames {

    static final Pattern DOMAIN = Pattern.compile("^(?=.{1,249}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");
    static final List<String> PREFIXES = List.of("", "www.", "m.");

    private WebsiteNames() {
    }

    /** The base domain, or null if the text is not a website name. */
    public static String normalize(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        int cut = indexOfAny(s, "/?#");
        if (cut >= 0) s = s.substring(0, cut);
        if (s.contains("@")) return null; // user@host forms are not website names
        s = s.replaceFirst(":\\d{1,5}$", "");
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("*.")) s = s.substring(2);
        for (String p : List.of("www.", "m.")) {
            if (s.startsWith(p) && s.indexOf('.', p.length()) > 0) {
                s = s.substring(p.length());
                break;
            }
        }
        try {
            s = IDN.toASCII(s, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return DOMAIN.matcher(s).matches() ? s : null;
    }

    /** The host names written into a device's hosts file for one blocked base domain. */
    public static List<String> namesOnDevice(String domain) {
        return PREFIXES.stream().map(p -> p + domain).toList();
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (char c : chars.toCharArray()) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }
}
