package gov.mot.netmon.flow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class FlowDtos {

    private FlowDtos() {
    }

    public record Filter(Instant from, Instant to, UUID exporterId, String ip, String subnet, Integer port, Integer protocol) {
    }

    public record Talker(String address, long bytes, long packets, long flows) {
    }

    public record PortRow(int protocol, Integer port, long bytes, long packets, long flows) {
    }

    public record ProtocolRow(int protocol, long bytes, long packets, long flows) {
    }

    public record Conversation(String a, String b, long bytes, long packets, long flows, long bytesAtoB, long bytesBtoA) {
    }

    public record Bucket(Instant t, long bytes, long packets) {
    }

    public record ExporterRow(UUID id, String name, long flows, long bytes) {
    }

    /**
     * Totals from flow records. {@code sampled} means some exporters sample 1:N packets: the values
     * are what the exporters reported, not multiplied up to estimated real traffic.
     */
    public record Summary(Instant from, Instant to, long flows, long bytes, long packets, boolean sampled, long bucketSeconds,
            List<Talker> topSources, List<Talker> topDestinations, List<PortRow> topPorts, List<ProtocolRow> protocols,
            List<Conversation> conversations, List<Bucket> timeline, List<ExporterRow> exporters) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FlowView(long id, String exporter, Instant start, Instant end, String src, Integer srcPort, String dst,
            Integer dstPort, int protocol, long bytes, long packets, Integer tcpFlags, int sampling, int version) {
    }
}
