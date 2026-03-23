"""
KubeFn Python Runtime — entry point.

Usage:
    python -m kubefn
    python -m kubefn --port 8080 --functions-dir /var/kubefn/functions
    python -m kubefn --timeout 60000 --no-scheduler
"""

import argparse
import os

from .circuit_breaker import CircuitBreakerRegistry
from .drain_manager import DrainManager
from .introspection import CaptureEngine
from .metrics import MetricsRecorder
from .heap_guard import HeapGuard, HeapGuardConfig
from .scheduler import SchedulerEngine
from .server import run_server


def main():
    parser = argparse.ArgumentParser(description="KubeFn Python Runtime")
    parser.add_argument(
        "--port", type=int,
        default=int(os.environ.get("KUBEFN_PORT", "8080")),
    )
    parser.add_argument(
        "--functions-dir",
        default=os.environ.get("KUBEFN_FUNCTIONS_DIR", "/var/kubefn/functions"),
    )
    parser.add_argument(
        "--timeout", type=int,
        default=int(os.environ.get("KUBEFN_TIMEOUT_MS", "30000")),
        help="Request timeout in milliseconds (default: 30000)",
    )
    parser.add_argument(
        "--max-heap-objects", type=int,
        default=int(os.environ.get("KUBEFN_MAX_HEAP_OBJECTS", "10000")),
        help="Maximum objects in HeapExchange (default: 10000)",
    )
    parser.add_argument(
        "--cb-threshold", type=int,
        default=int(os.environ.get("KUBEFN_CB_THRESHOLD", "5")),
        help="Circuit breaker failure threshold (default: 5)",
    )
    parser.add_argument(
        "--cb-reset-timeout", type=float,
        default=float(os.environ.get("KUBEFN_CB_RESET_TIMEOUT", "30.0")),
        help="Circuit breaker reset timeout in seconds (default: 30)",
    )
    parser.add_argument(
        "--drain-timeout", type=float,
        default=float(os.environ.get("KUBEFN_DRAIN_TIMEOUT", "30.0")),
        help="Drain timeout in seconds (default: 30)",
    )
    parser.add_argument(
        "--event-ring-capacity", type=int,
        default=int(os.environ.get("KUBEFN_EVENT_RING_CAPACITY", "50000")),
        help="Causal event ring buffer capacity (default: 50000)",
    )
    parser.add_argument(
        "--no-scheduler", action="store_true",
        default=os.environ.get("KUBEFN_NO_SCHEDULER", "").lower() in ("1", "true"),
        help="Disable the cron scheduler",
    )
    args = parser.parse_args()

    # ── Create production subsystems ──────────────────────────────────

    heap_guard = HeapGuard(HeapGuardConfig(
        max_objects=args.max_heap_objects,
    ))

    circuit_breakers = CircuitBreakerRegistry(
        failure_threshold=args.cb_threshold,
        reset_timeout_s=args.cb_reset_timeout,
    )

    drain_manager = DrainManager(
        drain_timeout_s=args.drain_timeout,
    )

    capture_engine = CaptureEngine(
        capacity=args.event_ring_capacity,
    )

    metrics_recorder = MetricsRecorder()

    scheduler: SchedulerEngine | None = None
    if not args.no_scheduler:
        scheduler = SchedulerEngine()

    # ── Start server with all subsystems ──────────────────────────────

    run_server(
        port=args.port,
        functions_dir=args.functions_dir,
        heap_guard=heap_guard,
        circuit_breakers=circuit_breakers,
        drain_manager=drain_manager,
        capture_engine=capture_engine,
        metrics_recorder=metrics_recorder,
        scheduler=scheduler,
        request_timeout_ms=args.timeout,
    )


if __name__ == "__main__":
    main()
