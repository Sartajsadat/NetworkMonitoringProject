package gov.mot.netmon.agent.windows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.Rates;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/** Local fixed volumes (Win32_LogicalDisk, DriveType=3), reported with the drive letter as mount point. */
public class WinDiskCollector implements Collector {

    @Override
    public String name() {
        return "disks";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        List<Report.Disk> out = new ArrayList<>();
        for (JsonNode r : WinData.section(b, "disks")) {
            if (out.size() >= 64) break;
            String id = WinData.str(r, "DeviceID");
            Long size = WinData.lng(r, "Size");
            Long free = WinData.lng(r, "FreeSpace");
            if (id == null || size == null || size <= 0) continue; // e.g. unformatted volume
            long used = free == null ? 0 : size - free;
            out.add(new Report.Disk(id + "\\", id, WinData.str(r, "FileSystem"), size, free == null ? null : used, free,
                    free == null ? null : Rates.round(100.0 * used / size, 2)));
        }
        b.disks = out;
    }
}
