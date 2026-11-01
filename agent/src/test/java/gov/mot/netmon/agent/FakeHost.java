package gov.mot.netmon.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds a fake /proc, /sys and /etc tree in a temp directory for collector tests. */
final class FakeHost {

    final Path proc;
    final Path sys;
    final Path etc;

    FakeHost(Path root) throws IOException {
        proc = Files.createDirectories(root.resolve("proc"));
        sys = Files.createDirectories(root.resolve("sys"));
        etc = Files.createDirectories(root.resolve("etc"));
        write(proc.resolve("uptime"), "1000.50 3000.00\n");
        write(proc.resolve("loadavg"), "0.50 0.40 0.30 2/300 12345\n");
        write(proc.resolve("sys/kernel/hostname"), "web-01\n");
        write(proc.resolve("sys/kernel/osrelease"), "6.8.0-45-generic\n");
        write(etc.resolve("os-release"), "NAME=\"Ubuntu\"\nVERSION_ID=\"24.04\"\n");
        write(etc.resolve("passwd"), "root:x:0:0:root:/root:/bin/bash\nwww-data:x:33:33::/var/www:/usr/sbin/nologin\n");
        cpuStat(1000, 0, 500, 8000, 500);
        write(proc.resolve("meminfo"), """
                MemTotal:        8000000 kB
                MemFree:         1000000 kB
                MemAvailable:    6000000 kB
                SwapTotal:       2000000 kB
                SwapFree:        1500000 kB
                """);
    }

    /** Aggregate + two identical cores. */
    void cpuStat(long user, long nice, long system, long idle, long iowait) throws IOException {
        String line = " " + user + " " + nice + " " + system + " " + idle + " " + iowait + " 0 0 0 0 0\n";
        write(proc.resolve("stat"), "cpu " + line + "cpu0" + line + "cpu1" + line + "procs_running 3\n");
    }

    void nic(String name, long rxBytes, long txBytes, long rxErr, String state) throws IOException {
        Path d = sys.resolve("class/net").resolve(name);
        Path s = d.resolve("statistics");
        write(s.resolve("rx_bytes"), rxBytes + "\n");
        write(s.resolve("tx_bytes"), txBytes + "\n");
        write(s.resolve("rx_packets"), rxBytes / 100 + "\n");
        write(s.resolve("tx_packets"), txBytes / 100 + "\n");
        write(s.resolve("rx_errors"), rxErr + "\n");
        write(s.resolve("tx_errors"), "0\n");
        write(s.resolve("rx_dropped"), "0\n");
        write(s.resolve("tx_dropped"), "0\n");
        write(d.resolve("operstate"), state + "\n");
        write(d.resolve("address"), "52:54:00:12:34:56\n");
        write(d.resolve("mtu"), "1500\n");
        write(d.resolve("speed"), "1000\n");
    }

    void process(long pid, String comm, long utime, long stime, long startTicks, int uid, long rssKb) throws IOException {
        Path d = proc.resolve(String.valueOf(pid));
        StringBuilder rest = new StringBuilder("S");
        // fields 4..13 (10 values), then utime(14) stime(15), then 16..21 (6 values), starttime(22), then more
        for (int i = 4; i <= 13; i++) rest.append(" 0");
        rest.append(' ').append(utime).append(' ').append(stime);
        for (int i = 16; i <= 21; i++) rest.append(" 0");
        rest.append(' ').append(startTicks).append(" 1000 200");
        write(d.resolve("stat"), pid + " (" + comm + ") " + rest + "\n");
        write(d.resolve("status"), "Name:\t" + comm + "\nUid:\t" + uid + "\t" + uid + "\t" + uid + "\t" + uid
                + "\nVmRSS:\t" + rssKb + " kB\n");
    }

    static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
