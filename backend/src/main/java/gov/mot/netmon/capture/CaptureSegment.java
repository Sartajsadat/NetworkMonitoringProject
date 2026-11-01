package gov.mot.netmon.capture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "capture_segments")
public class CaptureSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "capture_id", nullable = false)
    private UUID captureId;
    @Column(nullable = false)
    private int seq;
    @Column(name = "file_name", nullable = false)
    private String fileName;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(nullable = false)
    private String sha256;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @Column(name = "first_frame")
    private Long firstFrame;
    @Column(name = "packet_count")
    private Integer packetCount;
    @Column(name = "index_status", nullable = false)
    private String indexStatus = "PENDING";
    @Column(name = "index_error")
    private String indexError;

    protected CaptureSegment() {
    }

    public CaptureSegment(UUID captureId, int seq, String fileName, long sizeBytes, String sha256, Instant receivedAt) {
        this.captureId = captureId;
        this.seq = seq;
        this.fileName = fileName;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.receivedAt = receivedAt;
    }

    public void indexed(long firstFrame, int packets) {
        this.firstFrame = firstFrame;
        this.packetCount = packets;
        this.indexStatus = "DONE";
        this.indexError = null;
    }

    public void indexFailed(long firstFrame, String error) {
        this.firstFrame = firstFrame;
        this.packetCount = 0;
        this.indexStatus = "FAILED";
        this.indexError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }

    public Long getId() { return id; }
    public UUID getCaptureId() { return captureId; }
    public int getSeq() { return seq; }
    public String getFileName() { return fileName; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public Instant getReceivedAt() { return receivedAt; }
    public Long getFirstFrame() { return firstFrame; }
    public Integer getPacketCount() { return packetCount; }
    public String getIndexStatus() { return indexStatus; }
    public String getIndexError() { return indexError; }
}
