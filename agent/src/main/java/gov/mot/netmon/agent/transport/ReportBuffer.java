package gov.mot.netmon.agent.transport;

import java.util.ArrayDeque;
import java.util.Deque;

import gov.mot.netmon.agent.model.Report;

/**
 * In-memory queue of reports not yet delivered (platform unreachable). Bounded: when full, the
 * oldest report is dropped. The platform shows the resulting gap as missing data.
 */
public class ReportBuffer {

    private final int capacity;
    private final Deque<Report> queue = new ArrayDeque<>();
    private long dropped;

    public ReportBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(Report r) {
        if (queue.size() >= capacity) {
            queue.pollFirst();
            dropped++;
        }
        queue.addLast(r);
    }

    public synchronized Report peek() {
        return queue.peekFirst();
    }

    public synchronized void removeFirst() {
        queue.pollFirst();
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized long dropped() {
        return dropped;
    }
}
