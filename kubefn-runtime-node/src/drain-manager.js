/**
 * Drain Manager — graceful shutdown support.
 *
 * Tracks in-flight requests. When draining is initiated, new requests are
 * rejected and a promise resolves once all in-flight requests complete.
 */

export class DrainManager {
  constructor() {
    this._inFlight = 0;
    this._draining = false;
    this._drainResolve = null;
  }

  /**
   * Acquire a slot for an incoming request.
   * @returns {boolean} false if the server is draining (caller should reject the request)
   */
  acquire() {
    if (this._draining) return false;
    this._inFlight++;
    return true;
  }

  /** Release a slot when a request completes. */
  release() {
    this._inFlight--;
    if (this._inFlight < 0) this._inFlight = 0;

    if (this._draining && this._inFlight === 0 && this._drainResolve) {
      this._drainResolve();
      this._drainResolve = null;
    }
  }

  /**
   * Begin draining. Returns a promise that resolves when all in-flight
   * requests have completed.
   * @returns {Promise<void>}
   */
  startDrain() {
    this._draining = true;

    if (this._inFlight === 0) {
      return Promise.resolve();
    }

    return new Promise((resolve) => {
      this._drainResolve = resolve;
    });
  }

  /** @returns {boolean} */
  isDraining() {
    return this._draining;
  }

  /** @returns {number} */
  inFlightCount() {
    return this._inFlight;
  }

  toJSON() {
    return {
      draining: this._draining,
      inFlight: this._inFlight,
    };
  }
}
