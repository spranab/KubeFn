/**
 * Introspection — causal event capture, ring buffer storage, trace assembly.
 *
 * Every function invocation, heap operation, and request lifecycle event is
 * captured as a CausalEvent and stored in a fixed-capacity ring buffer.
 * Traces are assembled on demand from events sharing a requestId.
 */

import { randomUUID } from 'node:crypto';

/** Canonical event types. */
export const EventType = Object.freeze({
  REQUEST_START: 'REQUEST_START',
  REQUEST_END: 'REQUEST_END',
  FUNCTION_START: 'FUNCTION_START',
  FUNCTION_END: 'FUNCTION_END',
  HEAP_PUBLISH: 'HEAP_PUBLISH',
  HEAP_GET: 'HEAP_GET',
  HEAP_MISS: 'HEAP_MISS',
  HEAP_REMOVE: 'HEAP_REMOVE',
  CIRCUIT_OPEN: 'CIRCUIT_OPEN',
  TIMEOUT: 'TIMEOUT',
  ERROR: 'ERROR',
});

export class CausalEvent {
  /**
   * @param {object} fields
   * @param {string} fields.requestId
   * @param {string} fields.eventType    - one of EventType values
   * @param {string} [fields.functionName]
   * @param {string} [fields.heapKey]
   * @param {number} [fields.durationNs]
   * @param {string} [fields.detail]
   */
  constructor({ requestId, eventType, functionName, heapKey, durationNs, detail }) {
    this.eventId = randomUUID();
    this.timestamp = Date.now();
    this.requestId = requestId;
    this.eventType = eventType;
    this.functionName = functionName || null;
    this.heapKey = heapKey || null;
    this.durationNs = durationNs ?? null;
    this.detail = detail || null;
  }
}

/**
 * Fixed-capacity ring buffer for CausalEvent instances.
 * Overwrites oldest entries when full — O(1) append.
 */
export class EventRing {
  /**
   * @param {number} capacity - max events stored (default 50 000)
   */
  constructor(capacity = 50_000) {
    this._capacity = capacity;
    this._buffer = new Array(capacity);
    this._head = 0;   // next write position
    this._size = 0;
  }

  /** Append an event to the ring. */
  append(event) {
    this._buffer[this._head] = event;
    this._head = (this._head + 1) % this._capacity;
    if (this._size < this._capacity) this._size++;
  }

  /**
   * Return all events for a given requestId (scan — tolerable for traces).
   * @param {string} requestId
   * @returns {CausalEvent[]}
   */
  getByRequestId(requestId) {
    const result = [];
    const start = this._size < this._capacity ? 0 : this._head;
    for (let i = 0; i < this._size; i++) {
      const idx = (start + i) % this._capacity;
      const evt = this._buffer[idx];
      if (evt && evt.requestId === requestId) {
        result.push(evt);
      }
    }
    return result;
  }

  /**
   * Return the N most recent events.
   * @param {number} limit
   * @returns {CausalEvent[]}
   */
  recent(limit = 100) {
    const n = Math.min(limit, this._size);
    const result = new Array(n);
    for (let i = 0; i < n; i++) {
      const idx = (this._head - 1 - i + this._capacity) % this._capacity;
      result[i] = this._buffer[idx];
    }
    return result;
  }

  get size() {
    return this._size;
  }
}

/** Assembles a human-readable trace from raw events. */
export class TraceAssembler {
  /**
   * @param {string} requestId
   * @param {CausalEvent[]} events - events sorted by timestamp
   * @returns {object}
   */
  assemble(requestId, events) {
    if (!events || events.length === 0) {
      return { requestId, found: false, events: [] };
    }

    const sorted = [...events].sort((a, b) => a.timestamp - b.timestamp);
    const first = sorted[0];
    const last = sorted[sorted.length - 1];

    const heapOps = sorted.filter(
      (e) => e.eventType === EventType.HEAP_PUBLISH ||
             e.eventType === EventType.HEAP_GET ||
             e.eventType === EventType.HEAP_MISS ||
             e.eventType === EventType.HEAP_REMOVE
    );

    const errors = sorted.filter(
      (e) => e.eventType === EventType.ERROR || e.eventType === EventType.TIMEOUT
    );

    const reqEnd = sorted.find((e) => e.eventType === EventType.REQUEST_END);

    return {
      requestId,
      found: true,
      startTime: first.timestamp,
      endTime: last.timestamp,
      durationMs: last.timestamp - first.timestamp,
      functionName: first.functionName,
      totalDurationNs: reqEnd ? reqEnd.durationNs : null,
      eventCount: sorted.length,
      heapOps: heapOps.length,
      errors: errors.length,
      events: sorted,
    };
  }
}

/**
 * High-level capture API used by the server and heap-exchange.
 * Delegates to EventRing for storage and TraceAssembler for queries.
 */
export class CaptureEngine {
  /**
   * @param {number} [ringCapacity=50000]
   */
  constructor(ringCapacity = 50_000) {
    this._ring = new EventRing(ringCapacity);
    this._assembler = new TraceAssembler();
  }

  // ── lifecycle events ──────────────────────────────────────────

  captureRequestStart(requestId, funcName) {
    this._ring.append(new CausalEvent({
      requestId,
      eventType: EventType.REQUEST_START,
      functionName: funcName,
    }));
  }

  captureRequestEnd(requestId, durationNs, error) {
    this._ring.append(new CausalEvent({
      requestId,
      eventType: EventType.REQUEST_END,
      durationNs,
      detail: error ? String(error) : null,
    }));
  }

  captureFunctionStart(requestId, funcName) {
    this._ring.append(new CausalEvent({
      requestId,
      eventType: EventType.FUNCTION_START,
      functionName: funcName,
    }));
  }

  captureFunctionEnd(requestId, funcName, durationNs, error) {
    this._ring.append(new CausalEvent({
      requestId,
      eventType: EventType.FUNCTION_END,
      functionName: funcName,
      durationNs,
      detail: error ? String(error) : null,
    }));
  }

  // ── heap events ───────────────────────────────────────────────

  captureHeapPublish(requestId, key) {
    this._ring.append(new CausalEvent({
      requestId,
      eventType: EventType.HEAP_PUBLISH,
      heapKey: key,
    }));
  }

  captureHeapGet(requestId, key, hit) {
    this._ring.append(new CausalEvent({
      requestId,
      eventType: hit ? EventType.HEAP_GET : EventType.HEAP_MISS,
      heapKey: key,
    }));
  }

  // ── query API ─────────────────────────────────────────────────

  /**
   * Assemble a full trace for a request.
   * @param {string} requestId
   * @returns {object}
   */
  getTrace(requestId) {
    const events = this._ring.getByRequestId(requestId);
    return this._assembler.assemble(requestId, events);
  }

  /**
   * Return the N most recent traces (grouped by requestId).
   * @param {number} limit
   * @returns {object[]}
   */
  recentTraces(limit = 20) {
    const recent = this._ring.recent(limit * 10); // over-sample to find enough unique requests
    const seen = new Map();

    for (const evt of recent) {
      if (evt && evt.requestId && !seen.has(evt.requestId)) {
        seen.set(evt.requestId, true);
        if (seen.size >= limit) break;
      }
    }

    const traces = [];
    for (const reqId of seen.keys()) {
      traces.push(this.getTrace(reqId));
    }
    return traces;
  }

  get ringSize() {
    return this._ring.size;
  }
}
