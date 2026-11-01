package gov.mot.netmon.capture;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.config.AppProperties;

/**
 * Capture files on disk: {storageDir}/{captureId}/segment-{seq}.pcapng. Paths are built only from
 * UUIDs and integers, never from user input. Files and folders are readable by the backend user only.
 * The folder is not served by the web server; downloads go through the authorized API.
 */
@Component
public class CaptureStorage {

    private final Path root;
    private final long minFreeBytes;

    public CaptureStorage(AppProperties props) {
        this.root = Path.of(props.capture().storageDir()).toAbsolutePath().normalize();
        this.minFreeBytes = props.capture().minFreeDiskBytes();
    }

    public record Stored(String fileName, long size, String sha256) {
    }

    public Path root() {
        return root;
    }

    public Path dir(UUID captureId) {
        return root.resolve(captureId.toString());
    }

    public Path segmentFile(UUID captureId, String fileName) {
        Path p = dir(captureId).resolve(fileName).normalize();
        if (!p.startsWith(dir(captureId))) throw new IllegalArgumentException("invalid segment path");
        return p;
    }

    /** Streams an upload to disk, refusing it as soon as it exceeds {@code maxSize}. */
    public Stored storeSegment(UUID captureId, int seq, InputStream in, long maxSize) throws IOException {
        ensureDir(root);
        ensureDir(dir(captureId));
        if (usableSpace() < minFreeBytes) {
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "STORAGE_FULL",
                    "Capture storage has less free disk space than the configured minimum");
        }
        String name = String.format("segment-%06d.pcapng", seq);
        Path tmp = Files.createTempFile(dir(captureId), ".upload", ".tmp");
        try {
            restrict(tmp);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream din = new DigestInputStream(in, md); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = din.read(buf)) > 0) {
                    total += n;
                    if (total > maxSize) {
                        throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "CAPTURE_SIZE_LIMIT",
                                "Upload exceeds the capture's size limit");
                    }
                    out.write(buf, 0, n);
                }
            }
            if (total == 0) throw ApiException.badRequest("EMPTY_SEGMENT", "Empty upload");
            Files.move(tmp, segmentFile(captureId, name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new Stored(name, total, HexFormat.of().formatHex(md.digest()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public void deleteCapture(UUID captureId) throws IOException {
        Path d = dir(captureId);
        if (!Files.exists(d)) return;
        try (Stream<Path> walk = Files.walk(d)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    public long usableSpace() throws IOException {
        ensureDir(root);
        FileStore fs = Files.getFileStore(root);
        return fs.getUsableSpace();
    }

    private static void ensureDir(Path d) throws IOException {
        if (!Files.isDirectory(d)) {
            Files.createDirectories(d);
            if (Files.getFileAttributeView(d, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    private static void restrict(Path f) throws IOException {
        if (Files.getFileAttributeView(f, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        }
    }
}
