package com.kubefn.runtime.introspection;

/**
 * A single function invocation step within a {@link RequestTrace}.
 *
 * <p>Built from matched FUNCTION_START/FUNCTION_END event pairs by
 * {@link RequestTraceAssembler}. Represents one unit of work in the
 * causal chain of a request.
 *
 * @param functionName name of the invoked function
 * @param groupName    function group containing the function
 * @param revisionId   code revision active during this invocation
 * @param startNanos   monotonic start timestamp
 * @param endNanos     monotonic end timestamp (0 if function did not complete)
 * @param durationMs   wall-clock duration in milliseconds
 * @param status       completion status: "OK", "ERROR", or "TIMEOUT"
 * @param error        error detail string, null if status is "OK"
 */
public record TraceStep(
        String functionName,
        String groupName,
        String revisionId,
        long startNanos,
        long endNanos,
        double durationMs,
        String status,
        String error
) {

    /** Status constant for successful execution. */
    public static final String STATUS_OK = "OK";
    /** Status constant for execution that ended in error. */
    public static final String STATUS_ERROR = "ERROR";
    /** Status constant for execution that exceeded the timeout. */
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    public TraceStep {
        if (functionName == null) {
            throw new IllegalArgumentException("functionName must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    /**
     * Returns true if this step completed successfully.
     */
    public boolean isOk() {
        return STATUS_OK.equals(status);
    }

    /**
     * Returns true if this step ended in error or timeout.
     */
    public boolean isFailed() {
        return STATUS_ERROR.equals(status) || STATUS_TIMEOUT.equals(status);
    }
}
