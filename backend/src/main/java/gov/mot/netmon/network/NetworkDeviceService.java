package gov.mot.netmon.network;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.IpAddresses;
import gov.mot.netmon.flow.FlowExporters;
import gov.mot.netmon.network.NetworkDtos.DeviceRequest;
import gov.mot.netmon.network.NetworkDtos.DeviceView;
import gov.mot.netmon.security.CurrentUser;

@Service
public class NetworkDeviceService {

    private final NetworkDeviceRepository devices;
    private final IpInventorySync sync;
    private final FlowExporters exporters;
    private final AuditService audit;
    private final Clock clock;

    public NetworkDeviceService(NetworkDeviceRepository devices, IpInventorySync sync, FlowExporters exporters, AuditService audit,
            Clock clock) {
        this.devices = devices;
        this.sync = sync;
        this.exporters = exporters;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DeviceView> list() {
        return devices.findAllByOrderByNameAsc().stream().map(DeviceView::of).toList();
    }

    @Transactional(readOnly = true)
    public DeviceView get(UUID id) {
        return DeviceView.of(devices.findById(id).orElseThrow(() -> ApiException.notFound("Network device")));
    }

    private static String ip(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return IpAddresses.parse(raw).canonical();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_ADDRESS", e.getMessage());
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void apply(NetworkDevice d, DeviceRequest r, UUID selfId) {
        String type = r.deviceType().trim().toUpperCase(Locale.ROOT);
        if (!NetworkDevice.TYPES.contains(type)) throw ApiException.badRequest("INVALID_TYPE", "Unknown device type");
        String mgmt = ip(r.managementIp());
        String exporter = ip(r.exporterIp());
        if (r.flowExport() && exporter == null) exporter = mgmt;
        if (r.flowExport() && exporter == null) {
            throw ApiException.badRequest("EXPORTER_REQUIRED", "Give the address the device sends flow records from");
        }
        if (exporter != null) {
            devices.findByExporterIp(exporter).filter(o -> !o.getId().equals(selfId)).ifPresent(o -> {
                throw ApiException.conflict("EXPORTER_IN_USE", "Another device already uses this exporter address");
            });
        }
        devices.findAll().stream().filter(o -> o.getName().equalsIgnoreCase(r.name().trim()) && !o.getId().equals(selfId))
                .findAny().ifPresent(o -> {
                    throw ApiException.conflict("DEVICE_NAME_TAKEN", "A device with this name exists");
                });
        d.apply(r.name().trim(), type, blank(r.vendor()), blank(r.model()), mgmt, blank(r.location()), blank(r.description()),
                r.flowExport(), r.flowExport() ? exporter : null, CurrentUser.require().username(), clock.instant());
    }

    @Transactional
    public DeviceView create(DeviceRequest r) {
        NetworkDevice d = new NetworkDevice(CurrentUser.require().username(), clock.instant());
        apply(d, r, null);
        devices.saveAndFlush(d);
        exporters.reload();
        sync.sync();
        audit.event(AuditAction.NETWORK_DEVICE_CREATED).target("NETWORK_DEVICE", d.getId()).detail("name", d.getName())
                .detail("type", d.getDeviceType()).detail("flowExport", d.isFlowExport()).detail("exporterIp", d.getExporterIp())
                .success();
        return get(d.getId());
    }

    @Transactional
    public DeviceView update(UUID id, DeviceRequest r) {
        NetworkDevice d = devices.findById(id).orElseThrow(() -> ApiException.notFound("Network device"));
        String before = d.getName() + " export=" + d.isFlowExport() + " " + d.getExporterIp();
        apply(d, r, id);
        devices.saveAndFlush(d);
        exporters.reload();
        sync.sync();
        audit.event(AuditAction.NETWORK_DEVICE_UPDATED).target("NETWORK_DEVICE", id).detail("before", before)
                .detail("after", d.getName() + " export=" + d.isFlowExport() + " " + d.getExporterIp()).success();
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        NetworkDevice d = devices.findById(id).orElseThrow(() -> ApiException.notFound("Network device"));
        devices.delete(d);
        devices.flush();
        exporters.reload();
        audit.event(AuditAction.NETWORK_DEVICE_DELETED).target("NETWORK_DEVICE", id).detail("name", d.getName()).success();
    }
}
