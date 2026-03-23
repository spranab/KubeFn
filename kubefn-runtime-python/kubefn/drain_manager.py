"""
Drain Manager — graceful shutdown and hot-swap coordination.

Tracks in-flight requests and supports draining: rejecting new requests
while allowing in-flight requests to complete before shutdown or reload.
"""

import logging
import threading
import time

logger = logging.getLogger("kubefn.drain")


class DrainManager:
    """
    Thread-safe drain coordinator. Used during graceful shutdown
    and function hot-swap to ensure zero dropped requests.

    Usage:
        if not drain.acquire():
            return 503  # draining, reject
        try:
            handle_request()
        finally:
            drain.release()
    """

    def __init__(self, drain_timeout_s: float = 30.0):
        self._lock = threading.Lock()
        self._drained = threading.Event()
        self._draining = False
        self._in_flight = 0
        self._drain_timeout_s = drain_timeout_s
        self._drain_started_at: float | None = None

    def acquire(self) -> bool:
        """
        Attempt to acquire a request slot.
        Returns False if the system is draining (caller should return 503).
        """
        with self._lock:
            if self._draining:
                return False
            self._in_flight += 1
            self._drained.clear()
            return True

    def release(self) -> None:
        """Release a request slot after handling completes."""
        with self._lock:
            self._in_flight -= 1
            if self._in_flight <= 0:
                self._in_flight = 0
                self._drained.set()

    def start_drain(self, timeout_s: float | None = None) -> bool:
        """
        Begin draining. Blocks until all in-flight requests complete
        or the timeout expires.

        Returns True if drain completed (in-flight reached 0),
        False if timed out.
        """
        timeout = timeout_s if timeout_s is not None else self._drain_timeout_s

        with self._lock:
            self._draining = True
            self._drain_started_at = time.time()
            if self._in_flight == 0:
                self._drained.set()
                logger.info("Drain: no in-flight requests, drained immediately")
                return True

        logger.info(
            f"Drain started: {self._in_flight} in-flight requests, "
            f"timeout={timeout}s"
        )

        drained = self._drained.wait(timeout=timeout)

        if drained:
            logger.info("Drain complete: all in-flight requests finished")
        else:
            logger.warning(
                f"Drain timed out after {timeout}s with "
                f"{self._in_flight} requests still in-flight"
            )

        return drained

    def cancel_drain(self) -> None:
        """Cancel an active drain (e.g. if reload was aborted)."""
        with self._lock:
            self._draining = False
            self._drain_started_at = None
        logger.info("Drain cancelled")

    def is_draining(self) -> bool:
        return self._draining

    def in_flight_count(self) -> int:
        return self._in_flight

    def status(self) -> dict:
        with self._lock:
            result: dict = {
                "draining": self._draining,
                "inFlight": self._in_flight,
                "drainTimeoutS": self._drain_timeout_s,
            }
            if self._drain_started_at is not None:
                result["drainStartedAt"] = self._drain_started_at
                result["drainElapsedS"] = round(time.time() - self._drain_started_at, 2)
            return result
