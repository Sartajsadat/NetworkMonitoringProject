package gov.mot.netmon.agent.config;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gov.mot.netmon.agent.model.Json;

/**
 * Stores the agent credential in {@code <state.dir>/credentials.json}, readable only by the agent's
 * own user (mode 0600). Writes are atomic so a crash never leaves a half-written secret.
 */
public class CredentialStore {

    public record Credentials(UUID agentId, String secret, UUID serverId, String serverName, String platformUrl,
            Instant enrolledAt, Instant rotatedAt) {

        public String authorizationHeader() {
            return "Agent " + agentId + ":" + secret;
        }

        public Credentials withSecret(String newSecret, Instant at) {
            return new Credentials(agentId, newSecret, serverId, serverName, platformUrl, enrolledAt, at);
        }

        @Override
        public String toString() {
            return "Credentials[agentId=" + agentId + ", server=" + serverName + "]";
        }
    }

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final Path file;

    public CredentialStore(Path stateDir) {
        this.file = stateDir.resolve("credentials.json");
    }

    public Path file() {
        return file;
    }

    public Optional<Credentials> load() throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (view != null) {
            Set<PosixFilePermission> perms = view.readAttributes().permissions();
            if (perms.stream().anyMatch(p -> p.name().startsWith("GROUP") || p.name().startsWith("OTHERS"))) {
                throw new IOException(file + " is readable by other users; run: chmod 600 " + file);
            }
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                String broad = broadReader(acl.getAcl());
                if (broad != null) {
                    throw new IOException(file + " is readable by '" + broad + "'. Restrict it to the agent account, "
                            + "SYSTEM and Administrators (deploy/windows/install-agent.ps1 does this).");
                }
            }
        }
        return Optional.of(Json.MAPPER.readValue(file.toFile(), Credentials.class));
    }

    /** Windows: name of a broad group (Everyone, Users, Authenticated Users) that may read the file, else null. */
    static String broadReader(java.util.List<AclEntry> entries) {
        for (AclEntry e : entries) {
            String who = e.principal().getName().toLowerCase(java.util.Locale.ROOT);
            boolean broad = who.equals("everyone") || who.endsWith("\\everyone") || who.endsWith("\\users")
                    || who.endsWith("\\authenticated users") || who.equals("users") || who.equals("authenticated users");
            if (broad && e.type() == AclEntryType.ALLOW && e.permissions().contains(AclEntryPermission.READ_DATA)) {
                return e.principal().getName();
            }
        }
        return null;
    }

    public void save(Credentials c) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), ".credentials", ".tmp");
        try {
            if (Files.getFileAttributeView(tmp, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(tmp, OWNER_ONLY);
            }
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), c);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
