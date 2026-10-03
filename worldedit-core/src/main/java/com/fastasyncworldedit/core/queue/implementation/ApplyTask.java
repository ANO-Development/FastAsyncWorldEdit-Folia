package com.fastasyncworldedit.core.queue.implementation;

import com.fastasyncworldedit.core.extent.filter.block.ChunkFilterBlock;
import com.fastasyncworldedit.core.queue.Filter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;

class ApplyTask<F extends Filter> extends RecursiveAction implements Runnable {

    private static final int INITIAL_REGION_SHIFT = 5;
    private static final int SHIFT_REDUCTION = 1;

    private final CommonState<F> commonState;
    private final Region region;
    private final ApplyTask<F> before;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;
    // Note: shift == INITIAL_REGION_SHIFT means we are in the root node.
    // compute() relies on that when triggering postProcess
    private final int shift;

    @Override
    public void run() {
        compute();
    }

    private record CommonState<F extends Filter>(
            F originalFilter,
            ParallelQueueExtent parallelQueueExtent,
            ConcurrentMap<Thread, ThreadState<F>> stateCache,
            boolean full
    ) {

    }

    private static final class ThreadState<F extends Filter> {

        private final SingleThreadQueueExtent queue;
        private final F filter;
        private ChunkFilterBlock block;

        private ThreadState(SingleThreadQueueExtent queue, F filter) {
            this.queue = queue;
            this.filter = filter;
        }

    }

    ApplyTask(
            final Region region,
            final F filter,
            final ParallelQueueExtent parallelQueueExtent,
            final boolean full
    ) {
        this.commonState = new CommonState<>(
                filter,
                parallelQueueExtent,
                new ConcurrentHashMap<>(),
                full
        );
        this.region = region.clone();
        this.before = null;
        final BlockVector3 minimumPoint = region.getMinimumPoint();
        this.minChunkX = minimumPoint.x() >> 4;
        this.minChunkZ = minimumPoint.z() >> 4;
        final BlockVector3 maximumPoint = region.getMaximumPoint();
        this.maxChunkX = maximumPoint.x() >> 4;
        this.maxChunkZ = maximumPoint.z() >> 4;
        this.shift = INITIAL_REGION_SHIFT;

    }

    private ApplyTask(
            final CommonState<F> commonState,
            final Region region,
            final ApplyTask<F> before,
            final int minChunkX,
            final int maxChunkX,
            final int minChunkZ,
            final int maxChunkZ,
            final int higherShift
    ) {
        this.commonState = commonState;
        this.region = region.clone();
        this.minChunkX = minChunkX;
        this.maxChunkX = maxChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkZ = maxChunkZ;
        this.before = before;
        this.shift = Math.max(0, higherShift - SHIFT_REDUCTION);
    }

    @Override
    protected void compute() {
        ApplyTask<F> subtask = null;
        try {
            if (this.commonState.parallelQueueExtent.hasFailed()) return;
            if (this.minChunkX != this.maxChunkX || this.minChunkZ != this.maxChunkZ) {
                int minRegionX = this.minChunkX >> this.shift;
                int minRegionZ = this.minChunkZ >> this.shift;
                int maxRegionX = this.maxChunkX >> this.shift;
                int maxRegionZ = this.maxChunkZ >> this.shift;
                for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                    for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                        if (this.commonState.parallelQueueExtent.hasFailed()) return;
                        if (shouldProcessDirectly()) {
                            processRegion(regionX, regionZ, this.shift);
                            continue;
                        }
                        if (this.shift == 0 && !this.region.containsChunk(regionX, regionZ)) {
                            continue;
                        }
                        subtask = new ApplyTask<>(
                                this.commonState,
                                this.region,
                                subtask,
                                regionX << this.shift,
                                ((regionX + 1) << this.shift) - 1,
                                regionZ << this.shift,
                                ((regionZ + 1) << this.shift) - 1,
                                this.shift
                        );
                        subtask.fork();
                    }
                }
            } else {
                processChunk(this.minChunkX, this.minChunkZ);
            }
        } catch (Throwable failure) {
            this.commonState.parallelQueueExtent.recordFailure(failure);
        } finally {
            while (subtask != null) {
                try {
                    if (subtask.tryUnfork()) {
                        subtask.invoke();
                    } else {
                        subtask.join();
                    }
                } catch (Throwable failure) {
                    this.commonState.parallelQueueExtent.recordFailure(failure);
                }
                subtask = subtask.before;
            }
            if (this.shift == INITIAL_REGION_SHIFT) {
                onCompletion();
            }
        }
    }

    private boolean shouldProcessDirectly() {
        return ForkJoinTask.getSurplusQueuedTaskCount() > Math.max(3, 1 << this.shift);
    }

    private void processRegion(int regionX, int regionZ, int shift) {
        final ThreadState<F> state = getState();
        this.commonState.parallelQueueExtent.enter(state.queue);
        try {
            for (int chunkX = regionX << shift; chunkX <= ((regionX  + 1) << shift) - 1; chunkX++) {
                for (int chunkZ = regionZ << shift; chunkZ <= ((regionZ  + 1) << shift) - 1; chunkZ++) {
                    if (this.commonState.parallelQueueExtent.hasFailed()) return;
                    if (!this.region.containsChunk(chunkX, chunkZ)) {
                        continue; // chunks not intersecting with the region must not be processed
                    }
                    applyChunk(chunkX, chunkZ, state);
                }
            }
        } finally {
            this.commonState.parallelQueueExtent.exit();
        }

    }

    @SuppressWarnings("unchecked")
    private ThreadState<F> getState() {
        return this.commonState.stateCache.computeIfAbsent(
                Thread.currentThread(),
                __ -> new ThreadState<>(
                        (SingleThreadQueueExtent) this.commonState.parallelQueueExtent.getNewQueue(),
                        (F) this.commonState.originalFilter.fork()
                )
        );
    }

    private void processChunk(int chunkX, int chunkZ) {
        final ThreadState<F> state = getState();
        this.commonState.parallelQueueExtent.enter(state.queue);
        try {
            applyChunk(chunkX, chunkZ, state);
        } finally {
            this.commonState.parallelQueueExtent.exit();
        }
    }

    private void applyChunk(int chunkX, int chunkZ, ThreadState<F> state) {
        try {
            state.block = state.queue.apply(
                    state.block,
                    state.filter,
                    this.region,
                    chunkX,
                    chunkZ,
                    this.commonState.full
            );
        } catch (Throwable t) {
            // Keep joining sibling tasks; the shared failure stops their unstarted chunks and flush reports it.
            state.queue.recordFailure(t);
        }
    }

    private void onCompletion() {
        Throwable failure = null;
        for (ForkJoinTask<?> task : flushQueues()) {
            try {
                if (task.tryUnfork()) {
                    task.invoke();
                } else {
                    task.join();
                }
            } catch (RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        ((SingleThreadQueueExtent) this.commonState.parallelQueueExtent.getExtent()).checkFailure();
    }

    private ForkJoinTask<?>[] flushQueues() {
        final Collection<ThreadState<F>> values = this.commonState.stateCache.values();
        ForkJoinTask<?>[] tasks = new ForkJoinTask[values.size()];
        int i = values.size() - 1;
        for (final ThreadState<F> value : values) {
            tasks[i] = ForkJoinTask.adapt(value.queue::flush).fork();
            i--;
        }
        return tasks;
    }

}
