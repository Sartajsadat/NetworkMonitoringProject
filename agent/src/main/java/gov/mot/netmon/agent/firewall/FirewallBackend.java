package gov.mot.netmon.agent.firewall;

import java.util.List;
import java.util.Set;

/**
 * One local firewall manager. Implementations run fixed executables with argument lists only.
 * Every NetMon rule carries the comment/name {@code netmon:<tag>} so it can be found, verified and
 * removed without touching anything else.
 */
public interface FirewallBackend {

    /** NFTABLES, UFW, WINDOWS, NONE or UNSUPPORTED. */
    String name();

    /** Null if the backend can be used; otherwise why not. */
    String problem();

    /** The exact command, for the audit trail. */
    String describe(FwRule r);

    /** Validates the rule with the firewall's own check mode. Changes nothing. */
    void check(FwRule r) throws FirewallException;

    void apply(FwRule r) throws FirewallException;

    /** Tags of the NetMon rules currently present in the firewall. */
    Set<String> tags() throws FirewallException;

    /** @return false if the rule was not present */
    boolean remove(String tag) throws FirewallException;

    /** Application identities this firewall can enforce (Phase 8), e.g. LINUX_USER. */
    default List<String> applicationControl() {
        return List.of();
    }

    default String describeApp(AppPolicy p) {
        return "(" + name() + " cannot match applications)";
    }

    /** Checks an application policy without changing anything. */
    default void checkApp(AppPolicy p) throws FirewallException {
        throw new FirewallException(name() + " cannot match applications by " + p.identityKind());
    }

    default void applyApp(AppPolicy p) throws FirewallException {
        throw new FirewallException(name() + " cannot match applications by " + p.identityKind());
    }

    /** Addresses connected to local management ports right now (SSH/RDP/WinRM), if the backend knows them. */
    default List<String> managementSessions() {
        return List.of();
    }

    final class FirewallException extends Exception {
        public FirewallException(String message) {
            super(message);
        }
    }

    /** No supported firewall: everything stays a dry run. */
    record None(String name, String problem) implements FirewallBackend {
        @Override
        public String describe(FwRule r) {
            return "(no supported firewall: " + problem + ")";
        }

        @Override
        public void check(FwRule r) {
        }

        @Override
        public void apply(FwRule r) throws FirewallException {
            throw new FirewallException(problem);
        }

        @Override
        public Set<String> tags() {
            return Set.of();
        }

        @Override
        public boolean remove(String tag) {
            return false;
        }
    }

    static String firstLine(String s) {
        if (s == null) return "";
        String t = s.strip();
        int nl = t.indexOf('\n');
        String line = nl < 0 ? t : t.substring(0, nl);
        // Prefer the actual error line from nft/ufw output.
        for (String l : t.split("\n")) {
            if (l.startsWith("Error") || l.startsWith("ERROR")) {
                line = l;
                break;
            }
        }
        return line.length() > 300 ? line.substring(0, 300) : line;
    }
}
