/**
 * Scheduler Engine — cron-based function scheduling.
 *
 * Pure Node.js implementation. Supports standard 5-field cron expressions:
 *   minute hour day-of-month month day-of-week
 *
 * Special strings: @yearly, @monthly, @weekly, @daily, @hourly
 *
 * No external dependencies.
 */

/**
 * Parse a single cron field into a Set of valid integers.
 * Supports: *, N, N-M, N/step, *\/step, N-M/step, comma lists
 *
 * @param {string} field
 * @param {number} min
 * @param {number} max
 * @returns {Set<number>}
 */
function parseCronField(field, min, max) {
  const values = new Set();

  for (const part of field.split(',')) {
    const trimmed = part.trim();

    if (trimmed === '*') {
      for (let i = min; i <= max; i++) values.add(i);
      continue;
    }

    // */step
    const allStep = trimmed.match(/^\*\/(\d+)$/);
    if (allStep) {
      const step = parseInt(allStep[1], 10);
      for (let i = min; i <= max; i += step) values.add(i);
      continue;
    }

    // range with optional step: N-M or N-M/step
    const rangeMatch = trimmed.match(/^(\d+)-(\d+)(\/(\d+))?$/);
    if (rangeMatch) {
      const lo = parseInt(rangeMatch[1], 10);
      const hi = parseInt(rangeMatch[2], 10);
      const step = rangeMatch[4] ? parseInt(rangeMatch[4], 10) : 1;
      for (let i = lo; i <= hi; i += step) values.add(i);
      continue;
    }

    // single number with optional step: N/step
    const numStep = trimmed.match(/^(\d+)\/(\d+)$/);
    if (numStep) {
      const start = parseInt(numStep[1], 10);
      const step = parseInt(numStep[2], 10);
      for (let i = start; i <= max; i += step) values.add(i);
      continue;
    }

    // plain number
    const num = parseInt(trimmed, 10);
    if (!isNaN(num) && num >= min && num <= max) {
      values.add(num);
    }
  }

  return values;
}

/** Well-known aliases. */
const ALIASES = {
  '@yearly':  '0 0 1 1 *',
  '@annually': '0 0 1 1 *',
  '@monthly': '0 0 1 * *',
  '@weekly':  '0 0 * * 0',
  '@daily':   '0 0 * * *',
  '@midnight': '0 0 * * *',
  '@hourly':  '0 * * * *',
};

/**
 * Check whether a cron expression matches a given Date.
 * @param {string} cronExpr - 5-field cron expression
 * @param {Date}   date
 * @returns {boolean}
 */
export function matchesCron(cronExpr, date) {
  const resolved = ALIASES[cronExpr.toLowerCase()] || cronExpr;
  const parts = resolved.trim().split(/\s+/);
  if (parts.length !== 5) return false;

  const minutes = parseCronField(parts[0], 0, 59);
  const hours   = parseCronField(parts[1], 0, 23);
  const doms    = parseCronField(parts[2], 1, 31);
  const months  = parseCronField(parts[3], 1, 12);
  const dows    = parseCronField(parts[4], 0, 6); // 0 = Sunday

  return (
    minutes.has(date.getMinutes()) &&
    hours.has(date.getHours()) &&
    doms.has(date.getDate()) &&
    months.has(date.getMonth() + 1) &&
    dows.has(date.getDay())
  );
}

/**
 * A single scheduled function registration.
 * @typedef {object} ScheduledEntry
 * @property {string}   funcName
 * @property {string}   cronExpr
 * @property {Function} handler
 * @property {object}   options
 * @property {number}   lastRun
 * @property {number}   runCount
 * @property {string|null} lastError
 */

export class SchedulerEngine {
  /**
   * @param {object} [opts]
   * @param {number} [opts.tickIntervalMs=60000] - how often to check (default 60s)
   */
  constructor({ tickIntervalMs = 60_000 } = {}) {
    /** @type {Map<string, ScheduledEntry>} */
    this._entries = new Map();
    this._tickIntervalMs = tickIntervalMs;
    this._timer = null;
    this._running = false;
  }

  /**
   * Register a scheduled function.
   * @param {string}   funcName
   * @param {string}   cronExpr  - 5-field cron or alias
   * @param {Function} handler   - async (ctx) => any
   * @param {object}   [options] - arbitrary metadata
   */
  register(funcName, cronExpr, handler, options = {}) {
    this._entries.set(funcName, {
      funcName,
      cronExpr,
      handler,
      options,
      lastRun: 0,
      runCount: 0,
      lastError: null,
    });
  }

  /** Start the ticker. */
  start() {
    if (this._running) return;
    this._running = true;
    this._tick(); // immediate first check
    this._timer = setInterval(() => this._tick(), this._tickIntervalMs);
    if (this._timer.unref) this._timer.unref();
  }

  /** Stop the ticker. */
  stop() {
    this._running = false;
    if (this._timer) {
      clearInterval(this._timer);
      this._timer = null;
    }
  }

  /** @private */
  async _tick() {
    const now = new Date();
    for (const [, entry] of this._entries) {
      if (!matchesCron(entry.cronExpr, now)) continue;

      // Guard against double-fire within the same minute
      const minuteKey = Math.floor(now.getTime() / 60_000);
      const lastMinuteKey = Math.floor(entry.lastRun / 60_000);
      if (minuteKey === lastMinuteKey) continue;

      entry.lastRun = now.getTime();
      entry.runCount++;

      try {
        await entry.handler();
      } catch (err) {
        entry.lastError = String(err);
        console.error(`[scheduler] Error running ${entry.funcName}: ${err}`);
      }
    }
  }

  /** @returns {object[]} status of all scheduled functions */
  getScheduledFunctions() {
    const result = [];
    for (const [, entry] of this._entries) {
      result.push({
        funcName: entry.funcName,
        cronExpr: entry.cronExpr,
        lastRun: entry.lastRun || null,
        runCount: entry.runCount,
        lastError: entry.lastError,
        options: entry.options,
      });
    }
    return result;
  }

  get size() {
    return this._entries.size;
  }
}
