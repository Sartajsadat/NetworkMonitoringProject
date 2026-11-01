package gov.mot.netmon.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strongly typed view of the {@code app.*} configuration (all values come from the environment). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Security security, Bootstrap bootstrap, Monitoring monitoring, Agents agents) {

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

    /**
     * @param onlineThreshold  a server is ONLINE if its agent reported within this window
     * @param rawRetention     how long raw metric samples are kept
     * @param rollupRetention  how long 5-minute rollups are kept
     */
    public record Monitoring(Duration onlineThreshold, Duration rawRetention, Duration rollupRetention) {
    }

    /**
     * @param enrollmentTokenTtl       lifetime of one-time enrollment tokens
     * @param credentialGracePeriod    how long the previous secret keeps working after rotation
     * @param defaultReportInterval    report interval for newly enrolled agents
     * @param publicUrl                URL agents use to reach the platform (shown in install commands)
     */
    public record Agents(Duration enrollmentTokenTtl, Duration credentialGracePeriod, int defaultReportInterval,
            String publicUrl) {
    }
}
