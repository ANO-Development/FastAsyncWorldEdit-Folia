package com.fastasyncworldedit.core.extent.processor;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.FaweCache;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.internal.exception.FaweException;
import com.fastasyncworldedit.core.queue.Filter;
import com.fastasyncworldedit.core.queue.ChunkWriteScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.util.MultiFuture;
import com.fastasyncworldedit.core.util.StringMan;
import com.google.common.cache.LoadingCache;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Supplier;

public class MultiBatchProcessor implements IBatchProcessor {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    private final LoadingCache<Class<?>, Map<Long, Filter>> classToThreadIdToFilter =
            FaweCache.INSTANCE.createCache((Supplier<Map<Long, Filter>>) ConcurrentHashMap::new);
    // Array for lazy avoidance of concurrent modification exceptions and needless overcomplication of code (synchronisation is
    // not very important)
    private boolean[] faweExceptionReasonsUsed = new boolean[FaweException.Type.values().length];
    private IBatchProcessor[] processors;
    private int lastException = Integer.MIN_VALUE;
    private int exceptionCount = 0;

    public MultiBatchProcessor(IBatchProcessor... processors) {
        this.processors = processors;
    }

    public static IBatchProcessor of(IBatchProcessor... processors) {
        ArrayList<IBatchProcessor> list = new ArrayList<>();
        for (IBatchProcessor processor : processors) {
            if (processor instanceof MultiBatchProcessor) {
                list.addAll(Arrays.asList(((MultiBatchProcessor) processor).processors));
            } else if (!(processor instanceof EmptyBatchProcessor)) {
                list.add(processor);
            }
        }
        return switch (list.size()) {
            case 0 -> EmptyBatchProcessor.getInstance();
            case 1 -> list.get(0);
            default -> new MultiBatchProcessor(list.toArray(new IBatchProcessor[0]));
        };
    }

    public void addBatchProcessor(IBatchProcessor processor) {
        List<IBatchProcessor> processors = new ArrayList<>(Arrays.asList(this.processors));
        processors.add(processor);
        this.processors = processors.toArray(new IBatchProcessor[0]);
    }

    public List<IBatchProcessor> getBatchProcessors() {
        return Arrays.asList(this.processors);
    }

    public void removeBatchProcessor(IBatchProcessor processor) {
        List<IBatchProcessor> processors = new ArrayList<>(Arrays.asList(this.processors));
        processors.remove(processor);
        this.processors = processors.toArray(new IBatchProcessor[0]);
    }

