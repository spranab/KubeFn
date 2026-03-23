"""
Causal State Introspection — request tracing and event capture.

Simplified Python equivalent of the JVM CausalCaptureEngine.
Records structured events (request lifecycle, function invocations,
heap mutations) into a bounded ring buffer for diagnostics.
"""

import enum
import threading
import time
import uuid
from dataclasses import dataclass, field


class EventType(enum.Enum):
    REQUEST_START = "REQUEST_START"
    REQUEST_END = "REQUEST_END"
    FUNCTION_START = "FUNCTION_START"
    FUNCTION_END = "FUNCTION_END"
    HEAP_PUBLISH = "HEAP_PUBLISH"
    HEAP_GET_HIT = "HEAP_GET_HIT"
    HEAP_GET_MISS = "HEAP_GET_MISS"
    ERROR = "ERROR"


@dataclass(frozen=True, slots=True)
class CausalEvent:
    """An immutable event in the causal trace."""
    event_id: str
    timestamp: float
    request_id: str
    event_type: EventType
    function_name: str
    group_name: str
    heap_key: str | None = None
    duration_ns: int | None = None
    detail: str | None = None


class EventRing:
    """
    Bounded ring buffer for causal events.
    Thread-safe. Oldest events are evicted when capacity is reached.
    """

    def __init__(self, capacity: int = 50_000):
        self._capacity = capacity
        self._events: list[CausalEvent] = []
        self._lock = threading.Lock()

    def append(self, event: CausalEvent) -> None:
        with self._lock:
            self._events.append(event)
            if len(self._events) > self._capacity:
                # Drop oldest 10% to avoid frequent trimming
                drop_count = self._capacity // 10
                self._events = self._events[drop_count:]

    def get_by_request_id(self, request_id: str) -> list[CausalEvent]:
        with self._lock:
            return [e for e in self._events if e.request_id == request_id]

    def recent(self, limit: int = 100) -> list[CausalEvent]:
        with self._lock:
            return list(self._events[-limit:])

    def size(self) -> int:
        return len(self._events)

    def clear(self) -> None:
        with self._lock:
            self._events.clear()


class TraceAssembler:
    """Builds a structured trace dict from a list of causal events."""

    @staticmethod
    def assemble(events: list[CausalEvent]) -> dict:
        if not events:
            return {"error": "no events found"}

        request_id = events[0].request_id
        steps: list[dict] = []
        heap_mutations: list[dict] = []
        errors: list[dict] = []
        total_duration_ns: int | None = None

        for event in events:
            match event.event_type:
                case EventType.REQUEST_START:
                    pass  # captured in top-level metadata
                case EventType.REQUEST_END:
                    total_duration_ns = event.duration_ns
                case EventType.FUNCTION_START:
                    steps.append({
                        "type": "function_start",
                        "function": event.function_name,
                        "group": event.group_name,
                        "timestamp": event.timestamp,
                    })
                case EventType.FUNCTION_END:
                    steps.append({
                        "type": "function_end",
                        "function": event.function_name,
                        "group": event.group_name,
                        "durationNs": event.duration_ns,
                        "timestamp": event.timestamp,
                    })
                case EventType.HEAP_PUBLISH:
                    heap_mutations.append({
                        "action": "publish",
                        "key": event.heap_key,
                        "function": event.function_name,
                        "group": event.group_name,
                        "timestamp": event.timestamp,
                    })
                case EventType.HEAP_GET_HIT:
                    heap_mutations.append({
                        "action": "get_hit",
                        "key": event.heap_key,
                        "function": event.function_name,
                        "timestamp": event.timestamp,
                    })
                case EventType.HEAP_GET_MISS:
                    heap_mutations.append({
                        "action": "get_miss",
                        "key": event.heap_key,
                        "function": event.function_name,
                        "timestamp": event.timestamp,
                    })
                case EventType.ERROR:
                    errors.append({
                        "function": event.function_name,
                        "detail": event.detail,
                        "timestamp": event.timestamp,
                    })

        trace: dict = {
            "requestId": request_id,
            "steps": steps,
            "heapMutations": heap_mutations,
            "stepCount": len(steps),
        }
        if total_duration_ns is not None:
            trace["totalDurationMs"] = round(total_duration_ns / 1_000_000, 3)
        if errors:
            trace["errors"] = errors

        return trace


