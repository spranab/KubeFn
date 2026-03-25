/**
 * Request timeout — wraps async function execution with a deadline.
 *
 * Uses setTimeout-based cancellation. The wrapped function's result is
 * raced against a timer; if the timer fires first an error is thrown.
 */

export class TimeoutError extends Error {
  /**
   * @param {number} timeoutMs
   * @param {string} [context]
   */
  constructor(timeoutMs, context) {
    super(
      context
        ? `Function '${context}' timed out after ${timeoutMs}ms`
        : `Execution timed out after ${timeoutMs}ms`
    );
    this.name = 'TimeoutError';
    this.timeoutMs = timeoutMs;
  }
}

/**
 * Execute a function with a timeout guard.
 *
 * @param {() => Promise<*>} fn         - async function to execute
 * @param {number}           timeoutMs  - deadline in milliseconds
 * @param {string}           [context]  - label for error messages
 * @returns {Promise<*>} the function's return value
 * @throws {TimeoutError} if the deadline is exceeded
 */
export function executeWithTimeout(fn, timeoutMs, context) {
  if (!timeoutMs || timeoutMs <= 0) {
    // Wrap sync returns in Promise.resolve for uniform async handling
    return Promise.resolve(fn());
  }

  return new Promise((resolve, reject) => {
    let settled = false;

    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        reject(new TimeoutError(timeoutMs, context));
      }
    }, timeoutMs);

    // Ensure the timer does not keep the process alive during shutdown
    if (timer.unref) timer.unref();

    Promise.resolve(fn()).then(
      (result) => {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          resolve(result);
        }
      },
      (err) => {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          reject(err);
        }
      }
    );
  });
}
