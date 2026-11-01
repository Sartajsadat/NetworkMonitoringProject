package gov.mot.netmon.agent;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EnrollmentTokenRepository extends JpaRepository<EnrollmentToken, UUID> {

    Optional<EnrollmentToken> findByTokenHash(String tokenHash);

    /** Invalidates older unused tokens when a new one is issued for the same server. */
    @Modifying
    @Query("update EnrollmentToken t set t.usedAt = :now where t.serverId = :serverId and t.usedAt is null")
    int invalidateUnused(UUID serverId, Instant now);

    @Modifying
    @Query("delete from EnrollmentToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(Instant cutoff);
}
