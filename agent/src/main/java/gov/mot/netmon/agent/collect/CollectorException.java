package gov.mot.netmon.agent.collect;

/** Lets a collector report an explicit status (UNSUPPORTED, UNAVAILABLE, PERMISSION_DENIED, ERROR). */
public class CollectorException extends Exception {

    private final String status;

    public CollectorException(String status, String message) {
        super(message);
        this.status = status;
    }

    public String status() {
        return status;
    }
}
