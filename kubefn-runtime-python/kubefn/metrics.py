"""
Prometheus-compatible Metrics — per-function counters, histograms, and system gauges.

Exports metrics in Prometheus text exposition format for scraping at /admin/metrics.
All counters are thread-safe via threading.Lock.
"""

import logging
import threading
import time

logger = logging.getLogger("kubefn.metrics")

# Default histogram buckets (milliseconds) matching common latency distributions
DEFAULT_DURATION_BUCKETS_MS: tuple[float, ...] = (
    5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000,
)


class FunctionMetrics:
    """Counters and histogram for a single function."""

    __slots__ = (
        "name", "request_count", "error_count",
        "duration_sum_ms", "_duration_buckets", "_bucket_counts",
        "_lock",
    )

    def __init__(
        self, name: str, buckets: tuple[float, ...] = DEFAULT_DURATION_BUCKETS_MS
    ):
        self.name = name
        self.request_count: int = 0
        self.error_count: int = 0
        self.duration_sum_ms: float = 0.0
        self._duration_buckets = buckets
        self._bucket_counts: list[int] = [0] * len(buckets)
        self._lock = threading.Lock()

    def record(self, duration_ms: float, error: bool = False) -> None:
        with self._lock:
            self.request_count += 1
            self.duration_sum_ms += duration_ms
            if error:
                self.error_count += 1
            # Update histogram buckets
            for i, bound in enumerate(self._duration_buckets):
                if duration_ms <= bound:
                    self._bucket_counts[i] += 1

    def snapshot(self) -> dict:
        with self._lock:
            avg = (
                self.duration_sum_ms / self.request_count
                if self.request_count > 0
                else 0.0
            )
            return {
                "requestCount": self.request_count,
                "errorCount": self.error_count,
                "durationSumMs": round(self.duration_sum_ms, 3),
                "durationAvgMs": round(avg, 3),
            }

    def prometheus_lines(self, prefix: str = "kubefn") -> list[str]:
        """Generate Prometheus exposition lines for this function."""
        lines: list[str] = []
        label = f'function="{self.name}"'

        with self._lock:
            lines.append(
                f'{prefix}_function_requests_total{{{label}}} {self.request_count}'
            )
            lines.append(
                f'{prefix}_function_errors_total{{{label}}} {self.error_count}'
            )
            lines.append(
                f'{prefix}_function_duration_ms_sum{{{label}}} {self.duration_sum_ms:.3f}'
            )
            lines.append(
                f'{prefix}_function_duration_ms_count{{{label}}} {self.request_count}'
            )
            cumulative = 0
            for i, bound in enumerate(self._duration_buckets):
                cumulative += self._bucket_counts[i]
                lines.append(
                    f'{prefix}_function_duration_ms_bucket{{{label},le="{bound}"}} {cumulative}'
                )
            lines.append(
                f'{prefix}_function_duration_ms_bucket{{{label},le="+Inf"}} {self.request_count}'
            )

        return lines


class MetricsRecorder:
    """
    Central metrics aggregator. Thread-safe.

    Tracks per-function metrics plus system-wide gauges.
    """

    def __init__(self):
        self._functions: dict[str, FunctionMetrics] = {}
        self._lock = threading.Lock()
        self._start_time = time.time()

        # Heap metrics (updated externally)
        self.heap_object_count: int = 0
        self.heap_publish_count: int = 0
        self.heap_get_count: int = 0
        self.heap_hit_count: int = 0

        # System
        self.in_flight: int = 0

    def _get_or_create(self, func_name: str) -> FunctionMetrics:
        fm = self._functions.get(func_name)
        if fm is not None:
            return fm
        with self._lock:
            if func_name not in self._functions:
                self._functions[func_name] = FunctionMetrics(func_name)
            return self._functions[func_name]

    def record_invocation(
        self, func_name: str, duration_ms: float, error: bool = False
    ) -> None:
        self._get_or_create(func_name).record(duration_ms, error=error)

    def update_heap_stats(
        self, object_count: int, publish_count: int, get_count: int, hit_count: int
    ) -> None:
        self.heap_object_count = object_count
        self.heap_publish_count = publish_count
        self.heap_get_count = get_count
        self.heap_hit_count = hit_count

    def prometheus_text(self) -> str:
        """
        Render all metrics in Prometheus text exposition format.
        """
        lines: list[str] = []
        prefix = "kubefn"

        # ── HELP / TYPE declarations ──
        lines.append(f"# HELP {prefix}_function_requests_total Total requests per function")
        lines.append(f"# TYPE {prefix}_function_requests_total counter")
        lines.append(f"# HELP {prefix}_function_errors_total Total errors per function")
        lines.append(f"# TYPE {prefix}_function_errors_total counter")
        lines.append(f"# HELP {prefix}_function_duration_ms Duration histogram (ms)")
        lines.append(f"# TYPE {prefix}_function_duration_ms histogram")

        with self._lock:
            func_names = sorted(self._functions.keys())

        for name in func_names:
            fm = self._functions[name]
            lines.extend(fm.prometheus_lines(prefix))

        # ── Heap gauges ──
        lines.append(f"# HELP {prefix}_heap_objects Current heap object count")
        lines.append(f"# TYPE {prefix}_heap_objects gauge")
        lines.append(f"{prefix}_heap_objects {self.heap_object_count}")

        lines.append(f"# HELP {prefix}_heap_publishes_total Total heap publishes")
        lines.append(f"# TYPE {prefix}_heap_publishes_total counter")
        lines.append(f"{prefix}_heap_publishes_total {self.heap_publish_count}")

        lines.append(f"# HELP {prefix}_heap_gets_total Total heap gets")
        lines.append(f"# TYPE {prefix}_heap_gets_total counter")
        lines.append(f"{prefix}_heap_gets_total {self.heap_get_count}")

        lines.append(f"# HELP {prefix}_heap_hits_total Total heap hits")
        lines.append(f"# TYPE {prefix}_heap_hits_total counter")
        lines.append(f"{prefix}_heap_hits_total {self.heap_hit_count}")

        hit_rate = (
            self.heap_hit_count / self.heap_get_count
            if self.heap_get_count > 0
            else 0.0
        )
        lines.append(f"# HELP {prefix}_heap_hit_rate Heap hit rate (0-1)")
        lines.append(f"# TYPE {prefix}_heap_hit_rate gauge")
        lines.append(f"{prefix}_heap_hit_rate {hit_rate:.4f}")

        # ── System gauges ──
        uptime = time.time() - self._start_time
        lines.append(f"# HELP {prefix}_uptime_seconds Uptime in seconds")
        lines.append(f"# TYPE {prefix}_uptime_seconds gauge")
        lines.append(f"{prefix}_uptime_seconds {uptime:.0f}")

        lines.append(f"# HELP {prefix}_in_flight_requests Current in-flight requests")
        lines.append(f"# TYPE {prefix}_in_flight_requests gauge")
        lines.append(f"{prefix}_in_flight_requests {self.in_flight}")

        lines.append("")  # trailing newline
        return "\n".join(lines)
