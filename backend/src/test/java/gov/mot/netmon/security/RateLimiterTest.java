package gov.mot.netmon.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void blocksAfterLimitAndResetsAfterWindow() {
        AtomicLong now = new AtomicLong(1_000_000);
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        RateLimiter limiter = new RateLimiter(3, 60_000, clock);
        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isFalse();
        assertThat(limiter.tryAcquire("other")).isTrue();
        now.addAndGet(60_000);
        assertThat(limiter.tryAcquire("ip")).isTrue();
    }
}
