"""
Heap Guard — HeapExchange protection and resource limits.

Enforces:
  - Maximum object count
  - Maximum per-object size (estimated via sys.getsizeof)
  - TTL-based expiry with background cleanup
  - Memory pressure warnings and rejections
"""

import logging
import sys
import threading
import time
from dataclasses import dataclass, field

logger = logging.getLogger("kubefn.heap_guard")


@dataclass
class HeapGuardConfig:
    """Configuration for heap protection limits."""
    max_objects: int = 10_000
    max_object_size_bytes: int = 50 * 1024 * 1024  # 50 MB
    warn_threshold: float = 0.80  # 80% of max_objects
    reject_threshold: float = 0.95  # 95% of max_objects
    default_ttl_s: float | None = None  # None = no TTL
    cleanup_interval_s: float = 60.0


class HeapGuardLimitExceeded(RuntimeError):
    """Raised when a heap publish would exceed guard limits."""
    pass


class HeapGuard:
    """
    Guards HeapExchange against resource exhaustion.

    - Checks object count limits before publish
    - Estimates object size and rejects oversized payloads
    - Manages TTL entries and runs periodic cleanup
    """

    def __init__(self, config: HeapGuardConfig | None = None):
        self._config = config or HeapGuardConfig()
        self._ttl_entries: dict[str, float] = {}  # key -> expiry timestamp
        self._lock = threading.Lock()
        self._current_count: int = 0

        # Background cleanup thread
        self._cleanup_thread: threading.Thread | None = None
        self._stop_event = threading.Event()
        self._heap_ref = None  # Set via attach()

    def attach(self, heap) -> None:
        """Attach to a HeapExchange instance for TTL eviction."""
        self._heap_ref = heap

    def start_cleanup(self) -> None:
        """Start the background TTL cleanup thread."""
        if self._cleanup_thread is not None:
            return
        self._stop_event.clear()
        self._cleanup_thread = threading.Thread(
            target=self._cleanup_loop,
            name="kubefn-heap-guard-cleanup",
            daemon=True,
        )
        self._cleanup_thread.start()
        logger.info(
            f"Heap guard cleanup started (interval={self._config.cleanup_interval_s}s)"
        )

    def stop_cleanup(self) -> None:
        """Stop the background cleanup thread."""
        self._stop_event.set()
        if self._cleanup_thread is not None:
            self._cleanup_thread.join(timeout=5.0)
            self._cleanup_thread = None

    def check_publish(self, key: str, value: object, current_count: int) -> None:
        """
        Check if a publish is allowed. Raises HeapGuardLimitExceeded if not.

        Args:
            key: The heap key being published.
            value: The object being published.
            current_count: Current number of objects in the heap.
        """
        self._current_count = current_count

        # Check object count
        max_obj = self._config.max_objects
        if current_count >= max_obj:
            raise HeapGuardLimitExceeded(
                f"Heap at capacity: {current_count}/{max_obj} objects. "
                f"Key '{key}' rejected."
            )

        # Reject threshold (95%)
        reject_at = int(max_obj * self._config.reject_threshold)
        if current_count >= reject_at:
            raise HeapGuardLimitExceeded(
                f"Heap under memory pressure: {current_count}/{max_obj} objects "
                f"({current_count/max_obj:.0%}). Key '{key}' rejected at "
                f"{self._config.reject_threshold:.0%} threshold."
            )

        # Warn threshold (80%)
        warn_at = int(max_obj * self._config.warn_threshold)
        if current_count >= warn_at:
            logger.warning(
                f"Heap pressure warning: {current_count}/{max_obj} objects "
                f"({current_count/max_obj:.0%})"
            )

        # Check object size
        try:
            obj_size = sys.getsizeof(value)
        except TypeError:
            obj_size = 0

        if obj_size > self._config.max_object_size_bytes:
            raise HeapGuardLimitExceeded(
                f"Object too large: {obj_size} bytes exceeds limit of "
                f"{self._config.max_object_size_bytes} bytes. Key '{key}' rejected."
            )

    def set_ttl(self, key: str, ttl_s: float) -> None:
        """Set a TTL for a specific heap key."""
        with self._lock:
            self._ttl_entries[key] = time.time() + ttl_s

    def set_default_ttl(self, key: str) -> None:
        """Apply default TTL if configured."""
        if self._config.default_ttl_s is not None:
            self.set_ttl(key, self._config.default_ttl_s)

    def remove_ttl(self, key: str) -> None:
        """Remove TTL tracking for a key."""
        with self._lock:
            self._ttl_entries.pop(key, None)

    def _cleanup_loop(self) -> None:
        """Background loop that evicts expired entries."""
        while not self._stop_event.is_set():
            self._stop_event.wait(timeout=self._config.cleanup_interval_s)
            if self._stop_event.is_set():
                break
            self._evict_expired()

    def _evict_expired(self) -> None:
        """Remove expired entries from the heap."""
        if self._heap_ref is None:
            return

        now = time.time()
        expired_keys: list[str] = []

        with self._lock:
            for key, expiry in list(self._ttl_entries.items()):
                if now >= expiry:
                    expired_keys.append(key)
                    del self._ttl_entries[key]

        for key in expired_keys:
            try:
                self._heap_ref.remove(key)
                logger.debug(f"TTL evicted heap key: {key}")
            except Exception as e:
                logger.error(f"Error evicting key '{key}': {e}")

        if expired_keys:
            logger.info(f"TTL cleanup: evicted {len(expired_keys)} expired objects")

    def status(self) -> dict:
        with self._lock:
            return {
                "maxObjects": self._config.max_objects,
                "maxObjectSizeBytes": self._config.max_object_size_bytes,
                "warnThreshold": self._config.warn_threshold,
                "rejectThreshold": self._config.reject_threshold,
                "ttlEntries": len(self._ttl_entries),
                "currentCount": self._current_count,
                "cleanupRunning": self._cleanup_thread is not None
                    and self._cleanup_thread.is_alive(),
            }
