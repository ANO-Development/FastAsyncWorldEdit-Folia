package com.fastasyncworldedit.core.queue;

/**
 * Downstream admission whose durable outcome follows a chunk write. All methods run off tick threads.
 * The original processing worker closes the scope to detach its thread-local state; completion may
 * happen later on another worker, so closing must not release resources transferred to the write.
 */
public interface ChunkWriteScope extends AutoCloseable {

    /**
     * Finish capturing and persist the intent before native work can be scheduled. This may wait for
     * bounded downstream capacity. The chunk GET call lock is held, but no region callback takes it.
     */
    void beforeWrite() throws Exception;

    /**
     * Settle the write exactly once. APPLIED follows native completion and attempted history finalization,
     * even when history finalization failed. Failure here fails the edit without discarding its history.
     * UNCERTAIN must retain recovery data without claiming that all intended changes were applied.
     *
     * @param outcome observed world-write outcome
     * @param failure preceding processing, native, or finalization failure, or null
     */
    void complete(Outcome outcome, Throwable failure) throws Exception;

    enum Outcome {
        NOT_STARTED,
        APPLIED,
        UNCERTAIN
    }
}
