#!/usr/bin/env node

/**
 * KubeFn Node.js Runtime — Entry Point
 *
 * Wires all engines together and starts the server:
 *   HeapGuard → HeapExchange
 *   CircuitBreakerRegistry
 *   DrainManager
 *   CaptureEngine
 *   MetricsRecorder
 *   SchedulerEngine
 *   FunctionLoader → Server
 *
 * Graceful shutdown: SIGTERM / SIGINT → drain → stop scheduler → close server
 *
 * CLI args:
 *   --port <number>           HTTP port (default 8080 or KUBEFN_PORT)
 *   --functions-dir <path>    Function directory (default /var/kubefn/functions)
 *   --request-timeout <ms>    Per-request timeout (default 30000)
 *   --max-heap-objects <n>    HeapGuard object limit (default 10000)
 *   --max-heap-bytes <n>      HeapGuard byte limit (default 100000000)
 *   --ring-capacity <n>       Introspection ring capacity (default 50000)
 */

import { HeapExchange } from './heap-exchange.js';
import { HeapGuard } from './heap-guard.js';
import { CircuitBreakerRegistry } from './circuit-breaker.js';
import { DrainManager } from './drain-manager.js';
import { CaptureEngine } from './introspection.js';
import { MetricsRecorder } from './metrics.js';
import { SchedulerEngine } from './scheduler.js';
import { FunctionLoader } from './loader.js';
import { KubeFnServer } from './server.js';

// ── CLI arg parsing (no deps) ───────────────────────────────

function parseArgs(argv) {
  const args = {};
  for (let i = 2; i < argv.length; i++) {
    const arg = argv[i];
    if (arg.startsWith('--') && i + 1 < argv.length) {
      const key = arg.slice(2);
      const val = argv[++i];
      args[key] = val;
    }
  }
  return args;
}

const args = parseArgs(process.argv);

const PORT = parseInt(args['port'] || process.env.KUBEFN_PORT || '8080', 10);
const FUNCTIONS_DIR = args['functions-dir'] || process.env.KUBEFN_FUNCTIONS_DIR || '/var/kubefn/functions';
const REQUEST_TIMEOUT = parseInt(args['request-timeout'] || process.env.KUBEFN_REQUEST_TIMEOUT || '30000', 10);
const MAX_HEAP_OBJECTS = parseInt(args['max-heap-objects'] || process.env.KUBEFN_MAX_HEAP_OBJECTS || '10000', 10);
const MAX_HEAP_BYTES = parseInt(args['max-heap-bytes'] || process.env.KUBEFN_MAX_HEAP_BYTES || '100000000', 10);
const RING_CAPACITY = parseInt(args['ring-capacity'] || process.env.KUBEFN_RING_CAPACITY || '50000', 10);

// ── Create engines ──────────────────────────────────────────

const heapGuard = new HeapGuard({
  maxObjects: MAX_HEAP_OBJECTS,
  maxSizeBytes: MAX_HEAP_BYTES,
});

const captureEngine = new CaptureEngine(RING_CAPACITY);

const heap = new HeapExchange({
  guard: heapGuard,
  captureEngine,
});

const breakerRegistry = new CircuitBreakerRegistry();
const drainManager = new DrainManager();
const metrics = new MetricsRecorder();
const scheduler = new SchedulerEngine();

const loader = new FunctionLoader(FUNCTIONS_DIR, heap, { scheduler });

const server = new KubeFnServer(
  { heap, loader, drainManager, breakerRegistry, captureEngine, metrics, scheduler },
  { requestTimeoutMs: REQUEST_TIMEOUT }
);

// ── Boot sequence ───────────────────────────────────────────

async function boot() {
  // 1. Load functions
  await loader.loadAll();

  // 2. Start scheduler
  scheduler.start();

  // 3. Start HTTP server
  await server.listen(PORT);
}

// ── Graceful shutdown ───────────────────────────────────────

let shuttingDown = false;

async function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;

  console.log(`\n[kubefn] ${signal} received — initiating graceful shutdown...`);

  // 1. Stop scheduler (no new cron ticks)
  scheduler.stop();
  console.log('[kubefn] Scheduler stopped.');

  // 2. Drain in-flight requests
  console.log(`[kubefn] Draining ${drainManager.inFlightCount()} in-flight requests...`);
  const drainTimeout = setTimeout(() => {
    console.error('[kubefn] Drain timeout (30s) — forcing exit.');
    process.exit(1);
  }, 30_000);
  if (drainTimeout.unref) drainTimeout.unref();

  await drainManager.startDrain();
  clearTimeout(drainTimeout);
  console.log('[kubefn] All requests drained.');

  // 3. Close server
  await server.close();
  console.log('[kubefn] Server closed.');

  // 4. Cleanup
  heap.destroy();
  console.log('[kubefn] Shutdown complete.');

  process.exit(0);
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

// ── Start ───────────────────────────────────────────────────

boot().catch((err) => {
  console.error(`[kubefn] Fatal: ${err.message}`);
  console.error(err.stack);
  process.exit(1);
});
