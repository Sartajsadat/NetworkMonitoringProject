package gov.mot.netmon.network;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubnetRepository extends JpaRepository<Subnet, UUID> {

    boolean existsByCidr(String cidr);

    List<Subnet> findAllByOrderByIpVersionAscCidrAsc();
}
