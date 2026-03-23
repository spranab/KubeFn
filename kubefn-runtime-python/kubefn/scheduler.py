"""
Cron Scheduler — periodic function invocation.

Supports @schedule decorated functions with 5-field cron expressions
(minute, hour, day-of-month, month, day-of-week).

Runs a background thread that checks every 60 seconds for matching
functions and invokes them with a synthetic request.
"""

import logging
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable

logger = logging.getLogger("kubefn.scheduler")


@dataclass
class CronExpression:
    """Parsed 5-field cron: minute hour day month weekday."""
    minute: set[int]
    hour: set[int]
    day: set[int]
    month: set[int]
    weekday: set[int]  # 0=Monday .. 6=Sunday

    def matches(self, dt: datetime) -> bool:
        return (
            dt.minute in self.minute
            and dt.hour in self.hour
            and dt.day in self.day
            and dt.month in self.month
            and dt.weekday() in self.weekday
        )


def parse_cron_field(field_str: str, min_val: int, max_val: int) -> set[int]:
    """
    Parse a single cron field into a set of matching integers.

    Supports:
      *       → all values
      5       → exact value
      1,3,5   → list
      1-5     → range
      */15    → step from min
      1-30/5  → step within range
    """
    values: set[int] = set()

    for part in field_str.split(","):
        part = part.strip()

        if "/" in part:
            range_part, step_str = part.split("/", 1)
            step = int(step_str)
            if range_part == "*":
                start, end = min_val, max_val
            elif "-" in range_part:
                start, end = (int(x) for x in range_part.split("-", 1))
            else:
                start, end = int(range_part), max_val
            values.update(range(start, end + 1, step))

        elif part == "*":
            values.update(range(min_val, max_val + 1))

        elif "-" in part:
            start, end = (int(x) for x in part.split("-", 1))
            values.update(range(start, end + 1))

        else:
            values.add(int(part))

    return values


def parse_cron(expression: str) -> CronExpression:
    """Parse a 5-field cron expression string."""
    fields = expression.strip().split()
    if len(fields) != 5:
        raise ValueError(
            f"Invalid cron expression '{expression}': expected 5 fields, got {len(fields)}"
        )

    return CronExpression(
        minute=parse_cron_field(fields[0], 0, 59),
        hour=parse_cron_field(fields[1], 0, 23),
        day=parse_cron_field(fields[2], 1, 31),
        month=parse_cron_field(fields[3], 1, 12),
        weekday=parse_cron_field(fields[4], 0, 6),
    )


@dataclass
class ScheduledFunction:
    """A function registered with a cron schedule."""
    name: str
    group: str
    cron_expression: str
    cron: CronExpression
    handler: Callable
    skip_if_running: bool = True

    # Tracking
    last_run: float | None = None
    next_fire: float | None = None
    run_count: int = 0
    error_count: int = 0
    is_running: bool = False
    last_error: str | None = None
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)