    @Override
    public AutoCloseable prepareChunk(IChunk chunk, IChunkSet set) throws Exception {
        List<AutoCloseable> scopes = new ArrayList<>(processors.length);
        try {
            for (IBatchProcessor processor : processors) {
                scopes.add(java.util.Objects.requireNonNull(processor.prepareChunk(chunk, set)));
            }
        } catch (Exception | Error failure) {
            try {
                completeScopes(scopes, ChunkWriteScope.Outcome.NOT_STARTED, failure);
            } catch (Exception | Error completionFailure) {
                if (failure != completionFailure) failure.addSuppressed(completionFailure);
            }
            try {
                closeScopes(scopes);
            } catch (Exception | Error cleanupFailure) {
                if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return new ChunkWriteScope() {
            @Override
            public void beforeWrite() throws Exception {
                for (AutoCloseable scope : scopes) {
                    if (scope instanceof ChunkWriteScope write) {
                        write.beforeWrite();
                    }
                }
            }

            @Override
            public void complete(Outcome outcome, Throwable failure) throws Exception {
                completeScopes(scopes, outcome, failure);
            }

            @Override
            public void close() throws Exception {
                closeScopes(scopes);
            }
        };
    }

    private static void completeScopes(List<AutoCloseable> scopes, ChunkWriteScope.Outcome outcome, Throwable failure) throws Exception {
        Throwable completionFailure = null;
        for (AutoCloseable scope : scopes) {
            if (scope instanceof ChunkWriteScope write) {
                try {
                    write.complete(outcome, failure);
                } catch (Exception | Error exception) {
                    if (completionFailure == null) completionFailure = exception;
                    else if (completionFailure != exception) completionFailure.addSuppressed(exception);
                }
            }
        }
        if (completionFailure instanceof Exception exception) throw exception;
        if (completionFailure instanceof Error error) throw error;
    }

    private static void closeScopes(List<AutoCloseable> scopes) throws Exception {
        Throwable failure = null;
        for (int index = scopes.size() - 1; index >= 0; index--) {
            try {
                scopes.get(index).close();
            } catch (Exception | Error exception) {
                if (failure == null) failure = exception;
                else if (failure != exception) failure.addSuppressed(exception);
            }
        }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    @Override
    public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
        Map<ProcessorScope, List<IBatchProcessor>> ordered = new EnumMap<>(ProcessorScope.class);
        IChunkSet chunkSet = set;
        for (IBatchProcessor processor : processors) {
            if (processor.getScope() != ProcessorScope.ADDING_BLOCKS) {
                ordered.computeIfAbsent(processor.getScope(), k -> new ArrayList<>())
                        .add(processor);
                continue;
            }
            chunkSet = processSet(processor, chunk, get, chunkSet);
        }
        if (!ordered.isEmpty()) {
            for (List<IBatchProcessor> processors : ordered.values()) {
                for (IBatchProcessor processor : processors) {
                    chunkSet = processSet(processor, chunk, get, chunkSet);
                    if (chunkSet == null) {
                        return null;
                    }
                }
            }
        }
        return chunkSet;
    }

    @Nullable
    private IChunkSet processSet(IBatchProcessor processor, IChunk chunk, IChunkGet get, IChunkSet chunkSet) {
        if (processor instanceof Filter) {
            chunkSet = ((IBatchProcessor) classToThreadIdToFilter.getUnchecked(processor.getClass())
                    .computeIfAbsent(Thread.currentThread().getId(), k -> ((Filter) processor).fork())).processSet(
                    chunk,
                    get,
                    chunkSet
            );
        } else {
            chunkSet = processor.processSet(chunk, get, chunkSet);
        }
        return chunkSet;
    }

    @Override
    public Future<?> postProcessSet(IChunk chunk, IChunkGet get, IChunkSet set) {
        List<Future<?>> futures = new ArrayList<>();
        for (IBatchProcessor processor : processors) {
            try {
                // We do NOT want to edit blocks in post processing
                if (processor.getScope() != ProcessorScope.READING_BLOCKS) {
                    continue;
                }
                futures.add(processor.postProcessSet(chunk, get, set));
            } catch (Throwable e) {
                if (e instanceof FaweException) {
                    Fawe.handleFaweException(faweExceptionReasonsUsed, (FaweException) e, LOGGER);
                } else if (e.getCause() instanceof FaweException) {
                    Fawe.handleFaweException(faweExceptionReasonsUsed, (FaweException) e.getCause(), LOGGER);
                } else {
                    String message = e.getMessage();
                    int hash = message != null ? message.hashCode() : 0;
                    if (lastException != hash) {
                        lastException = hash;
                        exceptionCount = 0;
                        LOGGER.catching(e);
                    } else if (exceptionCount < Settings.settings().QUEUE.PARALLEL_THREADS) {
                        exceptionCount++;
                        LOGGER.warn(message);
                    }
                }
            }
        }
        return new MultiFuture(futures);
    }

    @Override
    public void postProcess(IChunk chunk, IChunkGet get, IChunkSet set) {
        Throwable failure = null;
        for (IBatchProcessor processor : processors) {
            try {
                // We do NOT want to edit blocks in post processing
                if (processor.getScope() != ProcessorScope.READING_BLOCKS) {
                    continue;
                }
                processor.postProcess(chunk, get, set);
            } catch (Throwable e) {
                if (failure == null) failure = e;
                else if (failure != e) failure.addSuppressed(e);
            }
        }
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Chunk post-processing failed", failure);
    }

    @Override
    public boolean processGet(int chunkX, int chunkZ) {
        for (IBatchProcessor processor : this.processors) {
            if (!processor.processGet(chunkX, chunkZ)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public IChunkGet processGet(IChunkGet get) {
        for (IBatchProcessor processor : this.processors) {
            get = processor.processGet(get);
        }
        return get;
    }

    @Override
    public Extent construct(Extent child) {
        for (IBatchProcessor processor : processors) {
            child = processor.construct(child);
        }
        return child;
    }

    @Override
    public <T extends IBatchProcessor> IBatchProcessor remove(Class<T> clazz) {
        ArrayList<IBatchProcessor> list = new ArrayList<>(Arrays.asList(this.processors));
        list.removeIf(clazz::isInstance);
        return of(list.toArray(new IBatchProcessor[0]));
    }

    @Override
    public IBatchProcessor join(IBatchProcessor other) {
        if (other instanceof MultiBatchProcessor) {
            for (IBatchProcessor processor : ((MultiBatchProcessor) other).processors) {
                addBatchProcessor(processor);
            }
        } else {
            addBatchProcessor(other);
        }
        return this;
    }

    @Override
    public IBatchProcessor joinPost(IBatchProcessor other) {
        if (other instanceof MultiBatchProcessor) {
            for (IBatchProcessor processor : ((MultiBatchProcessor) other).processors) {
                addBatchProcessor(processor);
            }
        } else {
            addBatchProcessor(other);
        }
        return this;
    }

    @Override
    public void flush() {
        for (IBatchProcessor processor : this.processors) {
            processor.flush();
        }
    }

    @Override
    public String toString() {
        return super.toString() + "{" + StringMan.join(processors, ",") + "}";
    }

    @Override
    public ProcessorScope getScope() {
        int scope = 0;
        for (IBatchProcessor processor : processors) {
            scope = Math.max(scope, processor.getScope().intValue());
        }
        return ProcessorScope.valueOf(scope);
    }

    /**
     * Sets the cached boolean array of length {@code FaweException.Type.values().length} that determines if a thrown
     * {@link FaweException} of type {@link FaweException.Type} should be output to console, rethrown to attempt to be visible
     * to the player, etc. Allows the same array to be used as widely as possible across the edit to avoid spam to console.
     *
     * @param faweExceptionReasonsUsed boolean array that should be cached where this method is called from of length {@code
     *                                 FaweException.Type.values().length}
     */
    public void setFaweExceptionArray(final boolean[] faweExceptionReasonsUsed) {
        this.faweExceptionReasonsUsed = faweExceptionReasonsUsed;
    }

}
