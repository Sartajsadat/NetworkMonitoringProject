package gov.mot.netmon.agent.firewall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The agent's own last line of defence: a BLOCK rule is refused if it covers loopback, the
 * platform's address, an address configured as protected, or an address connected to a management
 * port (SSH, RDP, WinRM) right now. The platform runs the same checks first; these run again here
 * because only the server sees its live sessions.
 */
public final class LocalSafety {

    private LocalSafety() {
    }

    /** Problems that make the rule unsafe here; empty if it may be applied. */
    public static List<String> problems(FwRule r, List<String> protectedList, List<String> sessions) {
        List<String> out = new ArrayList<>();
        if (!r.block()) return out;
        for (String p : protectedList) {
            if (Cidrs.overlaps(r.remote(), p)) out.add("it would block the protected address " + p);
        }
        for (String s : sessions) {
            if (Cidrs.overlaps(r.remote(), s)) out.add("it would cut the management session from " + s);
        }
        if ((r.remote().endsWith("/0")) && r.localPort() == null && r.remotePort() == null) {
            out.add("it would block every address");
        }
        return out;
    }

    /**
     * Remote addresses of ESTABLISHED TCP connections to local management ports, from
     * /proc/net/tcp and /proc/net/tcp6 (Linux).
     */
    public static List<String> linuxSessions(Path procRoot, List<Integer> ports) {
        Set<String> out = new LinkedHashSet<>();
        for (String f : List.of("net/tcp", "net/tcp6")) {
            Path file = procRoot.resolve(f);
            if (!Files.isReadable(file)) continue;
            try {
                for (String line : Files.readAllLines(file)) {
                    String[] c = line.trim().split("\\s+");
                    if (c.length < 4 || !c[3].equals("01")) continue; // 01 = ESTABLISHED
                    String[] local = c[1].split(":");
                    String[] remote = c[2].split(":");
                    if (local.length != 2 || remote.length != 2) continue;
                    int port = Integer.parseInt(local[1], 16);
                    if (!ports.contains(port)) continue;
                    String addr = decode(remote[0]);
                    if (addr != null) out.add(addr);
                }
            } catch (IOException | RuntimeException e) {
                // unreadable: no sessions known from this file
            }
        }
        return new ArrayList<>(out);
    }

    /** /proc/net/tcp addresses are 32-bit words in host (little-endian) order. */
    static String decode(String hex) {
        try {
            byte[] b = new byte[hex.length() / 2];
            for (int w = 0; w < b.length / 4; w++) {
                for (int i = 0; i < 4; i++) {
                    b[w * 4 + i] = (byte) Integer.parseInt(hex.substring(w * 8 + (3 - i) * 2, w * 8 + (3 - i) * 2 + 2), 16);
                }
            }
            if (b.length == 16) {
                boolean mapped = true;
                for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
                mapped &= b[10] == (byte) 0xff && b[11] == (byte) 0xff;
                if (mapped) b = java.util.Arrays.copyOfRange(b, 12, 16); // ::ffff:a.b.c.d
            }
            return Cidrs.format(b);
        } catch (Exception e) {
            return null;
        }
    }
}
