package gov.mot.netmon.capture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CaptureSegmentRepository extends JpaRepository<CaptureSegment, Long> {

    List<CaptureSegment> findByCaptureIdOrderBySeq(UUID captureId);

    Optional<CaptureSegment> findByCaptureIdAndSeq(UUID captureId, int seq);

    void deleteByCaptureId(UUID captureId);
}
