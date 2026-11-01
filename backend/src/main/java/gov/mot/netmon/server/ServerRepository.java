package gov.mot.netmon.server;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ServerRepository extends JpaRepository<Server, UUID>, JpaSpecificationExecutor<Server> {

    @Query("select count(s) > 0 from Server s where lower(s.name) = lower(:name)")
    boolean existsByNameIgnoreCase(String name);

    @Query("select count(s) > 0 from Server s where lower(s.name) = lower(:name) and s.id <> :id")
    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    @Query("select distinct s.organization from Server s order by s.organization")
    List<String> findDistinctOrganizations();

    @Query("select count(s) from Server s join s.groups g where g.id = :groupId")
    long countByGroupId(UUID groupId);
}
