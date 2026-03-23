/**
 * Metrics Recorder — per-function invocation metrics with Prometheus export.
 *
 * Pure in-memory. No external dependencies.
 */

export class MetricsRecorder {
  constructor() {
    /** @type {Map<string, FunctionMetrics>} */
    this._functions = new Map();
    this._startTime = Date.now();
    this._totalRequests = 0;
    this._totalErrors = 0;
  }

  /**
   * Record a function invocation.
   * @param {string}  funcName   - fully-qualified function name (group.name)
   * @param {number}  durationMs - wall-clock latency
   * @param {boolean} [error=false]
   */
  recordInvocation(funcName, durationMs, error = false) {
    this._totalRequests++;
    if (error) this._totalErrors++;

    let fm = this._functions.get(funcName);
    if (!fm) {
      fm = new FunctionMetrics(funcName);
      this._functions.set(funcName, fm);
    }
    fm.record(durationMs, error);
  }

  /** @returns {object} structured metrics snapshot */
  getMetrics() {
    const functions = {};
    for (const [name, fm] of this._functions) {
      functions[name] = fm.toJSON();
    }
    return {
      uptimeMs: Date.now() - this._startTime,
      totalRequests: this._totalRequests,
      totalErrors: this._totalErrors,
      errorRate: this._totalRequests > 0
        ? (this._totalErrors / this._totalRequests * 100).toFixed(2) + '%'
        : '0.00%',
      functions,
    };
  }

  /**
   * Render metrics in Prometheus exposition text format.
   * @returns {string}
   */
  prometheusText() {
    const lines = [];

    lines.push('# HELP kubefn_uptime_seconds Runtime uptime in seconds');
    lines.push('# TYPE kubefn_uptime_seconds gauge');
    lines.push(`kubefn_uptime_seconds ${((Date.now() - this._startTime) / 1000).toFixed(1)}`);

    lines.push('# HELP kubefn_requests_total Total function invocations');
    lines.push('# TYPE kubefn_requests_total counter');
    lines.push(`kubefn_requests_total ${this._totalRequests}`);

    lines.push('# HELP kubefn_errors_total Total function errors');
    lines.push('# TYPE kubefn_errors_total counter');
    lines.push(`kubefn_errors_total ${this._totalErrors}`);

    lines.push('# HELP kubefn_function_invocations_total Invocations per function');
    lines.push('# TYPE kubefn_function_invocations_total counter');
    for (const [name, fm] of this._functions) {
      const safe = sanitizeLabel(name);
      lines.push(`kubefn_function_invocations_total{function="${safe}"} ${fm.invocations}`);
    }

    lines.push('# HELP kubefn_function_errors_total Errors per function');
    lines.push('# TYPE kubefn_function_errors_total counter');
    for (const [name, fm] of this._functions) {
      const safe = sanitizeLabel(name);
      lines.push(`kubefn_function_errors_total{function="${safe}"} ${fm.errors}`);
    }

    lines.push('# HELP kubefn_function_duration_ms Function latency in milliseconds');
    lines.push('# TYPE kubefn_function_duration_ms summary');
    for (const [name, fm] of this._functions) {
      const safe = sanitizeLabel(name);
      lines.push(`kubefn_function_duration_ms_sum{function="${safe}"} ${fm.totalDurationMs.toFixed(3)}`);
      lines.push(`kubefn_function_duration_ms_count{function="${safe}"} ${fm.invocations}`);
      lines.push(`kubefn_function_duration_ms{function="${safe}",quantile="0.5"} ${fm.p50().toFixed(3)}`);
      lines.push(`kubefn_function_duration_ms{function="${safe}",quantile="0.99"} ${fm.p99().toFixed(3)}`);
    }

    lines.push('');
    return lines.join('\n');
  }
}

/** Per-function latency and error tracking. */
class FunctionMetrics {
  constructor(name) {
    this.name = name;
    this.invocations = 0;
    this.errors = 0;
    this.totalDurationMs = 0;
    this.minDurationMs = Infinity;
    this.maxDurationMs = 0;
    this.lastInvokedAt = 0;

    // Reservoir sampling for percentile estimation (fixed 1024 slots)
    this._reservoir = new Float64Array(1024);
    this._reservoirSize = 0;
    this._reservoirCount = 0;
  }

  record(durationMs, error) {
    this.invocations++;
    if (error) this.errors++;
    this.totalDurationMs += durationMs;
    if (durationMs < this.minDurationMs) this.minDurationMs = durationMs;
    if (durationMs > this.maxDurationMs) this.maxDurationMs = durationMs;
    this.lastInvokedAt = Date.now();

    // Reservoir sampling (Algorithm R)
    this._reservoirCount++;
    if (this._reservoirSize < this._reservoir.length) {
      this._reservoir[this._reservoirSize] = durationMs;
      this._reservoirSize++;
    } else {
      const j = Math.floor(Math.random() * this._reservoirCount);
      if (j < this._reservoir.length) {
        this._reservoir[j] = durationMs;
      }
    }
  }

  /** Estimate the p-th percentile from the reservoir. */
  _percentile(p) {
    if (this._reservoirSize === 0) return 0;
    const sorted = Array.from(this._reservoir.subarray(0, this._reservoirSize)).sort((a, b) => a - b);
    const idx = Math.max(0, Math.ceil(p / 100 * sorted.length) - 1);
    return sorted[idx];
  }

  p50() { return this._percentile(50); }
  p99() { return this._percentile(99); }

  toJSON() {
    return {
      invocations: this.invocations,
      errors: this.errors,
      errorRate: this.invocations > 0
        ? (this.errors / this.invocations * 100).toFixed(2) + '%'
        : '0.00%',
      avgDurationMs: this.invocations > 0
        ? (this.totalDurationMs / this.invocations).toFixed(3)
        : '0.000',
      minDurationMs: this.minDurationMs === Infinity ? 0 : this.minDurationMs.toFixed(3),
      maxDurationMs: this.maxDurationMs.toFixed(3),
      p50Ms: this.p50().toFixed(3),
      p99Ms: this.p99().toFixed(3),
      lastInvokedAt: this.lastInvokedAt || null,
    };
  }
}

/** Sanitize a label value for Prometheus. */
function sanitizeLabel(s) {
  return s.replace(/[\\"\n]/g, '_');
}
