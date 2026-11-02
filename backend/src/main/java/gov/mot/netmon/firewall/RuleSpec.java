package gov.mot.netmon.firewall;

/**
 * A validated, normalised rule. "Remote" is the other side (source of inbound traffic, destination
 * of outbound traffic); "local" is the target itself. Addresses are canonical CIDR strings; ports
 * are "22" or "8000-8080" and only set for TCP/UDP.
 */
public record RuleSpec(String action, String direction, String remoteCidr, String localCidr, String protocol, String localPort,
        String remotePort) {

    public boolean block() {
        return "BLOCK".equals(action);
    }

    public boolean inbound() {
        return "INBOUND".equals(direction);
    }
}
