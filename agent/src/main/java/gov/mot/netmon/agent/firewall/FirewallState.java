package gov.mot.netmon.agent.firewall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;

import gov.mot.netmon.agent.model.Json;

/**
 * Rules this agent applied, kept in a local file so they can still be removed at their expiry
 * while the platform is unreachable, and found again after a restart.
 */
public class FirewallState {

    public record Entry(UUID ruleId, String tag, String description, Instant appliedAt, Instant expiresAt, Instant removedAt) {
    }

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public FirewallState(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try {
                entries.addAll(Json.MAPPER.readValue(file.toFile(), new TypeReference<List<Entry>>() { }));
            } catch (IOException e) {
                java.util.logging.Logger.getLogger(FirewallState.class.getName()).warning("Unreadable " + file + ": " + e.getMessage());
            }
        }
    }

    public synchronized List<Entry> all() {
        return List.copyOf(entries);
    }

    public synchronized void applied(FwRule r, Instant now) {
        applied(r.ruleId(), r.tag(), r.describe(), r.expiresAt(), now);
    }

    public synchronized void applied(UUID ruleId, String tag, String description, Instant expiresAt, Instant now) {
        entries.removeIf(e -> e.tag().equals(tag));
        entries.add(new Entry(ruleId, tag, description, now, expiresAt, null));
        save();
    }

    public synchronized void removed(String tag, Instant now) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.tag().equals(tag) && e.removedAt() == null) {
                entries.set(i, new Entry(e.ruleId(), e.tag(), e.description(), e.appliedAt(), e.expiresAt(), now));
            }
        }
        // Keep removed entries for a week as a local record.
        entries.removeIf(e -> e.removedAt() != null && e.removedAt().isBefore(now.minusSeconds(7 * 86400)));
        save();
    }

    public synchronized Entry find(String tag) {
        return entries.stream().filter(e -> e.tag().equals(tag)).findFirst().orElse(null);
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Json.MAPPER.writeValue(tmp.toFile(), entries);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            java.util.logging.Logger.getLogger(FirewallState.class.getName()).warning("Could not save " + file + ": " + e.getMessage());
        }
    }
}
