/**
 * HeapExchange — Zero-copy shared object store for Node.js functions.
 *
 * All functions in the same V8 isolate share the same HeapExchange.
 * Objects are JS references — no serialization, no copying.
 * Function A publishes an object, Function B reads the SAME object.
 *
 * Production features:
 *   - HeapGuard integration (object count + memory pressure limits)
 *   - CaptureEngine integration (causal event tracing)
 *   - TTL support (optional per-publish expiration)
 *   - require() / getOrDefault() convenience methods
 *   - Full metrics counters
 */

export class HeapCapsule {
  constructor(key, value, valueType, version, publisherGroup, publisherFunction, ttlMs) {
    this.key = key;
    this.value = value;
    this.valueType = valueType;
    this.version = version;
    this.publisherGroup = publisherGroup;
    this.publisherFunction = publisherFunction;
    this.publishedAt = Date.now();
    this.expiresAt = ttlMs ? this.publishedAt + ttlMs : null;
  }

  isExpired() {
    return this.expiresAt !== null && Date.now() > this.expiresAt;
  }
}

export class HeapExchange {
  /**
   * @param {object} [opts]
   * @param {import('./heap-guard.js').HeapGuard}           [opts.guard]
   * @param {import('./introspection.js').CaptureEngine}    [opts.captureEngine]
   */
  constructor({ guard, captureEngine } = {}) {
    this._store = new Map();
    this._versionCounter = 0;
    this._guard = guard || null;
    this._captureEngine = captureEngine || null;

    // Metrics
    this.publishCount = 0;
    this.getCount = 0;
    this.hitCount = 0;
    this.missCount = 0;
    this.removeCount = 0;
    this.ttlEvictions = 0;

    // Context (set per-request by the server)
    this._currentGroup = null;
    this._currentFunction = null;
    this._currentRequestId = null;

    // Audit log (ring)
    this._auditLog = [];
    this._maxAudit = 10_000;

    // TTL sweep timer (every 30s, doesn't block shutdown)
    this._ttlTimer = setInterval(() => this._sweepExpired(), 30_000);
    if (this._ttlTimer.unref) this._ttlTimer.unref();
  }

  // ── Context management ──────────────────────────────────────

  setContext(group, fn, requestId) {
    this._currentGroup = group;
    this._currentFunction = fn;
    this._currentRequestId = requestId || null;
  }

  clearContext() {
    this._currentGroup = null;
    this._currentFunction = null;
    this._currentRequestId = null;
  }

  // ── Core API ────────────────────────────────────────────────

  /**
   * Publish an object to the heap.
   * @param {string} key
   * @param {*}      value
   * @param {string} [valueType='object']
   * @param {number} [ttlMs]  - optional time-to-live in milliseconds
   * @returns {HeapCapsule}
   */
  publish(key, value, valueType = 'object', ttlMs) {
    const isOverwrite = this._store.has(key);
    const oldCapsule = isOverwrite ? this._store.get(key) : null;

    // Guard check
    if (this._guard) {
      this._guard.checkPublish(key, value, isOverwrite);
    }

    this._versionCounter++;
    const capsule = new HeapCapsule(
      key, value, valueType, this._versionCounter,
      this._currentGroup || 'unknown',
      this._currentFunction || 'unknown',
      ttlMs
    );

    this._store.set(key, capsule);
    this.publishCount++;

    // Guard tracking
    if (this._guard) {
      this._guard.trackPublish(value, oldCapsule ? oldCapsule.value : undefined);
    }

    // Capture
    if (this._captureEngine && this._currentRequestId) {
      this._captureEngine.captureHeapPublish(this._currentRequestId, key);
    }

    this._audit('PUBLISH', key, valueType);
    return capsule;
  }

