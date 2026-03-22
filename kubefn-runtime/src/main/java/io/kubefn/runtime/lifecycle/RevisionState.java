package io.kubefn.runtime.lifecycle;

/**
 * Revision lifecycle states for function groups.
 * Follows the formal state machine:
 * LOADING → WARMING → ACTIVE → DRAINING → UNLOADING → UNLOADED
 *                                                    → FAILED
 */
public enum RevisionState {
    /** Classloader created, classes being scanned and loaded */
    LOADING,

    /** Functions instantiated, lifecycle init hooks running */
    WARMING,

    /** Serving traffic */
    ACTIVE,

    /** No new requests accepted, in-flight completing */
    DRAINING,

    /** All requests complete, classloader being closed */
    UNLOADING,

    /** Classloader closed, resources freed */
    UNLOADED,

    /** Loading/warming failed */
    FAILED
}
