package gov.mot.netmon.capture;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface PacketCaptureRepository extends JpaRepository<PacketCapture, UUID>, JpaSpecificationExecutor<PacketCapture> {

    List<PacketCapture> findByServerIdAndStatusIn(UUID serverId, Collection<CaptureStatus> statuses);

    List<PacketCapture> findByStatusIn(Collection<CaptureStatus> statuses);

    long countByStatusIn(Collection<CaptureStatus> statuses);

    @Query("select coalesce(sum(c.bytesStored), 0) from PacketCapture c where c.filesDeletedAt is null")
    long totalStoredBytes();

    @Query("select coalesce(sum(c.maxBytes), 0) from PacketCapture c where c.status in :statuses")
    long reservedBytes(Collection<CaptureStatus> statuses);

    List<PacketCapture> findByExpiresAtBeforeAndFilesDeletedAtIsNull(Instant cutoff);

    List<PacketCapture> findByRequestedAtBeforeAndFilesDeletedAtIsNull(Instant cutoff);

    /**
     * Indexing runs in the background while the agent keeps updating the capture, so the indexer
     * changes only its own counters, never the whole row (that would undo a status change).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE packet_captures SET packets_indexed = packets_indexed + :packets, "
            + "index_truncated = index_truncated OR :truncated WHERE id = :id", nativeQuery = true)
    int addIndexed(@org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("packets") long packets,
            @org.springframework.data.repository.query.Param("truncated") boolean truncated);
}
