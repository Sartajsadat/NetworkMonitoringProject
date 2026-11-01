package gov.mot.netmon.agent.collect;

/** Per-second rate from two counter readings. Null when unknown or when the counter reset/wrapped. */
public final class Rates {

    private Rates() {
    }

    public static Double perSecond(Long previous, Long current, double seconds) {
        if (previous == null || current == null || seconds <= 0 || current < previous) {
            return null;
        }
        return (current - previous) / seconds;
    }

    public static Double round(Double v, int decimals) {
        if (v == null || v.isNaN() || v.isInfinite()) return null;
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    public static Double clampPercent(Double v) {
        if (v == null) return null;
        return Math.max(0.0, Math.min(100.0, v));
    }
}
