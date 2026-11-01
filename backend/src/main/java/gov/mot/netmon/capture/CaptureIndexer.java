package gov.mot.netmon.capture;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import gov.mot.netmon.config.AppProperties;

/**
 * Extracts packet metadata from uploaded segments with TShark, in segment order, on one background
 * thread (so analysis never blocks agent uploads and never runs many TShark processes at once).
 */
@Component
public class CaptureIndexer {

    private static final Logger log = LoggerFactory.getLogger(CaptureIndexer.class);

    private final CaptureSegmentRepository segments;
    private final PacketCaptureRepository captures;
    private final PacketStore packets;
    private final CaptureStorage storage;
    private final Tshark tshark;
    private final TransactionTemplate tx;
    private final long maxIndexed;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "capture-indexer");
        t.setDaemon(true);
        return t;
    });

    public CaptureIndexer(CaptureSegmentRepository segments, PacketCaptureRepository captures, PacketStore packets,
            CaptureStorage storage, Tshark tshark, PlatformTransactionManager txm, AppProperties props) {
        this.segments = segments;
        this.captures = captures;
        this.packets = packets;
        this.storage = storage;
        this.tshark = tshark;
        this.tx = new TransactionTemplate(txm);
        this.maxIndexed = props.capture().maxIndexedPackets();
    }

    public void schedule(UUID captureId) {
        executor.submit(() -> {
            try {
                indexPending(captureId);
            } catch (RuntimeException e) {
                log.error("Indexing capture {} failed", captureId, e);
            }
        });
    }

    /** Synchronous variant (used by tests). Indexes all pending segments that are next in order. */
    public void indexPending(UUID captureId) {
        long nextFrame = 1;
        int expectedSeq = 1;
        for (CaptureSegment seg : segments.findByCaptureIdOrderBySeq(captureId)) {
            if (seg.getSeq() != expectedSeq) {
                return; // a piece is still missing: wait until it arrives so frame numbers stay correct
            }
            expectedSeq++;
            if (!"PENDING".equals(seg.getIndexStatus())) {
                nextFrame += seg.getPacketCount() == null ? 0 : seg.getPacketCount();
                continue;
            }
            nextFrame += indexOne(captureId, seg.getId(), nextFrame);
        }
    }

    private int indexOne(UUID captureId, long segmentId, long firstFrame) {
        Integer count = tx.execute(status -> {
            CaptureSegment seg = segments.findById(segmentId).orElseThrow();
            PacketCapture capture = captures.findById(captureId).orElseThrow();
            Path file = storage.segmentFile(captureId, seg.getFileName());
            try {
                List<Tshark.SummaryRow> rows = tshark.summaries(file);
                long room = Math.max(0, maxIndexed - capture.getPacketsIndexed());
                List<Tshark.SummaryRow> kept = rows.size() > room ? rows.subList(0, (int) room) : rows;
                packets.insert(captureId, seg.getSeq(), firstFrame, kept);
                seg.indexed(firstFrame, rows.size());
                capture.addIndexed(kept.size(), kept.size() < rows.size());
                return rows.size();
            } catch (RuntimeException e) {
                log.warn("Segment {} of capture {} could not be analysed: {}", seg.getSeq(), captureId, e.getMessage());
                seg.indexFailed(firstFrame, e.getMessage());
                return 0;
            }
        });
        return count == null ? 0 : count;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
