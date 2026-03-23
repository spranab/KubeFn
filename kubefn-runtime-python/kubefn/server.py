"""
KubeFn Python HTTP Server — production-grade request dispatcher.

Dispatches requests to registered function handlers on the shared interpreter.
Integrates: circuit breaker, drain manager, request timeout, causal introspection,
Prometheus metrics, heap guard, and cron scheduler.
"""

from __future__ import annotations

import json
import logging
import time
import os
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from typing import Optional, TYPE_CHECKING

from .heap_exchange import HeapExchange
from .context import FnContext, FnRequest
from .decorators import FunctionMetadata, get_registered_functions
from .loader import FunctionLoader
from .circuit_breaker import CircuitBreakerRegistry
from .drain_manager import DrainManager
from .request_timeout import execute_with_timeout, RequestTimeoutError
from .introspection import CaptureEngine
from .metrics import MetricsRecorder
from .heap_guard import HeapGuard
from .scheduler import SchedulerEngine

logger = logging.getLogger("kubefn.server")


class KubeFnHandler(BaseHTTPRequestHandler):
    """HTTP request handler that dispatches to KubeFn functions."""

    # Shared state — set by run_server() before serving
    heap: HeapExchange = None
    loader: FunctionLoader = None
    request_counter: int = 0
    start_time: float = 0

    # Production subsystems
    circuit_breakers: CircuitBreakerRegistry = None
    drain_manager: DrainManager = None
    capture_engine: CaptureEngine = None
    metrics_recorder: MetricsRecorder = None
    heap_guard: HeapGuard = None
    scheduler: SchedulerEngine = None
    request_timeout_ms: int = 30_000

    def do_GET(self):
        self._handle_request("GET")

    def do_POST(self):
        self._handle_request("POST")

    def do_PUT(self):
        self._handle_request("PUT")

    def do_DELETE(self):
        self._handle_request("DELETE")

    def _handle_request(self, method: str):
        parsed = urlparse(self.path)
        path = parsed.path
        query_params = {k: v[0] for k, v in parse_qs(parsed.query).items()}

        # Admin endpoints (bypass drain/circuit breaker)
        if path.startswith("/admin") or path in ("/healthz", "/readyz"):
            self._handle_admin(method, path, query_params)
            return

        # ── Drain check ───────────────────────────────────────────────
        if self.drain_manager and not self.drain_manager.acquire():
            self._send_json(503, {
                "error": "Service is draining — not accepting new requests",
                "status": 503,
                "runtime": "python",
            })
            return

        acquired_drain = self.drain_manager is not None

        try:
            self._dispatch_function(method, path, query_params)
        finally:
            if acquired_drain and self.drain_manager:
                self.drain_manager.release()

    def _dispatch_function(self, method: str, path: str, query_params: dict):
        """Core dispatch logic with all production integrations."""
        KubeFnHandler.request_counter += 1
        request_id = f"py-req-{int(time.time() * 1000):x}-{KubeFnHandler.request_counter}"

        # Update in-flight gauge
        if self.metrics_recorder:
            self.metrics_recorder.in_flight += 1

        try:
            # ── Route resolution ──────────────────────────────────────
            matched = self._resolve_function(method, path)
            if not matched:
                self._send_json(404, {
                    "error": f"No function for {method} {path}",
                    "status": 404,
                    "requestId": request_id,
                })
                return

            fn_meta = matched
            func_key = f"{fn_meta.group}.{fn_meta.name}"

            # ── Circuit breaker check ─────────────────────────────────
            if self.circuit_breakers and not self.circuit_breakers.is_allowed(func_key):
                self._send_json(503, {
                    "error": f"Circuit breaker OPEN for {func_key}",
                    "status": 503,
                    "requestId": request_id,
                    "circuitState": "OPEN",
                })
                return

            # ── Capture: request start ────────────────────────────────
            if self.capture_engine:
                self.capture_engine.capture_request_start(
                    request_id, fn_meta.name, fn_meta.group
                )

            # Read body
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length) if content_length > 0 else b""

            # Build request + context
            headers = {k.lower(): v for k, v in self.headers.items()}
            request = FnRequest(
                method=method, path=path, headers=headers,
                query_params=query_params, body=body,
                body_text=body.decode("utf-8") if body else "",
            )

            ctx = FnContext(
                heap=KubeFnHandler.heap,
                group_name=fn_meta.group,
                function_name=fn_meta.name,
                revision_id="py-rev-1",
                config={},
            )

            # Set heap context for attribution
            KubeFnHandler.heap.set_context(fn_meta.group, fn_meta.name, request_id)

            # ── Capture: function start ───────────────────────────────
            if self.capture_engine:
                self.capture_engine.capture_function_start(
                    request_id, fn_meta.name, fn_meta.group
                )

            # ── Execute with timeout ──────────────────────────────────
            start_ns = time.time_ns()
            try:
                result = execute_with_timeout(
                    func=fn_meta.handler,
                    args=(request, ctx),
                    timeout_ms=self.request_timeout_ms,
                    func_name=func_key,
                )
                duration_ns = time.time_ns() - start_ns
                duration_ms = duration_ns / 1_000_000

                # ── Capture: function end ─────────────────────────────
                if self.capture_engine:
                    self.capture_engine.capture_function_end(
                        request_id, fn_meta.name, fn_meta.group, duration_ns
                    )

                # ── Circuit breaker: success ──────────────────────────
                if self.circuit_breakers:
                    self.circuit_breakers.record_success(func_key)

                # ── Metrics ───────────────────────────────────────────
                if self.metrics_recorder:
                    self.metrics_recorder.record_invocation(func_key, duration_ms, error=False)

                # Build response
                response = result if isinstance(result, dict) else {"result": result}
                response.setdefault("_meta", {}).update({
                    "requestId": request_id,
                    "durationMs": f"{duration_ms:.3f}",
                    "function": fn_meta.name,
                    "group": fn_meta.group,
                    "runtime": "python",
                    "zeroCopy": True,
                })

                self._send_json(200, response, extra_headers={
                    "X-KubeFn-Request-Id": request_id,
                    "X-KubeFn-Runtime": "python-0.4.0",
                    "X-KubeFn-Group": fn_meta.group,
                })

            except RequestTimeoutError as e:
                duration_ns = time.time_ns() - start_ns
                duration_ms = duration_ns / 1_000_000
                logger.error(f"Timeout: {func_key} [{request_id}]: {e}")

                if self.capture_engine:
                    self.capture_engine.capture_error(
                        request_id, fn_meta.name, fn_meta.group, str(e)
                    )
                if self.circuit_breakers:
                    self.circuit_breakers.record_failure(func_key)
                if self.metrics_recorder:
                    self.metrics_recorder.record_invocation(func_key, duration_ms, error=True)

                self._send_json(504, {
                    "error": str(e),
                    "function": func_key,
                    "requestId": request_id,
                    "durationMs": f"{duration_ms:.3f}",
                })

            except Exception as e:
                duration_ns = time.time_ns() - start_ns
                duration_ms = duration_ns / 1_000_000
                logger.error(f"Function error: {func_key} [{request_id}]: {e}")

                if self.capture_engine:
                    self.capture_engine.capture_error(
                        request_id, fn_meta.name, fn_meta.group, str(e)
                    )
                    self.capture_engine.capture_function_end(
                        request_id, fn_meta.name, fn_meta.group, duration_ns
                    )
                if self.circuit_breakers:
                    self.circuit_breakers.record_failure(func_key)
                if self.metrics_recorder:
                    self.metrics_recorder.record_invocation(func_key, duration_ms, error=True)

                self._send_json(500, {
                    "error": str(e),
                    "function": func_key,
                    "requestId": request_id,
                    "durationMs": f"{duration_ms:.3f}",
                })

            finally:
                KubeFnHandler.heap.clear_context()

                # ── Capture: request end ──────────────────────────────
                total_ns = time.time_ns() - start_ns
                if self.capture_engine:
                    self.capture_engine.capture_request_end(
                        request_id, fn_meta.name, fn_meta.group, total_ns
                    )

                # Sync heap metrics
                if self.metrics_recorder:
                    heap = KubeFnHandler.heap
                    self.metrics_recorder.update_heap_stats(
                        object_count=heap.size(),
                        publish_count=heap.publish_count,
                        get_count=heap.get_count,
                        hit_count=heap.hit_count,
                    )

        finally:
            if self.metrics_recorder:
                self.metrics_recorder.in_flight -= 1

    def _resolve_function(self, method: str, path: str) -> Optional[FunctionMetadata]:
        """Find the function that matches this method + path."""
        best_match = None
        best_len = 0

        for fn in get_registered_functions():
            if method.upper() in [m.upper() for m in fn.methods]:
                if path == fn.path or path.startswith(fn.path + "/"):
                    if len(fn.path) > best_len:
                        best_match = fn
                        best_len = len(fn.path)

        return best_match

    def _handle_admin(self, method: str, path: str, query_params: dict):
        """Handle admin/health endpoints."""
        functions = get_registered_functions()

        match path:
            case "/healthz" | "/admin/health":
                self._send_json(200, {
                    "status": "UP",
                    "organism": "kubefn",
                    "version": "0.4.0",
                    "runtime": "python",
                })

            case "/readyz" | "/admin/ready":
                ready = len(functions) > 0
                self._send_json(200 if ready else 503, {
                    "status": "READY" if ready else "NOT_READY",
                    "functions": len(functions),
                    "runtime": "python",
                })

            case "/admin/functions":
                fn_list = []
                for fn in functions:
                    for m in fn.methods:
                        fn_list.append({
                            "method": m,
                            "path": fn.path,
                            "group": fn.group,
                            "function": fn.name,
                            "runtime": "python",
                        })
                self._send_json(200, {"functions": fn_list, "count": len(fn_list)})

            case "/admin/heap":
                data = KubeFnHandler.heap.metrics()
                if self.heap_guard:
                    data["guard"] = self.heap_guard.status()
                self._send_json(200, data)

            case "/admin/metrics":
                if self.metrics_recorder:
                    text = self.metrics_recorder.prometheus_text()
                    body = text.encode("utf-8")
                    self.send_response(200)
                    self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                else:
                    self._send_json(503, {"error": "Metrics not enabled"})

            case "/admin/breakers":
                if self.circuit_breakers:
                    self._send_json(200, {
                        "breakers": self.circuit_breakers.get_status()
                    })
                else:
                    self._send_json(503, {"error": "Circuit breakers not enabled"})

            case "/admin/traces/recent":
                if self.capture_engine:
                    limit = int(query_params.get("limit", "20"))
                    self._send_json(200, {
                        "traces": self.capture_engine.recent_traces(limit),
                        "eventCount": self.capture_engine.event_count(),
                    })
                else:
                    self._send_json(503, {"error": "Introspection not enabled"})

            case "/admin/scheduler":
                if self.scheduler:
                    self._send_json(200, {
                        "scheduled": self.scheduler.get_scheduled_functions(),
                    })
                else:
                    self._send_json(503, {"error": "Scheduler not enabled"})

            case "/admin/drain":
                if self.drain_manager:
                    self._send_json(200, self.drain_manager.status())
                else:
                    self._send_json(503, {"error": "Drain manager not enabled"})

            case "/admin/status":
                uptime = time.time() - KubeFnHandler.start_time
                self._send_json(200, {
                    "version": "0.4.0",
                    "runtime": "python",
                    "uptime_s": int(uptime),
                    "route_count": len(functions),
                    "heap_objects": KubeFnHandler.heap.size(),
                    "total_requests": KubeFnHandler.request_counter,
                })

            case _ if path.startswith("/admin/traces/"):
                # GET /admin/traces/{requestId}
                if self.capture_engine:
                    request_id = path.split("/admin/traces/", 1)[1]
                    trace = self.capture_engine.get_trace(request_id)
                    self._send_json(200, trace)
                else:
                    self._send_json(503, {"error": "Introspection not enabled"})

            case "/admin/reload" if method == "POST":
                self._handle_reload()

            case _:
                self._send_json(404, {"error": f"Unknown admin endpoint: {path}"})

    def _handle_reload(self):
        """Trigger function reload with drain coordination."""
        loader = KubeFnHandler.loader
        if loader is None:
            self._send_json(503, {"error": "Loader not available"})
            return

        logger.info("Admin reload triggered")

        # Drain before reload if manager is available
        if self.drain_manager:
            self.drain_manager.start_drain(timeout_s=10.0)

        try:
            loaded = loader.load_all()
            total_routes = sum(len(fns) for fns in loaded.values())
            logger.info(f"Reload complete: {total_routes} routes")
            self._send_json(200, {
                "status": "reloaded",
                "groups": len(loaded),
                "routes": total_routes,
            })
        except Exception as e:
            logger.error(f"Reload failed: {e}")
            self._send_json(500, {"error": f"Reload failed: {e}"})
        finally:
            if self.drain_manager:
                self.drain_manager.cancel_drain()

    def _send_json(self, status: int, data: dict, extra_headers: dict = None):
        body = json.dumps(data, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        """Suppress default HTTP server logs — we use our own."""
        pass


def run_server(
    host: str = "0.0.0.0",
    port: int = 8080,
    functions_dir: str = "/var/kubefn/functions",
    heap_guard: HeapGuard | None = None,
    circuit_breakers: CircuitBreakerRegistry | None = None,
    drain_manager: DrainManager | None = None,
    capture_engine: CaptureEngine | None = None,
    metrics_recorder: MetricsRecorder | None = None,
    scheduler: SchedulerEngine | None = None,
    request_timeout_ms: int = 30_000,
):
    """Start the KubeFn Python runtime with all production subsystems."""

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
    )

    logger.info("Booting KubeFn Python organism v0.4.0...")

    # ── Create shared HeapExchange ────────────────────────────────────
    heap = HeapExchange()

    # Wire optional subsystems into heap
    if heap_guard:
        heap.set_guard(heap_guard)
        heap_guard.start_cleanup()
    if capture_engine:
        heap.set_capture_engine(capture_engine)

    # ── Set handler class attributes ──────────────────────────────────
    KubeFnHandler.heap = heap
    KubeFnHandler.start_time = time.time()
    KubeFnHandler.circuit_breakers = circuit_breakers
    KubeFnHandler.drain_manager = drain_manager
    KubeFnHandler.capture_engine = capture_engine
    KubeFnHandler.metrics_recorder = metrics_recorder
    KubeFnHandler.heap_guard = heap_guard
    KubeFnHandler.scheduler = scheduler
    KubeFnHandler.request_timeout_ms = request_timeout_ms

    # ── Load functions ────────────────────────────────────────────────
    loader = FunctionLoader(functions_dir, heap)
    KubeFnHandler.loader = loader
    loaded = loader.load_all()

    total_routes = sum(len(fns) for fns in loaded.values())

    # ── Register scheduled functions ──────────────────────────────────
    if scheduler:
        from .scheduler import get_scheduled_registry
        for entry in get_scheduled_registry():
            scheduler.register(
                name=entry["name"],
                group=entry["group"],
                cron_expression=entry["cron"],
                handler=entry["handler"],
                skip_if_running=entry["skip_if_running"],
            )
        scheduler.start()

    # ── Banner ────────────────────────────────────────────────────────
    subsystems = []
    if circuit_breakers:
        subsystems.append("CircuitBreaker")
    if drain_manager:
        subsystems.append("DrainManager")
    if capture_engine:
        subsystems.append("CausalIntrospection")
    if metrics_recorder:
        subsystems.append("Prometheus")
    if heap_guard:
        subsystems.append("HeapGuard")
    if scheduler:
        subsystems.append("Scheduler")

    logger.info("+" + "=" * 58 + "+")
    logger.info("|   KubeFn v0.4.0 -- Python Runtime (Production)           |")
    logger.info("|   Memory-Continuous Architecture                         |")
    logger.info("+" + "-" * 58 + "+")
    logger.info(f"|  HTTP:       port {port:<40}|")
    logger.info(f"|  Functions:  {functions_dir:<45}|")
    logger.info(f"|  Routes:     {total_routes:<45}|")
    logger.info(f"|  Timeout:    {request_timeout_ms}ms{' ' * (42 - len(str(request_timeout_ms)))}|")
    logger.info(f"|  Subsystems: {', '.join(subsystems) if subsystems else 'none':<45}|")
    logger.info("|  HeapExchange: enabled (zero-copy)                       |")
    logger.info("|  Runtime: CPython                                        |")
    logger.info("+" + "=" * 58 + "+")
    logger.info(f"KubeFn Python organism is ALIVE. {total_routes} routes registered.")

    # ── Serve ─────────────────────────────────────────────────────────
    server = HTTPServer((host, port), KubeFnHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("Shutting down Python organism...")

        # Graceful shutdown sequence
        if drain_manager:
            logger.info("Draining in-flight requests...")
            drain_manager.start_drain(timeout_s=15.0)

        if scheduler:
            logger.info("Stopping scheduler...")
            scheduler.stop()

        if heap_guard:
            logger.info("Stopping heap guard cleanup...")
            heap_guard.stop_cleanup()

        from .request_timeout import shutdown_executor
        shutdown_executor()

        server.shutdown()
        logger.info("KubeFn Python organism shutdown complete.")
