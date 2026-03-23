"""
HeapExchange — Zero-copy shared object store for Python functions.

All functions in the same interpreter share the same HeapExchange.
Objects are Python references — no serialization, no copying.
Function A publishes a dict, Function B reads the SAME dict object.

This is the Python equivalent of KubeFn's JVM HeapExchange.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from typing import Any, Optional, TYPE_CHECKING

if TYPE_CHECKING:
    from .heap_guard import HeapGuard
    from .introspection import CaptureEngine


@dataclass
class HeapCapsule:
    """Wrapper around a shared object with metadata."""
    key: str
    value: Any
    value_type: str
    version: int
    publisher_group: str
    publisher_function: str
    published_at: float


class HeapExchange:
    """
    Zero-copy shared object store. Functions publish and consume
    Python objects directly — same reference, same memory address.

    Thread-safe via RLock for concurrent access from async handlers.
    """

    def __init__(self, max_objects: int = 10_000):
        self._store: dict[str, HeapCapsule] = {}
        self._lock = threading.RLock()
        self._version_counter = 0
        self._max_objects = max_objects

        # Metrics
        self.publish_count = 0
        self.get_count = 0
        self.hit_count = 0
        self.miss_count = 0

        # Audit log
        self._audit_log: list[dict] = []
        self._max_audit = 10_000

        # Current context (set per-request)
        self._current_group = threading.local()
        self._current_function = threading.local()
        self._current_request_id = threading.local()

        # Optional integrations (set via setters)
        self._guard: HeapGuard | None = None
        self._capture_engine: CaptureEngine | None = None

    # ── Integration setters ───────────────────────────────────────────

    def set_guard(self, guard: HeapGuard) -> None:
        """Attach a HeapGuard for resource protection."""
        self._guard = guard
        guard.attach(self)

    def set_capture_engine(self, engine: CaptureEngine) -> None:
        """Attach a CaptureEngine for causal introspection."""
        self._capture_engine = engine

    # ── Context management ────────────────────────────────────────────

    def set_context(self, group: str, function: str, request_id: str = "") -> None:
        """Set the current publisher context (called before function execution)."""
        self._current_group.value = group
        self._current_function.value = function
        self._current_request_id.value = request_id

    def clear_context(self) -> None:
        """Clear the current context."""
        self._current_group.value = None
        self._current_function.value = None
        self._current_request_id.value = None

    def _ctx_group(self) -> str:
        return getattr(self._current_group, "value", None) or "unknown"

    def _ctx_function(self) -> str:
        return getattr(self._current_function, "value", None) or "unknown"

    def _ctx_request_id(self) -> str:
        return getattr(self._current_request_id, "value", None) or ""

    # ── Core API ──────────────────────────────────────────────────────

    def publish(self, key: str, value: Any, value_type: str = "object") -> HeapCapsule:
        """
        Publish an object to the shared heap.

        The object is stored by reference — consumers get the SAME object.
        Zero serialization. Zero copying. Same memory address.
        """
        with self._lock:
            # Guard check (if attached)
            if self._guard is not None:
                self._guard.check_publish(key, value, len(self._store))

            if len(self._store) >= self._max_objects and key not in self._store:
                raise RuntimeError(
                    f"HeapExchange at capacity ({self._max_objects} objects). "
                    "Evict stale objects or increase limit."
                )

            self._version_counter += 1
            group = self._ctx_group()
            function = self._ctx_function()

            capsule = HeapCapsule(
                key=key,
                value=value,  # Direct reference — THE object, not a copy
                value_type=value_type,
                version=self._version_counter,
                publisher_group=group,
                publisher_function=function,
                published_at=time.time(),
            )

            self._store[key] = capsule
            self.publish_count += 1

            self._audit("PUBLISH", key, value_type, group, function)

            # Capture engine (if attached)
            if self._capture_engine is not None:
                request_id = self._ctx_request_id()
                if request_id:
                    self._capture_engine.capture_heap_publish(
                        request_id=request_id,
                        function_name=function,
                        group_name=group,
                        heap_key=key,
                    )

            # Set default TTL if guard is attached
            if self._guard is not None:
                self._guard.set_default_ttl(key)

            return capsule

    def get(self, key: str) -> Optional[Any]:
        """
        Get a shared object by key. Returns the SAME object reference.
        Zero copy. Zero serialization. Same memory address.
        """
        self.get_count += 1
        group = self._ctx_group()
        function = self._ctx_function()
        request_id = self._ctx_request_id()

        capsule = self._store.get(key)
        if capsule is None:
            self.miss_count += 1
            self._audit("GET_MISS", key, None, group, function)

            if self._capture_engine is not None and request_id:
                self._capture_engine.capture_heap_get(
                    request_id=request_id,
                    function_name=function,
                    group_name=group,
                    heap_key=key,
                    hit=False,
                )
            return None

        self.hit_count += 1
        self._audit("GET_HIT", key, capsule.value_type, group, function)

        if self._capture_engine is not None and request_id:
            self._capture_engine.capture_heap_get(
                request_id=request_id,
                function_name=function,
                group_name=group,
                heap_key=key,
                hit=True,
            )

        # Zero copy: return the SAME object
        return capsule.value

    def require(self, key: str, type_hint: type | None = None) -> Any:
        """
        Get a shared object by key, raising KeyError if absent.

        This is the Python equivalent of Java's
        HeapReader.require(ctx, key, Type.class).
        """
        value = self.get(key)
        if value is None:
            type_msg = f" (expected type: {type_hint.__name__})" if type_hint else ""
            raise KeyError(
                f"Heap key '{key}' not found{type_msg}. "
                "The producing function may not have run yet."
            )
        if type_hint is not None and not isinstance(value, type_hint):
            raise TypeError(
                f"Heap key '{key}' has type {type(value).__name__}, "
                f"expected {type_hint.__name__}"
            )
        return value

    def get_or_default(self, key: str, default: Any) -> Any:
        """
        Get a shared object by key, returning default if absent.

        This is the Python equivalent of Java's
        HeapReader.getOrDefault(ctx, key, Type.class, default).
        """
        value = self.get(key)
        return value if value is not None else default

    def get_capsule(self, key: str) -> Optional[HeapCapsule]:
        """Get the full capsule with metadata."""
        return self._store.get(key)

    def remove(self, key: str) -> bool:
        """Remove an object from the exchange."""
        with self._lock:
            capsule = self._store.pop(key, None)
            if capsule:
                group = self._ctx_group()
                function = self._ctx_function()
                self._audit("REMOVE", key, None, group, function)
                # Clean up TTL tracking
                if self._guard is not None:
                    self._guard.remove_ttl(key)
                return True
            return False

    def keys(self) -> list[str]:
        """List all keys in the exchange."""
        return list(self._store.keys())

    def contains(self, key: str) -> bool:
        return key in self._store

    def size(self) -> int:
        return len(self._store)

    def metrics(self) -> dict:
        """Get metrics for admin endpoint."""
        hit_rate = (self.hit_count / self.get_count * 100) if self.get_count > 0 else 0
        return {
            "objectCount": len(self._store),
            "publishCount": self.publish_count,
            "getCount": self.get_count,
            "hitCount": self.hit_count,
            "missCount": self.miss_count,
            "hitRate": f"{hit_rate:.2f}%",
            "keys": self.keys(),
        }

    def recent_audit(self, limit: int = 50) -> list[dict]:
        """Get recent audit entries."""
        return self._audit_log[-limit:]

    def _audit(self, action: str, key: str, value_type: Optional[str],
               group: str, function: str) -> None:
        entry = {
            "action": action,
            "key": key,
            "type": value_type,
            "group": group,
            "function": function,
            "timestamp": time.time(),
        }
        self._audit_log.append(entry)
        if len(self._audit_log) > self._max_audit:
            self._audit_log = self._audit_log[-self._max_audit:]
