package gov.mot.netmon.flow;

import java.net.InetAddress;
import java.time.Instant;

/** One decoded flow record (a summary written by the exporter, not a packet). */
public record FlowRecord(Instant start, Instant end, InetAddress src, InetAddress dst, Integer srcPort, Integer dstPort,
        int protocol, long bytes, long packets, Integer tcpFlags, Integer inputIf, Integer outputIf, int sampling, int version) {
}
