package gov.mot.netmon.agent.linux;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gov.mot.netmon.agent.collect.Collector;
import gov.mot.netmon.agent.collect.Rates;
import gov.mot.netmon.agent.collect.ReportBuilder;
import gov.mot.netmon.agent.model.Report;

/** Filesystem capacity for real (block-device backed) mounts listed in /proc/mounts. */
public class DiskCollector implements Collector {

    /** Capacity of one mount point. */
    public interface FsStats {
        long[] totalUsableUnallocated(Path mount) throws IOException;
    }

    static final Set<String> REAL_FS = Set.of("ext2", "ext3", "ext4", "xfs", "btrfs", "zfs", "vfat", "exfat", "f2fs",
            "jfs", "reiserfs", "ntfs", "ntfs3", "fuseblk");

    private final Path proc;
    private final FsStats stats;

    public DiskCollector(Path proc) {
        this(proc, mount -> {
            FileStore fs = Files.getFileStore(mount);
            return new long[] {fs.getTotalSpace(), fs.getUsableSpace(), fs.getUnallocatedSpace()};
        });
    }

    public DiskCollector(Path proc, FsStats stats) {
        this.proc = proc;
        this.stats = stats;
    }

    @Override
    public String name() {
        return "disks";
    }

    @Override
    public void collect(ReportBuilder b, Instant now) throws Exception {
        List<Report.Disk> disks = new ArrayList<>();
        Set<String> seenDevices = new HashSet<>();
        for (String line : ProcFs.lines(proc.resolve("mounts"))) {
            String[] f = line.split("\\s+");
            if (f.length < 3 || !REAL_FS.contains(f[2])) continue;
            String device = unescape(f[0]);
            String mount = unescape(f[1]);
            if (mount.startsWith("/snap/") || !seenDevices.add(device + "|" + mount)) continue;
            if (disks.size() >= 64) break;
            try {
                long[] s = stats.totalUsableUnallocated(Path.of(mount));
                long total = s[0], usable = s[1], free = s[2];
                if (total <= 0) continue;
                long used = total - free;
                // Like df: percentage of the space available to normal users.
                Double pct = used + usable > 0 ? Rates.round(100.0 * used / (used + usable), 2) : null;
                disks.add(new Report.Disk(mount, device, f[2], total, used, usable, pct));
            } catch (IOException e) {
                // Mount not accessible to the agent user (e.g. restricted path): skip it, others still reported.
            }
        }
        b.disks = disks;
    }

    /** /proc/mounts encodes space, tab, newline and backslash as octal escapes. */
    static String unescape(String s) {
        return s.replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\");
    }
}
