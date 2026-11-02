package gov.mot.netmon.network;

/** Where an address was learned. Only "seen" sources prove the address was in use at that time. */
public enum IpSource {
    MANUAL(false), IMPORT(false), SERVER_REGISTRY(false), DEVICE(false),
    AGENT_INTERFACE(true), NEIGHBOR(true), FLOW(true);

    public final boolean evidenceOfUse;

    IpSource(boolean evidenceOfUse) {
        this.evidenceOfUse = evidenceOfUse;
    }
}
