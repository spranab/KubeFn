/**
 * Heap Guard — enforces capacity and memory pressure limits on HeapExchange.
 *
 * Tracks object count and estimated byte size. Rejects publishes that would
 * exceed configured thresholds. Reports pressure level for health checks.
 */

export class HeapGuard {
  /**
   * @param {object} opts
   * @param {number} opts.maxObjects   - max number of heap entries (default 10 000)
   * @param {number} opts.maxSizeBytes - estimated max total size (default 100 MB)
   */
  constructor({ maxObjects = 10_000, maxSizeBytes = 100_000_000 } = {}) {
    this.maxObjects = maxObjects;
    this.maxSizeBytes = maxSizeBytes;
    this._objectCount = 0;
    this._estimatedSizeBytes = 0;
  }

  /**
   * Validate that a publish will not exceed limits.
   * Called BEFORE the object is written to the store.
   *
   * @param {string} key
   * @param {*}      value
   * @param {boolean} isOverwrite - true if the key already exists (replace, not add)
   * @throws {Error} if limits are exceeded
   */
  checkPublish(key, value, isOverwrite = false) {
    if (!isOverwrite && this._objectCount >= this.maxObjects) {
      throw new Error(
        `HeapGuard: object limit reached (${this.maxObjects}). ` +
        `Publish of key '${key}' rejected.`
      );
    }

    const estimated = estimateSize(value);
    if (this._estimatedSizeBytes + estimated > this.maxSizeBytes && !isOverwrite) {
      throw new Error(
        `HeapGuard: memory pressure limit reached (~${formatBytes(this._estimatedSizeBytes)} / ` +
        `${formatBytes(this.maxSizeBytes)}). Publish of key '${key}' rejected.`
      );
    }
  }

  /**
   * Track a successful publish.
   * @param {*} value
   * @param {*} [oldValue] - previous value if overwrite
   */
  trackPublish(value, oldValue) {
    if (oldValue !== undefined) {
      this._estimatedSizeBytes -= estimateSize(oldValue);
    } else {
      this._objectCount++;
    }
    this._estimatedSizeBytes += estimateSize(value);
  }

  /** Track a removal. */
  trackRemove(value) {
    this._objectCount = Math.max(0, this._objectCount - 1);
    this._estimatedSizeBytes -= estimateSize(value);
    if (this._estimatedSizeBytes < 0) this._estimatedSizeBytes = 0;
  }

  /** @returns {{ objectCount: number, estimatedSizeBytes: number, pressure: string }} */
  getStatus() {
    const pct = this.maxSizeBytes > 0
      ? this._estimatedSizeBytes / this.maxSizeBytes
      : 0;

    let pressure;
    if (pct < 0.5) pressure = 'LOW';
    else if (pct < 0.8) pressure = 'MODERATE';
    else if (pct < 0.95) pressure = 'HIGH';
    else pressure = 'CRITICAL';

    return {
      objectCount: this._objectCount,
      maxObjects: this.maxObjects,
      estimatedSizeBytes: this._estimatedSizeBytes,
      maxSizeBytes: this.maxSizeBytes,
      estimatedSizeHuman: formatBytes(this._estimatedSizeBytes),
      maxSizeHuman: formatBytes(this.maxSizeBytes),
      pressure,
    };
  }
}

/**
 * Rough estimate of the in-memory size of a JS value.
 * Not exact — V8 internals are opaque — but good enough for pressure tracking.
 */
function estimateSize(value) {
  if (value === null || value === undefined) return 8;
  const type = typeof value;
  if (type === 'boolean') return 4;
  if (type === 'number') return 8;
  if (type === 'string') return 40 + value.length * 2;
  if (type === 'bigint') return 16;
  if (type === 'function') return 64;

  if (ArrayBuffer.isView(value) || value instanceof ArrayBuffer) {
    return (value.byteLength || 0) + 64;
  }

  if (Array.isArray(value)) {
    let size = 64 + value.length * 8;
    // Only sample for large arrays to avoid O(n^2)
    const sample = value.length > 100 ? 100 : value.length;
    let sampleSize = 0;
    for (let i = 0; i < sample; i++) {
      sampleSize += estimateSize(value[i]);
    }
    if (sample < value.length) {
      size += (sampleSize / sample) * value.length;
    } else {
      size += sampleSize;
    }
    return size;
  }

  // Plain object — estimate keys + values
  if (type === 'object') {
    const keys = Object.keys(value);
    let size = 64 + keys.length * 32;
    // Sample for large objects
    const sample = keys.length > 50 ? 50 : keys.length;
    let sampleSize = 0;
    for (let i = 0; i < sample; i++) {
      sampleSize += estimateSize(value[keys[i]]);
    }
    if (sample < keys.length) {
      size += (sampleSize / sample) * keys.length;
    } else {
      size += sampleSize;
    }
    return size;
  }

  return 64;
}

function formatBytes(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}
