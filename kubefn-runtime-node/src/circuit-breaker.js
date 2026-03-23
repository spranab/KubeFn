/**
 * Circuit Breaker — protects functions from cascading failures.
 *
 * States:
 *   CLOSED    → requests flow normally; failures are counted
 *   OPEN      → requests are rejected immediately
 *   HALF_OPEN → one probe request allowed; success resets, failure re-opens
 */

const STATE_CLOSED = 'CLOSED';
const STATE_OPEN = 'OPEN';
const STATE_HALF_OPEN = 'HALF_OPEN';

export class CircuitBreaker {
  /**
   * @param {string} name - breaker name (typically the function name)
   * @param {object} opts
   * @param {number} opts.failureThreshold - consecutive failures before opening (default 5)
   * @param {number} opts.resetTimeoutMs   - ms to wait in OPEN before probing (default 30000)
   */
  constructor(name, { failureThreshold = 5, resetTimeoutMs = 30000 } = {}) {
    this.name = name;
    this.failureThreshold = failureThreshold;
    this.resetTimeoutMs = resetTimeoutMs;

    this._state = STATE_CLOSED;
    this._failureCount = 0;
    this._successCount = 0;
    this._lastFailureTime = 0;
    this._totalTrips = 0;
  }

  /** @returns {'CLOSED'|'OPEN'|'HALF_OPEN'} */
  getState() {
    if (this._state === STATE_OPEN) {
      // Check if enough time has elapsed to allow a probe
      if (Date.now() - this._lastFailureTime >= this.resetTimeoutMs) {
        this._state = STATE_HALF_OPEN;
      }
    }
    return this._state;
  }

  /** @returns {boolean} true if the request should be allowed through */
  isAllowed() {
    const state = this.getState();
    if (state === STATE_CLOSED) return true;
    if (state === STATE_HALF_OPEN) return true; // allow one probe
    return false; // OPEN
  }

  recordSuccess() {
    this._successCount++;
    if (this._state === STATE_HALF_OPEN) {
      // Probe succeeded — reset to closed
      this._state = STATE_CLOSED;
      this._failureCount = 0;
    }
  }

  recordFailure() {
    this._failureCount++;
    this._lastFailureTime = Date.now();

    if (this._state === STATE_HALF_OPEN) {
      // Probe failed — back to open
      this._state = STATE_OPEN;
      this._totalTrips++;
      return;
    }

    if (this._failureCount >= this.failureThreshold) {
      this._state = STATE_OPEN;
      this._totalTrips++;
    }
  }

  toJSON() {
    return {
      name: this.name,
      state: this.getState(),
      failureCount: this._failureCount,
      successCount: this._successCount,
      failureThreshold: this.failureThreshold,
      resetTimeoutMs: this.resetTimeoutMs,
      lastFailureTime: this._lastFailureTime || null,
      totalTrips: this._totalTrips,
    };
  }
}

export class CircuitBreakerRegistry {
  constructor(defaultOpts = {}) {
    this._breakers = new Map();
    this._defaultOpts = defaultOpts;
  }

  /**
   * Get or create a breaker for the given name.
   * @param {string} name
   * @param {object} [opts] - overrides for this breaker
   * @returns {CircuitBreaker}
   */
  getOrCreate(name, opts) {
    let breaker = this._breakers.get(name);
    if (!breaker) {
      breaker = new CircuitBreaker(name, { ...this._defaultOpts, ...opts });
      this._breakers.set(name, breaker);
    }
    return breaker;
  }

  /** @returns {object} map of name → breaker status */
  getAllStatus() {
    const result = {};
    for (const [name, breaker] of this._breakers) {
      result[name] = breaker.toJSON();
    }
    return result;
  }
}
