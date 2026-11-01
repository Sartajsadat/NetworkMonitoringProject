package gov.mot.netmon.capture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * One-time download tickets. The web UI keeps its access token in memory, so a plain link cannot
 * carry it; instead the UI asks for a ticket (an authorised, audited call) and lets the browser
 * download natively with {@code ?ticket=}. A ticket works once, for one capture, for 60 seconds,
 * and only its hash is kept.
 */
@Component
public class DownloadTickets {

    static final Duration TTL = Duration.ofSeconds(60);

    public record Ticket(UUID captureId, UUID userId, String username, Instant expiresAt) {
    }

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public DownloadTickets(Clock clock) {
        this.clock = clock;
    }

    public String issue(UUID captureId, UUID userId, String username) {
        Instant now = clock.instant();
        tickets.values().removeIf(t -> t.expiresAt().isBefore(now));
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tickets.put(hash(token), new Ticket(captureId, userId, username, now.plus(TTL)));
        return token;
    }

    /** Returns the ticket and invalidates it, or null if unknown, used or expired. */
    public Ticket redeem(String token) {
        if (token == null || token.length() != 43) return null;
        Ticket t = tickets.remove(hash(token));
        return t == null || t.expiresAt().isBefore(clock.instant()) ? null : t;
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
