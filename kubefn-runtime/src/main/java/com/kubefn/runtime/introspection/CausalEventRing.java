package com.kubefn.runtime.introspection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, lock-free ring buffer for causal event capture on the hot path.
 *
 * <p>Design constraints:
 * <ul>
 *   <li>Zero allocation on append (pre-allocated array)</li>
 *   <li>Lock-free writes via AtomicLong position counter — safe for
 *       concurrent virtual threads without contention</li>
 *   <li>Bounded memory: oldest events are silently overwritten when
 *       the buffer wraps</li>
 *   <li>Reads are consistent for the current window but may miss
 *       events that were overwritten during iteration</li>
 * </ul>
 *
 * <p>The buffer stores events in a fixed-size array. The write position
 * increments monotonically; the actual array index is {@code position % capacity}.
 * This means eventIds in the buffer always increase, and we can detect
 * whether a slot has been overwritten by comparing its eventId to the
 * expected range.
 */
public class CausalEventRing {

    private static final Logger log = LoggerFactory.getLogger(CausalEventRing.class);

    /** Default capacity: 100,000 events. */
    public static final int DEFAULT_CAPACITY = 100_000;

    private final CausalEvent[] ring;
    private final int capacity;
    private final AtomicLong writePosition = new AtomicLong(0);

    /**
     * Creates a ring buffer with the default capacity of {@value #DEFAULT_CAPACITY}.
     */
    public CausalEventRing() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a ring buffer with the specified capacity.
     *
     * @param capacity maximum number of events to retain; must be positive
     * @throws IllegalArgumentException if capacity is not positive
     */
    public CausalEventRing(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive, got: " + capacity);
        }
        this.capacity = capacity;
        this.ring = new CausalEvent[capacity];
    }

    /**
     * Appends an event to the ring buffer. Lock-free and safe for
     * concurrent calls from multiple virtual threads.
     *
     * <p>If the buffer is full, the oldest event is silently overwritten.
     *
     * @param event the event to append; must not be null
     * @throws IllegalArgumentException if event is null
     */
    public void append(CausalEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Event must not be null");
        }
        long pos = writePosition.getAndIncrement();
        int index = (int) (pos % capacity);
        ring[index] = event;
    }

    /**
     * Returns events in the given eventId range (inclusive on both ends).
     *
     * <p>Only returns events that are still present in the buffer (not
     * overwritten). Results are ordered by eventId.
     *
     * @param fromId start eventId (inclusive)
     * @param toId   end eventId (inclusive)
     * @return list of events in range, possibly empty if all were overwritten
     */
    public List<CausalEvent> getRange(long fromId, long toId) {
        if (fromId > toId) {
            return Collections.emptyList();
        }

        List<CausalEvent> result = new ArrayList<>();
        long currentPos = writePosition.get();
        long oldestPossible = Math.max(0, currentPos - capacity);

        // Clamp range to what's still in the buffer
        long effectiveFrom = Math.max(fromId, oldestPossible);
        long effectiveTo = Math.min(toId, currentPos - 1);

        for (long id = effectiveFrom; id <= effectiveTo; id++) {
            int index = (int) (id % capacity);
            CausalEvent event = ring[index];
            if (event != null && event.eventId() >= fromId && event.eventId() <= toId) {
                result.add(event);
            }
        }
        return result;
    }

    /**
     * Returns events matching the given requestId, scanning the most recent
     * events in the buffer up to the specified limit.
     *
     * <p>Scans backwards from the write position for efficiency — most
     * queries are for recent requests.
     *
     * @param requestId the request identifier to match
     * @param limit     maximum number of events to return
     * @return list of matching events ordered by eventId (ascending)
     */
    public List<CausalEvent> getByRequestId(String requestId, int limit) {
        if (requestId == null || limit <= 0) {
            return Collections.emptyList();
        }

        List<CausalEvent> result = new ArrayList<>();
        long currentPos = writePosition.get();
        long oldestPossible = Math.max(0, currentPos - capacity);

        // Scan backwards for efficiency
        for (long pos = currentPos - 1; pos >= oldestPossible && result.size() < limit; pos--) {
            int index = (int) (pos % capacity);
            CausalEvent event = ring[index];
            if (event != null && requestId.equals(event.requestId())) {
                result.add(event);
            }
        }

        // Reverse to get ascending eventId order
        Collections.reverse(result);
        return result;
    }

    /**
     * Returns the number of events that have been written to the buffer.
     * This may exceed capacity if events have wrapped.
     *
     * @return total events written (not the number currently retained)
     */
    public long totalEventsWritten() {
        return writePosition.get();
    }

    /**
     * Returns the number of events currently retained in the buffer.
     *
     * @return event count, at most {@link #capacity()}
     */
    public int size() {
        long written = writePosition.get();
        return (int) Math.min(written, capacity);
    }

    /**
     * Returns the maximum number of events this buffer can hold.
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Returns the most recent N events in ascending eventId order.
     *
     * @param count maximum number of events to return
     * @return list of recent events
     */
    public List<CausalEvent> recent(int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }

        List<CausalEvent> result = new ArrayList<>();
        long currentPos = writePosition.get();
        long start = Math.max(0, currentPos - Math.min(count, capacity));

        for (long pos = start; pos < currentPos && result.size() < count; pos++) {
            int index = (int) (pos % capacity);
            CausalEvent event = ring[index];
            if (event != null) {
                result.add(event);
            }
        }
        return result;
    }

    /**
     * Returns all distinct request IDs present in the current buffer window.
     * Scans the entire buffer — use sparingly.
     *
     * @param limit maximum number of distinct request IDs to return
     * @return list of request IDs, most recent first
     */
    public List<String> recentRequestIds(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }

        // Use a linked structure to preserve insertion order (most recent first)
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        long currentPos = writePosition.get();
        long oldestPossible = Math.max(0, currentPos - capacity);

        for (long pos = currentPos - 1; pos >= oldestPossible && ids.size() < limit; pos--) {
            int index = (int) (pos % capacity);
            CausalEvent event = ring[index];
            if (event != null) {
                ids.add(event.requestId());
            }
        }
        return new ArrayList<>(ids);
    }
}
