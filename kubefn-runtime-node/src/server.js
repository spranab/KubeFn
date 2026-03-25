/**
 * KubeFn Node.js HTTP Server — production-grade request handler.
 *
 * Pure Node.js http module. Routes requests to loaded functions with:
 *   - DrainManager: graceful shutdown / reject during drain
 *   - CircuitBreaker: per-function failure protection
 *   - Request timeout: configurable deadline per invocation
 *   - CaptureEngine: causal event tracing
 *   - MetricsRecorder: latency + error tracking with Prometheus export
 *   - Full admin API under /admin/ prefix
 */

import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';

const VERSION = '0.4.0';

export class KubeFnServer {
  /**
   * @param {object} deps
   * @param {import('./heap-exchange.js').HeapExchange}        deps.heap
   * @param {import('./loader.js').FunctionLoader}             deps.loader
   * @param {import('./drain-manager.js').DrainManager}        deps.drainManager
   * @param {import('./circuit-breaker.js').CircuitBreakerRegistry} deps.breakerRegistry
   * @param {import('./introspection.js').CaptureEngine}       deps.captureEngine
   * @param {import('./metrics.js').MetricsRecorder}           deps.metrics
   * @param {import('./scheduler.js').SchedulerEngine}         deps.scheduler
   * @param {object} [opts]
   * @param {number} [opts.requestTimeoutMs=30000]
   */
  constructor(deps, opts = {}) {
    this.heap = deps.heap;
    this.loader = deps.loader;
    this.drainManager = deps.drainManager;
    this.breakerRegistry = deps.breakerRegistry;
    this.captureEngine = deps.captureEngine;
    this.metrics = deps.metrics;
    this.scheduler = deps.scheduler;

    this.requestTimeoutMs = opts.requestTimeoutMs || 30_000;
    this.startTime = Date.now();

    this._server = createServer((req, res) => this._handleRequest(req, res));
  }

  /**
   * Start listening.
   * @param {number} port
   * @returns {Promise<void>}
   */
  listen(port) {
    return new Promise((resolve, reject) => {
      this._server.on('error', reject);
      this._server.listen(port, () => {
        this._printBanner(port);
        resolve();
      });
    });
  }

  /**
   * Graceful close — stops accepting new connections.
   * @returns {Promise<void>}
   */
  close() {
    return new Promise((resolve) => {
      this._server.close(() => resolve());
    });
  }

  // ── Request handling ────────────────────────────────────────

  /** @private */
  async _handleRequest(req, res) {
    const url = new URL(req.url, `http://localhost`);
    const path = url.pathname;
    const method = req.method;

    // ── Admin API ──
    if (path.startsWith('/admin/') || path === '/admin/health' || path === '/admin/ready') {
      return this._handleAdmin(path, method, url, req, res);
    }

    // Legacy health endpoints
    if (path === '/healthz') {
      return sendJson(res, 200, {
        status: 'alive', organism: 'kubefn', version: VERSION, runtime: 'node',
      });
    }
    if (path === '/readyz') {
      const fns = this.loader.allFunctions();
      const ready = fns.length > 0;
      return sendJson(res, ready ? 200 : 503, {
        status: ready ? 'ready' : 'no_functions_loaded',
        functionCount: fns.length, runtime: 'node',
      });
    }

    // ── Drain check ──
    if (!this.drainManager.acquire()) {
      return sendJson(res, 503, {
        error: 'Server is draining — not accepting new requests',
        retryAfter: 5,
      }, { 'Retry-After': '5' });
    }

    const requestId = randomUUID();
    const queryParams = Object.fromEntries(url.searchParams);

    try {
      await this._dispatchFunction(req, res, method, path, queryParams, requestId);
    } finally {
      this.drainManager.release();
    }
  }

