package com.fastasyncworldedit.bukkit.adapter;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.util.MemUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Isolated
class ChunkWriteFailureTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void ownedLoadDoesNotQueueAContinuationBehindOtherOwnershipWaiters() throws Exception {
        Settings.QUEUE previous = Settings.settings().QUEUE;
        Settings.settings().QUEUE = new Settings.QUEUE();
        Settings.settings().QUEUE.ASYNC_CHUNK_LOAD_WRITE = false;
        var blocks = mock(AbstractBukkitGetBlocks.class,
                withSettings().useConstructor(new Object(), 0, 0, 0, 16).defaultAnswer(CALLS_REAL_METHODS));
        var load = new CompletableFuture<>();
        var loading = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> { loading.countDown(); return load; }).when(blocks).ensureLoaded(any());
        var owner = mock(IQueueExtent.class);
        try (var fawe = mockStatic(Fawe.class);
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
                AutoCloseable ownership = blocks.acquireWrite(0)) {
            fawe.when(Fawe::isMainThread).thenReturn(false);
            var result = executor.submit(() -> {
                blocks.lockCall();
                try {
                    return blocks.call(owner, mock(IChunkSet.class), () -> {});
                } finally {
                    blocks.unlockCall();
                }
            });
            try {
                assertTrue(loading.await(2, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
            } finally {
                load.complete(new Object());
            }
            result.get(2, TimeUnit.SECONDS);
            verify(owner, never()).submitTaskUnchecked(any());
            verify(blocks).internalCall(any(), any(), anyInt(), any(), any());
        } finally {
            Settings.settings().QUEUE = previous;
        }
    }

    @Test
    void writeOwnershipSurvivesWorkerHandoffAndReleasesOnce() throws Exception {
        var blocks = mock(AbstractBukkitGetBlocks.class,
                withSettings().useConstructor(new Object(), 0, 0, 0, 16).defaultAnswer(CALLS_REAL_METHODS));
        try (var fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(false);
            AutoCloseable first = blocks.acquireWrite(0);
            assertThrows(IllegalStateException.class, () -> blocks.acquireWrite(0));
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                executor.submit(() -> { first.close(); return null; }).get(2, TimeUnit.SECONDS);
            }
            try (AutoCloseable second = blocks.acquireWrite(0)) {
                first.close();
                assertThrows(IllegalStateException.class, () -> blocks.acquireWrite(0));
            }
            try (AutoCloseable third = blocks.acquireWrite(0)) {
                assertNotNull(third);
            }
        }
    }

    @Test
    void regionThreadCannotAcquireWriteOwnership() {
        var blocks = mock(AbstractBukkitGetBlocks.class,
                withSettings().useConstructor(new Object(), 0, 0, 0, 16).defaultAnswer(CALLS_REAL_METHODS));
        try (var fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> blocks.acquireWrite(0));
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void delayedChunkLoadIsIncludedInCompletionAndReportsFailure() throws Exception {
        Settings.QUEUE previous = Settings.settings().QUEUE;
        Settings.settings().QUEUE = new Settings.QUEUE();
        Settings.settings().QUEUE.ASYNC_CHUNK_LOAD_WRITE = false;
        var blocks = mock(AbstractBukkitGetBlocks.class,
                withSettings().useConstructor(new Object(), 0, 0, 0, 16).defaultAnswer(CALLS_REAL_METHODS));
        var load = new CompletableFuture<>();
        doReturn(load).when(blocks).ensureLoaded(any());
        var failure = new IllegalStateException("Chunk could not be loaded");
        blocks.lockCall();
        try (var memory = mockStatic(MemUtil.class)) {
            memory.when(MemUtil::shouldBeginSlow).thenReturn(false);
            Future<?> completion = blocks.call(mock(IQueueExtent.class), mock(IChunkSet.class), () -> {});
            assertFalse(completion.isDone());
            load.completeExceptionally(failure);
            assertSame(failure, assertThrows(ExecutionException.class, () -> completion.get(1, TimeUnit.SECONDS)).getCause());
            verify(blocks, never()).internalCall(any(), any(), anyInt(), any(), any());
        } finally {
            blocks.unlockCall();
            Settings.settings().QUEUE = previous;
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void ownerThreadNeverRunsHistoryFinalizerInline() throws Exception {
        var blocks = mock(AbstractBukkitGetBlocks.class,
                withSettings().useConstructor(new Object(), 0, 0, 0, 16).defaultAnswer(CALLS_REAL_METHODS));
        doReturn(true).when(blocks).isOwnerThread();
        var faweInstance = mock(Fawe.class);
        var handler = mock(QueueHandler.class);
        when(faweInstance.getQueueHandler()).thenReturn(handler);
        var finalizer = mock(Runnable.class);
        var completion = new CompletableFuture<>();
        doReturn(completion).when(handler).async(finalizer, null);
        try (var fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::instance).thenReturn(faweInstance);
            assertSame(completion, blocks.handleCallFinalizer(java.util.List.of(), null, finalizer));
            verify(finalizer, never()).run();
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void nativeWriteFailureMustReachTheEditInsteadOfReturningSuccess() throws Exception {
        Settings.QUEUE previous = Settings.settings().QUEUE;
        Settings.settings().QUEUE = new Settings.QUEUE();
        var blocks = mock(AbstractBukkitGetBlocks.class,
                withSettings().useConstructor(new Object(), 0, 0, 0, 16).defaultAnswer(CALLS_REAL_METHODS));
        var failure = new IllegalStateException("Injected native write failure");
        doReturn(CompletableFuture.completedFuture(new Object())).when(blocks).ensureLoaded(any());
        doThrow(failure).when(blocks).internalCall(any(), any(), anyInt(), any(), any());
        blocks.lockCall();
        try {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> blocks.call(mock(IQueueExtent.class), mock(IChunkSet.class), () -> {})));
        } finally {
            blocks.unlockCall();
            Settings.settings().QUEUE = previous;
        }
    }
}
