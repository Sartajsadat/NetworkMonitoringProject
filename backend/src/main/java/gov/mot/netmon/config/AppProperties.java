package gov.mot.netmon.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strongly typed view of the {@code app.*} configuration (all values come from the environment). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Security security, Bootstrap bootstrap, Monitoring monitoring) {

    public record Security(
            String jwtSecret,
            String jwtIssuer,
            Duration accessTokenTtl,
            Duration refreshTokenTtl,
            boolean refreshCookieSecure,
            int maxFailedLogins,
            Duration lockoutDuration,
            int loginRateLimitPerMinute,
            List<String> corsAllowedOrigins) {
    }

    public record Bootstrap(String setupToken) {
    }

    public record Monitoring(Duration onlineThreshold) {
    }
}