  /**
   * Get an object from the heap (zero-copy).
   * @param {string} key
   * @returns {*} the value, or undefined if not found / expired
   */
  get(key) {
    this.getCount++;
    const capsule = this._store.get(key);

    if (!capsule || capsule.isExpired()) {
      if (capsule && capsule.isExpired()) {
        this._evictExpired(key, capsule);
      }
      this.missCount++;
      this._audit('GET_MISS', key);
      if (this._captureEngine && this._currentRequestId) {
        this._captureEngine.captureHeapGet(this._currentRequestId, key, false);
      }
      return undefined;
    }

    this.hitCount++;
    this._audit('GET_HIT', key, capsule.valueType);
    if (this._captureEngine && this._currentRequestId) {
      this._captureEngine.captureHeapGet(this._currentRequestId, key, true);
    }

    return capsule.value;
  }

  /**
   * Get or throw — for required dependencies.
   * @param {string} key
   * @returns {*}
   * @throws {Error} with a helpful message if the key is missing
   */
  require(key) {
    const value = this.get(key);
    if (value === undefined) {
      throw new Error(
        `HeapExchange.require('${key}'): key not found. ` +
        `Available keys: [${this.keys().join(', ')}]. ` +
        `Ensure the producer function has run before this consumer.`
      );
    }
    return value;
  }

  /**
   * Get with a default fallback.
   * @param {string} key
   * @param {*}      defaultValue
   * @returns {*}
   */
  getOrDefault(key, defaultValue) {
    const value = this.get(key);
    return value !== undefined ? value : defaultValue;
  }

  /**
   * Get the full capsule (includes metadata).
   * @param {string} key
   * @returns {HeapCapsule|undefined}
   */
  getCapsule(key) {
    const capsule = this._store.get(key);
    if (capsule && capsule.isExpired()) {
      this._evictExpired(key, capsule);
      return undefined;
    }
    return capsule;
  }

  /**
   * Remove an object from the heap.
   * @param {string} key
   * @returns {boolean}
   */
  remove(key) {
    const capsule = this._store.get(key);
    const existed = this._store.delete(key);
    if (existed) {
      this.removeCount++;
      if (this._guard && capsule) {
        this._guard.trackRemove(capsule.value);
      }
      this._audit('REMOVE', key);
    }
    return existed;
  }

  /** @returns {string[]} */
  keys() { return [...this._store.keys()]; }

  /** @param {string} key */
  contains(key) {
    const capsule = this._store.get(key);
    if (capsule && capsule.isExpired()) {
      this._evictExpired(key, capsule);
      return false;
    }
    return this._store.has(key);
  }

  /** @returns {number} */
  size() { return this._store.size; }

  /** @returns {object} metrics snapshot */
  metrics() {
    const hitRate = this.getCount > 0
      ? ((this.hitCount / this.getCount) * 100).toFixed(2) + '%' : '0.00%';

    const result = {
      objectCount: this._store.size,
      publishCount: this.publishCount,
      getCount: this.getCount,
      hitCount: this.hitCount,
      missCount: this.missCount,
      removeCount: this.removeCount,
      ttlEvictions: this.ttlEvictions,
      hitRate,
      keys: this.keys(),
    };

    if (this._guard) {
      result.guard = this._guard.getStatus();
    }

    return result;
  }

  /** Stop internal timers (call during shutdown). */
  destroy() {
    if (this._ttlTimer) {
      clearInterval(this._ttlTimer);
      this._ttlTimer = null;
    }
  }

  // ── Internal ────────────────────────────────────────────────

  /** @private */
  _evictExpired(key, capsule) {
    this._store.delete(key);
    this.ttlEvictions++;
    if (this._guard) {
      this._guard.trackRemove(capsule.value);
    }
  }

  /** @private Periodic sweep of expired TTL entries. */
  _sweepExpired() {
    const now = Date.now();
    for (const [key, capsule] of this._store) {
      if (capsule.expiresAt !== null && now > capsule.expiresAt) {
        this._evictExpired(key, capsule);
      }
    }
  }

  /** @private */
  _audit(action, key, type = null) {
    this._auditLog.push({
      action, key, type,
      group: this._currentGroup,
      function: this._currentFunction,
      timestamp: Date.now(),
    });
    if (this._auditLog.length > this._maxAudit) {
      this._auditLog = this._auditLog.slice(-this._maxAudit);
    }
  }
}
