package gov.mot.netmon.network;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.network.NetworkDtos.DeviceRequest;
import gov.mot.netmon.network.NetworkDtos.DeviceView;
import gov.mot.netmon.network.NetworkDtos.ImportResult;
import gov.mot.netmon.network.NetworkDtos.IpDetail;
import gov.mot.netmon.network.NetworkDtos.IpRequest;
import gov.mot.netmon.network.NetworkDtos.IpView;
import gov.mot.netmon.network.NetworkDtos.Seen;
import gov.mot.netmon.network.NetworkDtos.SubnetRequest;
import gov.mot.netmon.network.NetworkDtos.SubnetView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Subnets, IP addresses and network devices. Everyone signed in can read; network admins change. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "IP inventory")
public class NetworkController {

    private final IpInventoryService ips;
    private final NetworkDeviceService devices;

    public NetworkController(IpInventoryService ips, NetworkDeviceService devices) {
        this.ips = ips;
        this.devices = devices;
    }

    @GetMapping("/subnets")
    public List<SubnetView> subnets() {
        return ips.subnets();
    }

    @GetMapping("/subnets/{id}")
    public SubnetView subnet(@PathVariable UUID id) {
        return ips.subnet(id);
    }

    @PostMapping("/subnets")
    @PreAuthorize(NetworkAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public SubnetView createSubnet(@Valid @RequestBody SubnetRequest req) {
        return ips.createSubnet(req);
    }

    @PutMapping("/subnets/{id}")
    @PreAuthorize(NetworkAccess.MANAGE)
    public SubnetView updateSubnet(@PathVariable UUID id, @Valid @RequestBody SubnetRequest req) {
        return ips.updateSubnet(id, req);
    }

    @DeleteMapping("/subnets/{id}")
    @PreAuthorize(NetworkAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubnet(@PathVariable UUID id) {
        ips.deleteSubnet(id);
    }

    @GetMapping("/ip-addresses")
    @Operation(summary = "Search the IP inventory (q matches address, host name or MAC)")
    public PageResponse<IpView> search(@RequestParam(required = false) String q, @RequestParam(required = false) UUID subnetId,
            @RequestParam(defaultValue = "false") boolean noSubnet, @RequestParam(required = false) String source,
            @RequestParam(required = false) Seen seen, @RequestParam(required = false) UUID serverId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ips.search(q == null || q.length() <= 100 ? q : q.substring(0, 100), subnetId, noSubnet, source, seen, serverId,
                page, size);
    }

    @GetMapping("/ip-addresses/{id}")
    public IpDetail ip(@PathVariable UUID id) {
        return ips.detail(id);
    }

    @PostMapping("/ip-addresses")
    @PreAuthorize(NetworkAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public IpView createIp(@Valid @RequestBody IpRequest req) {
        return ips.create(req);
    }

    @PutMapping("/ip-addresses/{id}")
    @PreAuthorize(NetworkAccess.MANAGE)
    public IpView updateIp(@PathVariable UUID id, @Valid @RequestBody IpRequest req) {
        return ips.update(id, req);
    }

    @DeleteMapping("/ip-addresses/{id}")
    @PreAuthorize(NetworkAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIp(@PathVariable UUID id) {
        ips.delete(id);
    }

    @PostMapping(value = "/ip-addresses/import", consumes = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize(NetworkAccess.MANAGE)
    @Operation(summary = "Import CSV lines: address,mac,hostname,allocation (e.g. a DHCP or DNS export)")
    public ImportResult importCsv(@RequestBody String csv) {
        return ips.importCsv(csv);
    }

    @GetMapping("/network-devices")
    public List<DeviceView> devices() {
        return devices.list();
    }

    @GetMapping("/network-devices/{id}")
    public DeviceView device(@PathVariable UUID id) {
        return devices.get(id);
    }

    @PostMapping("/network-devices")
    @PreAuthorize(NetworkAccess.MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceView createDevice(@Valid @RequestBody DeviceRequest req) {
        return devices.create(req);
    }

    @PutMapping("/network-devices/{id}")
    @PreAuthorize(NetworkAccess.MANAGE)
    public DeviceView updateDevice(@PathVariable UUID id, @Valid @RequestBody DeviceRequest req) {
        return devices.update(id, req);
    }

    @DeleteMapping("/network-devices/{id}")
    @PreAuthorize(NetworkAccess.MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDevice(@PathVariable UUID id) {
        devices.delete(id);
    }
}