class CaptureEngine:
    """
    Singleton-style capture engine for causal introspection.
    Records events and provides query methods for the admin API.
    """

    def __init__(self, capacity: int = 50_000):
        self._ring = EventRing(capacity=capacity)
        # Track recent request IDs for quick lookup
        self._recent_request_ids: list[str] = []
        self._recent_lock = threading.Lock()
        self._max_recent = 500

    def _emit(self, event: CausalEvent) -> None:
        self._ring.append(event)

    def _track_request_id(self, request_id: str) -> None:
        with self._recent_lock:
            self._recent_request_ids.append(request_id)
            if len(self._recent_request_ids) > self._max_recent:
                self._recent_request_ids = self._recent_request_ids[
                    -self._max_recent :
                ]

    @staticmethod
    def _new_event_id() -> str:
        return uuid.uuid4().hex[:16]

    # ── Capture methods ───────────────────────────────────────────────

    def capture_request_start(
        self, request_id: str, function_name: str, group_name: str
    ) -> None:
        self._track_request_id(request_id)
        self._emit(CausalEvent(
            event_id=self._new_event_id(),
            timestamp=time.time(),
            request_id=request_id,
            event_type=EventType.REQUEST_START,
            function_name=function_name,
            group_name=group_name,
        ))

    def capture_request_end(
        self,
        request_id: str,
        function_name: str,
        group_name: str,
        duration_ns: int,
    ) -> None:
        self._emit(CausalEvent(
            event_id=self._new_event_id(),
            timestamp=time.time(),
            request_id=request_id,
            event_type=EventType.REQUEST_END,
            function_name=function_name,
            group_name=group_name,
            duration_ns=duration_ns,
        ))

    def capture_function_start(
        self, request_id: str, function_name: str, group_name: str
    ) -> None:
        self._emit(CausalEvent(
            event_id=self._new_event_id(),
            timestamp=time.time(),
            request_id=request_id,
            event_type=EventType.FUNCTION_START,
            function_name=function_name,
            group_name=group_name,
        ))

    def capture_function_end(
        self,
        request_id: str,
        function_name: str,
        group_name: str,
        duration_ns: int,
    ) -> None:
        self._emit(CausalEvent(
            event_id=self._new_event_id(),
            timestamp=time.time(),
            request_id=request_id,
            event_type=EventType.FUNCTION_END,
            function_name=function_name,
            group_name=group_name,
            duration_ns=duration_ns,
        ))

    def capture_heap_publish(
        self,
        request_id: str,
        function_name: str,
        group_name: str,
        heap_key: str,
    ) -> None:
        self._emit(CausalEvent(
            event_id=self._new_event_id(),
            timestamp=time.time(),
            request_id=request_id,
            event_type=EventType.HEAP_PUBLISH,
            function_name=function_name,
            group_name=group_name,
            heap_key=heap_key,
        ))

    def capture_heap_get(
        self,
        request_id: str,
        function_name: str,
        group_name: str,
        heap_key: str,
        hit: bool,
    ) -> None:
        self._emit(CausalEvent(
            event_id=self._new_event_id(),
            timestamp=time.time(),
            request_id=request_id,
            event_type=EventType.HEAP_GET_HIT if hit else EventType.HEAP_GET_MISS,
            function_name=function_name,
            group_name=group_name,
            heap_key=heap_key,
        ))

    def capture_error(
        self,
        request_id: str,
        function_name: str,
        group_name: str,
        detail: str,
    ) -> None:
        self._emit(CausalEvent(
            event_id=self._new_event_id(),
            timestamp=time.time(),
            request_id=request_id,
            event_type=EventType.ERROR,
            function_name=function_name,
            group_name=group_name,
            detail=detail,
        ))

    # ── Query methods ─────────────────────────────────────────────────

    def get_trace(self, request_id: str) -> dict:
        events = self._ring.get_by_request_id(request_id)
        return TraceAssembler.assemble(events)

    def recent_traces(self, limit: int = 20) -> list[dict]:
        with self._recent_lock:
            request_ids = list(reversed(self._recent_request_ids[-limit:]))

        traces = []
        for rid in request_ids:
            events = self._ring.get_by_request_id(rid)
            if events:
                traces.append(TraceAssembler.assemble(events))
        return traces

    def event_count(self) -> int:
        return self._ring.size()
