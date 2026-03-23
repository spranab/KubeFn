"""
Request Timeout — enforces per-request time limits.

Wraps function execution in a thread pool with a configurable timeout.
If the function exceeds the deadline, a TimeoutError is raised and the
response returns 504 Gateway Timeout.
"""

import concurrent.futures
import logging
from typing import Any, Callable

logger = logging.getLogger("kubefn.timeout")

# Module-level thread pool, shared across all timeout-guarded invocations.
# Sized to match typical HTTP server concurrency.
_executor: concurrent.futures.ThreadPoolExecutor | None = None


def _get_executor() -> concurrent.futures.ThreadPoolExecutor:
    global _executor
    if _executor is None:
        _executor = concurrent.futures.ThreadPoolExecutor(
            max_workers=64,
            thread_name_prefix="kubefn-timeout",
        )
    return _executor


def shutdown_executor() -> None:
    """Shut down the shared executor during graceful shutdown."""
    global _executor
    if _executor is not None:
        _executor.shutdown(wait=False, cancel_futures=True)
        _executor = None


class RequestTimeoutError(TimeoutError):
    """Raised when a function invocation exceeds its timeout."""

    def __init__(self, func_name: str, timeout_ms: int):
        self.func_name = func_name
        self.timeout_ms = timeout_ms
        super().__init__(
            f"Function '{func_name}' exceeded timeout of {timeout_ms}ms"
        )


def execute_with_timeout(
    func: Callable[..., Any],
    args: tuple = (),
    kwargs: dict | None = None,
    timeout_ms: int = 30_000,
    func_name: str = "unknown",
) -> Any:
    """
    Execute a callable with a timeout.

    Args:
        func: The callable to execute.
        args: Positional arguments to pass.
        kwargs: Keyword arguments to pass.
        timeout_ms: Maximum execution time in milliseconds.
        func_name: Function name for error messages.

    Returns:
        The result of func(*args, **kwargs).

    Raises:
        RequestTimeoutError: If the function does not return in time.
    """
    if kwargs is None:
        kwargs = {}

    timeout_s = timeout_ms / 1000.0
    executor = _get_executor()

    future = executor.submit(func, *args, **kwargs)
    try:
        return future.result(timeout=timeout_s)
    except concurrent.futures.TimeoutError:
        future.cancel()
        logger.warning(
            f"Timeout: {func_name} did not complete within {timeout_ms}ms"
        )
        raise RequestTimeoutError(func_name, timeout_ms)