class SchedulerEngine:
    """
    Background cron scheduler for KubeFn functions.

    Checks every 60 seconds for functions whose cron expressions
    match the current time and invokes them with a synthetic request.
    """

    def __init__(self, context_factory: Callable | None = None):
        self._functions: dict[str, ScheduledFunction] = {}
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._stop_event = threading.Event()
        self._context_factory = context_factory
        self._check_interval_s = 60

    def register(
        self,
        name: str,
        group: str,
        cron_expression: str,
        handler: Callable,
        skip_if_running: bool = True,
    ) -> None:
        """Register a function with a cron schedule."""
        cron = parse_cron(cron_expression)
        key = f"{group}.{name}"
        with self._lock:
            self._functions[key] = ScheduledFunction(
                name=name,
                group=group,
                cron_expression=cron_expression,
                cron=cron,
                handler=handler,
                skip_if_running=skip_if_running,
            )
        logger.info(f"Scheduled: {key} → '{cron_expression}'")

    def unregister(self, name: str, group: str) -> None:
        key = f"{group}.{name}"
        with self._lock:
            self._functions.pop(key, None)
        logger.info(f"Unscheduled: {key}")

    def start(self) -> None:
        """Start the scheduler background thread."""
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._run_loop,
            name="kubefn-scheduler",
            daemon=True,
        )
        self._thread.start()
        logger.info("Scheduler engine started")

    def stop(self) -> None:
        """Stop the scheduler."""
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=10.0)
            self._thread = None
        logger.info("Scheduler engine stopped")

    def _run_loop(self) -> None:
        while not self._stop_event.is_set():
            self._stop_event.wait(timeout=self._check_interval_s)
            if self._stop_event.is_set():
                break
            self._tick()

    def _tick(self) -> None:
        """Check all registered functions against current time."""
        now = datetime.now()

        with self._lock:
            candidates = list(self._functions.values())

        for sf in candidates:
            if sf.cron.matches(now):
                if sf.skip_if_running and sf.is_running:
                    logger.debug(
                        f"Skipping {sf.group}.{sf.name}: still running from last invocation"
                    )
                    continue
                self._invoke(sf)

    def _invoke(self, sf: ScheduledFunction) -> None:
        """Invoke a scheduled function in a new thread."""
        thread = threading.Thread(
            target=self._invoke_inner,
            args=(sf,),
            name=f"kubefn-sched-{sf.group}.{sf.name}",
            daemon=True,
        )
        thread.start()

    def _invoke_inner(self, sf: ScheduledFunction) -> None:
        with sf._lock:
            sf.is_running = True

        start = time.time()
        try:
            # Build a synthetic request
            from .context import FnRequest, FnContext

            request = FnRequest(
                method="SCHEDULE",
                path=f"/_schedule/{sf.group}/{sf.name}",
                headers={"x-kubefn-trigger": "scheduler"},
                query_params={},
                body=b"",
                body_text="",
            )

            # Use context factory if available, otherwise pass None
            ctx = None
            if self._context_factory:
                ctx = self._context_factory(sf.group, sf.name)

            sf.handler(request, ctx)

            with sf._lock:
                sf.run_count += 1
                sf.last_run = time.time()
                sf.last_error = None

            duration_ms = (time.time() - start) * 1000
            logger.info(
                f"Scheduled {sf.group}.{sf.name} completed in {duration_ms:.1f}ms"
            )

        except Exception as e:
            with sf._lock:
                sf.run_count += 1
                sf.error_count += 1
                sf.last_run = time.time()
                sf.last_error = str(e)
            logger.error(f"Scheduled {sf.group}.{sf.name} failed: {e}")

        finally:
            with sf._lock:
                sf.is_running = False

    def get_scheduled_functions(self) -> list[dict]:
        """Return status of all scheduled functions for admin API."""
        with self._lock:
            result = []
            for key, sf in sorted(self._functions.items()):
                with sf._lock:
                    result.append({
                        "name": sf.name,
                        "group": sf.group,
                        "cron": sf.cron_expression,
                        "lastRun": sf.last_run,
                        "runCount": sf.run_count,
                        "errorCount": sf.error_count,
                        "isRunning": sf.is_running,
                        "lastError": sf.last_error,
                        "skipIfRunning": sf.skip_if_running,
                    })
            return result


# ── Decorator ─────────────────────────────────────────────────────────

# Global list of schedule-decorated functions, consumed at startup
_scheduled_registry: list[dict] = []


def schedule(cron: str, group: str = "default", skip_if_running: bool = True):
    """
    Decorator to register a function as a cron-scheduled handler.

    Usage:
        @schedule("*/5 * * * *", group="reports")
        def generate_report(request, ctx):
            ...
    """
    def decorator(func):
        _scheduled_registry.append({
            "name": func.__name__,
            "group": group,
            "cron": cron,
            "handler": func,
            "skip_if_running": skip_if_running,
        })
        return func
    return decorator


def get_scheduled_registry() -> list[dict]:
    return list(_scheduled_registry)