  /** @private */
  async _dispatchFunction(req, res, method, path, queryParams, requestId) {
    // Resolve function
    const resolved = this.loader.resolve(method, path);
    if (!resolved) {
      return sendJson(res, 404, {
        error: `No function for ${method} ${path}`,
        requestId,
        status: 404,
      });
    }

    const { fn } = resolved;
    const qualifiedName = `${fn.group}.${fn.name}`;

    // ── Circuit breaker check ──
    const breaker = this.breakerRegistry.getOrCreate(qualifiedName);
    if (!breaker.isAllowed()) {
      this.metrics.recordInvocation(qualifiedName, 0, true);
      return sendJson(res, 503, {
        error: `Circuit breaker OPEN for ${qualifiedName}`,
        requestId,
        breakerState: breaker.getState(),
      });
    }

    // ── Capture: request start ──
    this.captureEngine.captureRequestStart(requestId, qualifiedName);

    // Set heap context
    this.heap.setContext(fn.group, fn.name, requestId);

    // Read body
    const body = await readBody(req);

    // Build request context
    const request = {
      method, path, queryParams,
      headers: req.headers,
      body,
      bodyText: body.toString('utf-8'),
      requestId,
    };

    const ctx = {
      heap: this.heap,
      groupName: fn.group,
      functionName: fn.name,
      revisionId: `node-rev-${VERSION}`,
      requestId,
      config: {},
      logger: {
        info: (msg) => console.log(`[${fn.group}.${fn.name}] [${requestId.slice(0, 8)}] ${msg}`),
        warn: (msg) => console.warn(`[${fn.group}.${fn.name}] [${requestId.slice(0, 8)}] ${msg}`),
        error: (msg) => console.error(`[${fn.group}.${fn.name}] [${requestId.slice(0, 8)}] ${msg}`),
      },
    };

    // ── Execute with timeout ──
    const startNs = process.hrtime.bigint();
    this.captureEngine.captureFunctionStart(requestId, qualifiedName);

    try {
      const result = await executeWithDeadline(
        () => fn.handler(request, ctx),
        this.requestTimeoutMs,
        qualifiedName
      );

      const durationNs = Number(process.hrtime.bigint() - startNs);
      const durationMs = durationNs / 1_000_000;

      breaker.recordSuccess();
      this.metrics.recordInvocation(qualifiedName, durationMs, false);
      this.captureEngine.captureFunctionEnd(requestId, qualifiedName, durationNs, null);
      this.captureEngine.captureRequestEnd(requestId, durationNs, null);

      const response = typeof result === 'object' && result !== null ? result : { result };
      if (!response._meta) response._meta = {};
      Object.assign(response._meta, {
        requestId,
        durationMs: durationMs.toFixed(3),
        function: fn.name,
        group: fn.group,
        runtime: 'node',
        zeroCopy: true,
      });

      sendJson(res, 200, response, {
        'X-KubeFn-Request-Id': requestId,
        'X-KubeFn-Runtime': `node-${VERSION}`,
        'X-KubeFn-Group': fn.group,
        'X-KubeFn-Duration-Ms': durationMs.toFixed(3),
      });
    } catch (e) {
      const durationNs = Number(process.hrtime.bigint() - startNs);
      const durationMs = durationNs / 1_000_000;

      breaker.recordFailure();
      this.metrics.recordInvocation(qualifiedName, durationMs, true);
      this.captureEngine.captureFunctionEnd(requestId, qualifiedName, durationNs, e.message);
      this.captureEngine.captureRequestEnd(requestId, durationNs, e.message);

      console.error(`Function error: ${qualifiedName} [${requestId}]: ${e.message}`);

      const status = e.name === 'TimeoutError' ? 504 : 500;
      sendJson(res, status, {
        error: e.message,
        requestId,
        durationMs: durationMs.toFixed(3),
      }, {
        'X-KubeFn-Request-Id': requestId,
      });
    } finally {
      this.heap.clearContext();
    }
  }

  // ── Admin API ───────────────────────────────────────────────

