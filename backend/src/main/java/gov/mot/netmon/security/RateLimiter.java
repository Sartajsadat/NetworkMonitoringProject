package gov.mot.netmon.security;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory fixed-window limiter used for login and setup endpoints.
 * Suitable for a single backend instance; a shared store (e.g. Redis or the DB) is needed
 * if the backend is ever scaled horizontally.
 */
public class RateLimiter {

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    public RateLimiter(int limit, long windowMillis, Clock clock) {
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /** @return true if the call is allowed, false if the key exceeded its quota in the current window. */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue()[0] >= windowMillis);
        }
        long[] w = windows.compute(key, (k, cur) -> {
            if (cur == null || now - cur[0] >= windowMillis) {
                return new long[] {now, 1};
            }
            cur[1]++;
            return cur;
        });
        return w[1] <= limit;
    }

    public void reset() {
        windows.clear();
    }
}
