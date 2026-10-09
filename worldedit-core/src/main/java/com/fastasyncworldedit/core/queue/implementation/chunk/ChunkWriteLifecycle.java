package com.fastasyncworldedit.core.queue.implementation.chunk;

import com.fastasyncworldedit.core.queue.ChunkWriteScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks downstream settlement across native writes, history finalization and nested completion futures. */
final class ChunkWriteLifecycle implements AutoCloseable {

    private final AutoCloseable admission;
    private final AutoCloseable ownership;
    private final ChunkWriteScope scope;
    private final AtomicBoolean completed = new AtomicBoolean();
    private boolean started;
    private boolean pending;
    private volatile Throwable detachmentFailure;
    private Runnable partialHistory = () -> {};

    private ChunkWriteLifecycle(AutoCloseable admission, AutoCloseable ownership) {
        this.admission = admission;
        this.ownership = ownership;
        scope = admission instanceof ChunkWriteScope write ? write : null;
    }

    static ChunkWriteLifecycle prepare(IChunkGet get, IBatchProcessor processor, IChunk chunk, IChunkSet set,
                                       long timeoutMillis) throws Exception {
        AutoCloseable ownership = Objects.requireNonNull(get.acquireWrite(timeoutMillis));
        try {
            return new ChunkWriteLifecycle(Objects.requireNonNull(processor.prepareChunk(chunk, set)), ownership);
        } catch (Exception | Error failure) {
            try {
                ownership.close();
            } catch (Exception | Error releaseFailure) {
                if (failure != releaseFailure) failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    void beforeWrite() throws Exception {
        if (scope != null) scope.beforeWrite();
        started = true;
    }

    void notStarted() {
        rethrow(complete(ChunkWriteScope.Outcome.NOT_STARTED, null));
    }

    void failed(Throwable failure) {
        if (!completed.compareAndSet(false, true)) return;
        if (detachmentFailure != null) combine(failure, detachmentFailure);
        if (started) {
            try {
                partialHistory.run();
            } catch (Throwable historyFailure) {
                combine(failure, historyFailure);
            }
        }
        notifyScope(started ? ChunkWriteScope.Outcome.UNCERTAIN : ChunkWriteScope.Outcome.NOT_STARTED, failure);
    }

    void onFailure(Runnable partialHistory) {
        this.partialHistory = partialHistory;
    }

    @Override
    public void close() throws Exception {
        try {
            admission.close();
        } catch (Exception | Error failure) {
            if (!pending) throw failure;
            // A scheduled write still owns its settlement, even if detaching the submitting worker fails.
            detachmentFailure = failure;
        }
    }

    void finish(Runnable history, Runnable finalizer) {
        Throwable failure = null;
        try {
            history.run();
        } catch (Throwable exception) {
            failure = exception;
        }
        try {
            finalizer.run();
        } catch (Throwable exception) {
            failure = combine(failure, exception);
        }
        rethrow(complete(ChunkWriteScope.Outcome.APPLIED, failure));
    }

    private Throwable complete(ChunkWriteScope.Outcome outcome, Throwable failure) {
        return completed.compareAndSet(false, true) ? notifyScope(outcome, failure) : failure;
    }

    private Throwable notifyScope(ChunkWriteScope.Outcome outcome, Throwable failure) {
        if (scope != null) {
            try {
                scope.complete(outcome, failure);
            } catch (Throwable exception) {
                failure = combine(failure, exception);
            }
        }
        try {
            ownership.close();
        } catch (Throwable releaseFailure) {
            failure = combine(failure, releaseFailure);
        }
        partialHistory = () -> {};
        return failure;
    }

    private static Throwable combine(Throwable original, Throwable additional) {
        if (original == null) return additional;
        if (original != additional) original.addSuppressed(additional);
        return original;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Chunk completion failed", failure);
    }

    Future<?> observe(Future<?> future) {
        if (future == null) {
            if (!completed.get()) {
                var failure = new IllegalStateException("Chunk write finished without its completion callback");
                failed(failure);
                throw failure;
            }
            rethrow(detachmentFailure);
            return null;
        }
        pending = true;
        return new Future<Future<?>>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return future.isCancelled();
            }

            @Override
            public boolean isDone() {
                return completed.get() && future.isDone();
            }

            @Override
            public Future<?> get() throws InterruptedException, ExecutionException {
                try {
                    return observe((Future<?>) future.get());
                } catch (ExecutionException exception) {
                    failed(exception.getCause());
                    throw exception;
                } catch (CancellationException exception) {
                    failed(exception);
                    throw exception;
                }
            }

            @Override
            public Future<?> get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                try {
                    return observe((Future<?>) future.get(timeout, unit));
                } catch (ExecutionException exception) {
                    failed(exception.getCause());
                    throw exception;
                } catch (CancellationException exception) {
                    failed(exception);
                    throw exception;
                }
            }
        };
    }
}
