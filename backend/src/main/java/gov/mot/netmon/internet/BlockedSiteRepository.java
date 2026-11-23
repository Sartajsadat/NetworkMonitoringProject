package gov.mot.netmon.internet;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BlockedSiteRepository extends JpaRepository<BlockedSite, UUID> {

    List<BlockedSite> findByServerIdOrderByDomainAsc(UUID serverId);

    List<BlockedSite> findByServerIdAndBlockedTrueOrderByDomainAsc(UUID serverId);

    Optional<BlockedSite> findByServerIdAndDomain(UUID serverId, String domain);

    long countByServerId(UUID serverId);
}
