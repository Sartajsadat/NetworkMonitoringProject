package gov.mot.netmon.server;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import gov.mot.netmon.server.ServerEnums.GroupType;

public interface ServerGroupRepository extends JpaRepository<ServerGroup, UUID> {

    boolean existsByGroupTypeAndNameIgnoreCase(GroupType type, String name);

    boolean existsByGroupTypeAndNameIgnoreCaseAndIdNot(GroupType type, String name, UUID id);

    /** Group id with member count, used for list views without N+1 queries. */
    @Query("select g.id, count(s) from Server s join s.groups g group by g.id")
    List<Object[]> countMembersPerGroup();
}
