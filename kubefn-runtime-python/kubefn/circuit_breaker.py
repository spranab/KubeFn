"""
Circuit Breaker — per-function fault isolation.

Prevents cascading failures by tracking error rates per function
and temporarily disabling functions that exceed the failure threshold.

States:
  CLOSED    → normal operation, requests pass through
  OPEN      → function disabled, requests fail-fast
  HALF_OPEN → trial period, one request allowed to test recovery
"""

import enum
import logging
import threading
import time
from dataclasses import dataclass, field

logger = logging.getLogger("kubefn.circuit_breaker")


class CircuitState(enum.Enum):
    CLOSED = "CLOSED"
    OPEN = "OPEN"
    HALF_OPEN = "HALF_OPEN"


@dataclass
class CircuitBreaker:
    """Per-function circuit breaker with configurable thresholds."""

    function_name: str
    failure_threshold: int = 5
    reset_timeout_s: float = 30.0
    half_open_max_calls: int = 1

    # Internal state
    state: CircuitState = field(default=CircuitState.CLOSED)
    failure_count: int = field(default=0)
    success_count: int = field(default=0)
    last_failure_time: float = field(default=0.0)
    last_state_change: float = field(default_factory=time.time)
    total_rejections: int = field(default=0)
    half_open_calls: int = field(default=0)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def is_allowed(self) -> bool:
        """Check if a request is allowed through this breaker."""
        with self._lock:
            match self.state:
                case CircuitState.CLOSED:
                    return True
                case CircuitState.OPEN:
                    if time.time() - self.last_failure_time >= self.reset_timeout_s:
                        self._transition(CircuitState.HALF_OPEN)
                        self.half_open_calls = 1
                        return True
                    self.total_rejections += 1
                    return False
                case CircuitState.HALF_OPEN:
                    if self.half_open_calls < self.half_open_max_calls:
                        self.half_open_calls += 1
                        return True
                    self.total_rejections += 1
                    return False

    def record_success(self) -> None:
        """Record a successful invocation."""
        with self._lock:
            match self.state:
                case CircuitState.HALF_OPEN:
                    self.success_count += 1
                    self._transition(CircuitState.CLOSED)
                    self.failure_count = 0
                case CircuitState.CLOSED:
                    self.success_count += 1
                    self.failure_count = 0

    def record_failure(self) -> None:
        """Record a failed invocation."""
        with self._lock:
            self.failure_count += 1
            self.last_failure_time = time.time()

            match self.state:
                case CircuitState.HALF_OPEN:
                    self._transition(CircuitState.OPEN)
                case CircuitState.CLOSED:
                    if self.failure_count >= self.failure_threshold:
                        self._transition(CircuitState.OPEN)
                        logger.warning(
                            f"Circuit OPEN for {self.function_name}: "
                            f"{self.failure_count} consecutive failures"
                        )

    def _transition(self, new_state: CircuitState) -> None:
        old_state = self.state
        self.state = new_state
        self.last_state_change = time.time()
        self.half_open_calls = 0
        logger.info(
            f"Circuit breaker [{self.function_name}]: "
            f"{old_state.value} -> {new_state.value}"
        )

    def status(self) -> dict:
        with self._lock:
            return {
                "function": self.function_name,
                "state": self.state.value,
                "failureCount": self.failure_count,
                "successCount": self.success_count,
                "totalRejections": self.total_rejections,
                "lastFailureTime": self.last_failure_time,
                "lastStateChange": self.last_state_change,
                "failureThreshold": self.failure_threshold,
                "resetTimeoutS": self.reset_timeout_s,
            }


class CircuitBreakerRegistry:
    """
    Manages per-function circuit breakers. Automatically creates
    a breaker on first access for any function name.
    """

    def __init__(
        self,
        failure_threshold: int = 5,
        reset_timeout_s: float = 30.0,
    ):
        self._breakers: dict[str, CircuitBreaker] = {}
        self._lock = threading.Lock()
        self._failure_threshold = failure_threshold
        self._reset_timeout_s = reset_timeout_s

    def _get_or_create(self, func_name: str) -> CircuitBreaker:
        breaker = self._breakers.get(func_name)
        if breaker is not None:
            return breaker
        with self._lock:
            # Double-check after acquiring lock
            if func_name not in self._breakers:
                self._breakers[func_name] = CircuitBreaker(
                    function_name=func_name,
                    failure_threshold=self._failure_threshold,
                    reset_timeout_s=self._reset_timeout_s,
                )
            return self._breakers[func_name]

    def is_allowed(self, func_name: str) -> bool:
        return self._get_or_create(func_name).is_allowed()

    def record_success(self, func_name: str) -> None:
        self._get_or_create(func_name).record_success()

    def record_failure(self, func_name: str) -> None:
        self._get_or_create(func_name).record_failure()

    def get_status(self) -> dict:
        """Return status of all known breakers."""
        with self._lock:
            return {
                name: breaker.status()
                for name, breaker in sorted(self._breakers.items())
            }