  /** @private */
  async _handleAdmin(path, method, url, req, res) {
    // GET /admin/health
    if (path === '/admin/health') {
      return sendJson(res, 200, {
        status: 'alive',
        organism: 'kubefn',
        version: VERSION,
        runtime: 'node',
        uptimeMs: Date.now() - this.startTime,
      });
    }

    // GET /admin/ready
    if (path === '/admin/ready') {
      const fns = this.loader.allFunctions();
      const ready = fns.length > 0 && !this.drainManager.isDraining();
      return sendJson(res, ready ? 200 : 503, {
        status: ready ? 'ready' : 'not_ready',
        functionCount: fns.length,
        draining: this.drainManager.isDraining(),
        runtime: 'node',
      });
    }

    // GET /admin/functions
    if (path === '/admin/functions') {
      const fns = this.loader.allFunctions();
      return sendJson(res, 200, { functions: fns, count: fns.length });
    }

    // GET /admin/heap
    if (path === '/admin/heap') {
      return sendJson(res, 200, this.heap.metrics());
    }

    // GET /admin/metrics (Prometheus text format)
    if (path === '/admin/metrics') {
      const text = this.metrics.prometheusText();
      res.writeHead(200, {
        'Content-Type': 'text/plain; version=0.0.4; charset=utf-8',
        'Content-Length': Buffer.byteLength(text),
      });
      return res.end(text);
    }

    // GET /admin/breakers
    if (path === '/admin/breakers') {
      return sendJson(res, 200, this.breakerRegistry.getAllStatus());
    }

    // GET /admin/traces/recent
    if (path === '/admin/traces/recent') {
      const limit = parseInt(url.searchParams.get('limit') || '20', 10);
      return sendJson(res, 200, { traces: this.captureEngine.recentTraces(limit) });
    }

    // GET /admin/traces/:requestId
    if (path.startsWith('/admin/traces/') && path !== '/admin/traces/recent') {
      const requestId = path.slice('/admin/traces/'.length);
      if (!requestId) {
        return sendJson(res, 400, { error: 'Missing requestId' });
      }
      return sendJson(res, 200, this.captureEngine.getTrace(requestId));
    }

    // GET /admin/scheduler
    if (path === '/admin/scheduler') {
      return sendJson(res, 200, {
        scheduledFunctions: this.scheduler.getScheduledFunctions(),
        count: this.scheduler.size,
      });
    }

    // GET /admin/drain
    if (path === '/admin/drain') {
      return sendJson(res, 200, this.drainManager.toJSON());
    }

    // POST /admin/reload
    if (path === '/admin/reload' && method === 'POST') {
      try {
        await this.loader.reloadAll();
        return sendJson(res, 200, {
          status: 'reloaded',
          functions: this.loader.allFunctions().length,
        });
      } catch (e) {
        return sendJson(res, 500, { error: `Reload failed: ${e.message}` });
      }
    }

    // GET /admin/status (aggregate)
    if (path === '/admin/status') {
      return sendJson(res, 200, {
        version: VERSION,
        runtime: 'node',
        uptimeMs: Date.now() - this.startTime,
        routeCount: this.loader.allFunctions().length,
        heapObjects: this.heap.size(),
        inFlight: this.drainManager.inFlightCount(),
        draining: this.drainManager.isDraining(),
        scheduledFunctions: this.scheduler.size,
      });
    }

    return sendJson(res, 404, { error: `Unknown admin endpoint: ${path}` });
  }

  /** @private */
  _printBanner(port) {
    const totalRoutes = this.loader.allFunctions().length;
    const scheduled = this.scheduler.size;
    console.log('');
    console.log('  ╔════════════════════════════════════════════════════╗');
    console.log(`  ║   KubeFn v${VERSION} — Node.js Runtime                 ║`);
    console.log('  ║   Memory-Continuous Architecture                   ║');
    console.log('  ╠════════════════════════════════════════════════════╣');
    console.log(`  ║  HTTP:       port ${port}                              ║`);
    console.log(`  ║  Functions:  ${totalRoutes} routes loaded                      ║`);
    console.log(`  ║  Scheduled:  ${scheduled} cron jobs                           ║`);
    console.log('  ║  Heap:       enabled (zero-copy)                   ║');
    console.log('  ║  Breakers:   enabled                               ║');
    console.log('  ║  Metrics:    /admin/metrics (Prometheus)           ║');
    console.log('  ║  Tracing:    /admin/traces/recent                  ║');
    console.log('  ║  Runtime:    V8 / Node.js                          ║');
    console.log('  ╚════════════════════════════════════════════════════╝');
    console.log(`  KubeFn Node.js organism is ALIVE. ${totalRoutes} routes, ${scheduled} scheduled.`);
    console.log('');
  }
}

// ── Helpers ─────────────────────────────────────────────────────

function sendJson(res, status, data, extraHeaders = {}) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    ...extraHeaders,
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    const MAX_BODY = 10 * 1024 * 1024; // 10 MB

    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        req.destroy();
        reject(new Error(`Request body exceeds ${MAX_BODY} bytes`));
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

/**
 * Execute a function with a timeout deadline.
 * Inlined here to avoid circular dependency with request-timeout.js.
 */
function executeWithDeadline(fn, timeoutMs, context) {
  if (!timeoutMs || timeoutMs <= 0) return fn();

  return new Promise((resolve, reject) => {
    let settled = false;

    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        const err = new Error(
          `Function '${context}' timed out after ${timeoutMs}ms`
        );
        err.name = 'TimeoutError';
        reject(err);
      }
    }, timeoutMs);

    if (timer.unref) timer.unref();

    Promise.resolve(fn()).then(
      (result) => {
        if (!settled) { settled = true; clearTimeout(timer); resolve(result); }
      },
      (err) => {
        if (!settled) { settled = true; clearTimeout(timer); reject(err); }
      }
    );
  });
}
