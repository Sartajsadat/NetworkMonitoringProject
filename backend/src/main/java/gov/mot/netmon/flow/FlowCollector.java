package gov.mot.netmon.flow;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import gov.mot.netmon.common.IpAddresses;
import gov.mot.netmon.config.AppProperties;
import jakarta.annotation.PreDestroy;

/**
 * Receives NetFlow/IPFIX over UDP. Only registered exporters are accepted (by source address; on
 * an internal management network - UDP sources can be spoofed, see docs/NETWORK-VISIBILITY.md).
 * Each exporter is limited to a number of flows per second; the rest are dropped and counted.
 */
@Component
public class FlowCollector {

    private static final Logger log = LoggerFactory.getLogger(FlowCollector.class);

    public record Received(FlowExporters.Exporter exporter, String exporterIp, FlowRecord flow, Instant receivedAt) {
    }

    private final AppProperties.Flows cfg;
    private final FlowExporters exporters;
    private final Clock clock;
    private final NetflowParser parser = new NetflowParser();
    final BlockingQueue<Received> queue;
    /** Dropped flows per exporter device id (rate limit, full queue) since the last write. */
    final Map<java.util.UUID, AtomicLong> dropped = new ConcurrentHashMap<>();
    final Map<java.util.UUID, Integer> versions = new ConcurrentHashMap<>();
    final Map<java.util.UUID, Integer> sampling = new ConcurrentHashMap<>();
    private final Map<String, long[]> rate = new ConcurrentHashMap<>(); // exporter -> [second, count]
    private final Map<String, Long> unknownLogged = new ConcurrentHashMap<>();
    private final AtomicLong rejected = new AtomicLong();
    private final List<DatagramSocket> sockets = new ArrayList<>();
    private volatile boolean running;

    public FlowCollector(AppProperties props, FlowExporters exporters, Clock clock) {
        this.cfg = props.flows();
        this.exporters = exporters;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(Math.max(1000, cfg.queueSize()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!cfg.enabled()) {
            log.info("Flow collector is off (FLOW_COLLECTOR_ENABLED=false)");
            return;
        }
        running = true;
        for (int port : new int[] {cfg.netflowPort(), cfg.ipfixPort()}) {
            if (port <= 0) continue;
            try {
                DatagramSocket s = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(cfg.bindAddress()), port));
                s.setReceiveBufferSize(4 * 1024 * 1024);
                sockets.add(s);
                Thread t = new Thread(() -> receive(s), "flow-udp-" + port);
                t.setDaemon(true);
                t.start();
                log.info("Flow collector listening on {}:{} (UDP)", cfg.bindAddress(), port);
            } catch (Exception e) {
                log.error("Flow collector could not listen on UDP port {}: {}", port, e.toString());
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        sockets.forEach(DatagramSocket::close);
    }

    public boolean isRunning() {
        return running && !sockets.isEmpty();
    }

    public long rejectedPackets() {
        return rejected.get();
    }

    private void receive(DatagramSocket s) {
        byte[] buf = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                s.receive(packet);
                handle(packet.getAddress().getHostAddress(), buf, packet.getLength());
            } catch (SocketException e) {
                if (running) log.warn("Flow socket error: {}", e.toString());
                return;
            } catch (Exception e) {
                log.debug("Flow packet ignored: {}", e.toString());
            }
        }
    }

    /** Package-visible for tests: one UDP payload from a source address. */
    void handle(String sourceAddress, byte[] data, int length) {
        String ip;
        try {
            ip = IpAddresses.parse(sourceAddress).canonical();
        } catch (IllegalArgumentException e) {
            return;
        }
        FlowExporters.Exporter exporter = exporters.find(ip);
        Instant now = clock.instant();
        if (exporter == null) {
            rejected.incrementAndGet();
            Long last = unknownLogged.get(ip);
            if (last == null || now.toEpochMilli() - last > 600_000) {
                unknownLogged.put(ip, now.toEpochMilli());
                log.warn("Flow records from unregistered exporter {} dropped. Register it as a network device with flow export.", ip);
            }
            return;
        }
        NetflowParser.Result r;
        try {
            r = parser.parse(ip, data, length, now);
        } catch (NetflowParser.ParseException e) {
            rejected.incrementAndGet();
            log.debug("Malformed flow packet from {}: {}", ip, e.getMessage());
            return;
        }
        versions.put(exporter.deviceId(), r.version());
        if (r.sampling() != null) sampling.put(exporter.deviceId(), r.sampling());
        long second = now.getEpochSecond();
        long[] window = rate.computeIfAbsent(ip, k -> new long[2]);
        for (FlowRecord f : r.flows()) {
            boolean allowed;
            synchronized (window) {
                if (window[0] != second) {
                    window[0] = second;
                    window[1] = 0;
                }
                allowed = ++window[1] <= cfg.maxFlowsPerSecond();
            }
            if (!allowed || !queue.offer(new Received(exporter, ip, f, now))) {
                dropped.computeIfAbsent(exporter.deviceId(), k -> new AtomicLong()).incrementAndGet();
            }
        }
    }
}
