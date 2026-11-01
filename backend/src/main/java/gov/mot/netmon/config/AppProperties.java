package gov.mot.netmon.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strongly typed view of the {@code app.*} configuration (all values come from the environment). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Security security, Bootstrap bootstrap, Monitoring monitoring, Agents agents, Capture capture, Events events,
        Alerts alerts) {

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

    /**
     * Packet capture limits and storage.
     *
     * @param storageDir         folder for PCAPNG files (readable only by the backend)
     * @param quotaBytes         total size of all stored capture files
     * @param minFreeDiskBytes   uploads are refused when the disk has less free space than this
     * @param maxDurationSeconds longest capture that can be requested
     * @param maxBytesPerCapture largest capture that can be requested
     * @param defaultSnaplen     bytes kept per packet unless the requester asks for more (headers only)
     * @param defaultRetentionDays / maxRetentionDays  how long files and packet metadata are kept
     * @param maxIndexedPackets  packets per capture whose metadata is stored for the packet table
     * @param tsharkPath         TShark executable (fixed path, never from user input)
     * @param tsharkTimeout      limit for one TShark run
     * @param pendingTimeout     a capture the agent has not picked up within this time fails
     */
    public record Capture(String storageDir, long quotaBytes, long minFreeDiskBytes, int maxDurationSeconds,
            long maxBytesPerCapture, int defaultSnaplen, int defaultRetentionDays, int maxRetentionDays,
            long maxIndexedPackets, String tsharkPath, Duration tsharkTimeout, Duration pendingTimeout) {
    }

    /**
     * @param retention           events older than this are deleted
     * @param maxPerServerPerHour flood protection; extra events in the same hour are dropped (and that is recorded)
     */
    public record Events(Duration retention, int maxPerServerPerHour) {
    }

    /**
     * @param evaluationInterval how often alert rules are evaluated
     * @param resolvedRetention  resolved alerts older than this are deleted
     */
    public record Alerts(Duration evaluationInterval, Duration resolvedRetention) {
    }
}
