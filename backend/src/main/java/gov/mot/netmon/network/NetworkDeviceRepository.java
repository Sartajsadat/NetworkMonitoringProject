package gov.mot.netmon.network;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NetworkDeviceRepository extends JpaRepository<NetworkDevice, UUID> {

    List<NetworkDevice> findAllByOrderByNameAsc();

    Optional<NetworkDevice> findByExporterIp(String exporterIp);

    boolean existsByName(String name);
}
