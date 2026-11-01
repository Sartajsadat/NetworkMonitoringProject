package gov.mot.netmon.metrics;

import java.util.Map;

/**
 * Catalogue of stored metric series. Rates are computed by the agent from counter deltas.
 * `resource` is '' for host-wide metrics, else the mount point, disk device or interface.
 */
public final class MetricNames {

    private MetricNames() {
    }

    public static final String CPU_USAGE = "cpu.usage_pct";
    public static final String CPU_IOWAIT = "cpu.iowait_pct";
    public static final String LOAD_1 = "load.1";
    public static final String LOAD_5 = "load.5";
    public static final String LOAD_15 = "load.15";
    public static final String MEM_USED_PCT = "mem.used_pct";
    public static final String MEM_USED_BYTES = "mem.used_bytes";
    public static final String SWAP_USED_PCT = "swap.used_pct";
    public static final String DISK_USED_PCT = "disk.used_pct";
    public static final String DISKIO_READ_BPS = "diskio.read_Bps";
    public static final String DISKIO_WRITE_BPS = "diskio.write_Bps";
    public static final String DISKIO_BUSY_PCT = "diskio.busy_pct";
    public static final String NET_RX_BPS = "net.rx_bps";
    public static final String NET_TX_BPS = "net.tx_bps";
    public static final String NET_RX_PPS = "net.rx_pps";
    public static final String NET_TX_PPS = "net.tx_pps";
    public static final String NET_ERRORS_PS = "net.errors_ps";
    public static final String NET_DROPS_PS = "net.drops_ps";

    /** Metric -> unit, also used to validate history queries. */
    public static final Map<String, String> UNITS = Map.ofEntries(
            Map.entry(CPU_USAGE, "percent"), Map.entry(CPU_IOWAIT, "percent"),
            Map.entry(LOAD_1, "load"), Map.entry(LOAD_5, "load"), Map.entry(LOAD_15, "load"),
            Map.entry(MEM_USED_PCT, "percent"), Map.entry(MEM_USED_BYTES, "bytes"), Map.entry(SWAP_USED_PCT, "percent"),
            Map.entry(DISK_USED_PCT, "percent"),
            Map.entry(DISKIO_READ_BPS, "bytes_per_second"), Map.entry(DISKIO_WRITE_BPS, "bytes_per_second"),
            Map.entry(DISKIO_BUSY_PCT, "percent"),
            Map.entry(NET_RX_BPS, "bits_per_second"), Map.entry(NET_TX_BPS, "bits_per_second"),
            Map.entry(NET_RX_PPS, "packets_per_second"), Map.entry(NET_TX_PPS, "packets_per_second"),
            Map.entry(NET_ERRORS_PS, "per_second"), Map.entry(NET_DROPS_PS, "per_second"));
}
