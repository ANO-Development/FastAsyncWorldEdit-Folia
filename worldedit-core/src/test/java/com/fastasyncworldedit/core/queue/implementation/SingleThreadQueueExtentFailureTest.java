package com.fastasyncworldedit.core.queue.implementation;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.queue.IQueueChunk;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Isolated
class SingleThreadQueueExtentFailureTest {

    private Settings.QUEUE previousQueue;

    @BeforeEach
    void setUp() {
        previousQueue = Settings.settings().QUEUE;
        if (Settings.settings().QUEUE == null) {
            Settings.settings().QUEUE = new Settings.QUEUE();
        }
    }

    @AfterEach
    void tearDown() {
        Settings.settings().QUEUE = previousQueue;
    }

    @Test
    void flushReportsCompletedFailureAndDrainsOtherChunks() throws Exception {
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        IllegalStateException failure = new IllegalStateException("Rejected off-region block read");
        Future<?> finished = mock(Future.class);
        when(finished.isDone()).thenReturn(true);
        when(finished.get()).thenReturn(null);
        try (MockedStatic<Fawe> fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            queue.submit(chunk(0, CompletableFuture.failedFuture(failure)));
            queue.submit(chunk(1, finished));

            RuntimeException thrown = assertThrows(RuntimeException.class, queue::flush);

            assertSame(failure, thrown.getCause());
            verify(finished).get();
            assertTrue(queue.isEmpty());
            assertThrows(RuntimeException.class, queue::flush);
        }
    }

    @Test
    void flushDoesNotDiscardAlreadyCompletedChunkWrites() throws Exception {
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        IllegalStateException failure = new IllegalStateException("Chunk processor failed immediately");
        Field field = SingleThreadQueueExtent.class.getDeclaredField("chunks");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var chunks = (Long2ObjectLinkedOpenHashMap<IQueueChunk<?>>) field.get(queue);
        chunks.put(0, chunk(0, CompletableFuture.failedFuture(failure)));
        try (MockedStatic<Fawe> fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);

            RuntimeException thrown = assertThrows(RuntimeException.class, queue::flush);

            assertSame(failure, thrown.getCause());
        }
    }

    @Test
    void flushFollowsNestedFutureFailures() throws Exception {
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        IllegalStateException failure = new IllegalStateException("Nested chunk write failed");
        try (MockedStatic<Fawe> fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            queue.submit(chunk(0, CompletableFuture.completedFuture(CompletableFuture.failedFuture(failure))));

            assertSame(failure, assertThrows(RuntimeException.class, queue::flush).getCause());
        }
    }

    @Test
    void successfulWritesStillCompleteNormally() throws Exception {
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        try (MockedStatic<Fawe> fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            queue.submit(chunk(0, CompletableFuture.completedFuture(null)));

            assertDoesNotThrow(queue::flush);
            assertTrue(queue.isEmpty());
        }
    }

    @Test
    void heapPressureRefusesUnstartedWriteEvenWithoutBuilderMemoryCheck() throws Exception {
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        IQueueChunk<?> chunk = chunk(0, CompletableFuture.completedFuture(null));
        try (var fawe = mockStatic(Fawe.class);
                var memory = mockStatic(com.fastasyncworldedit.core.util.MemUtil.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            memory.when(com.fastasyncworldedit.core.util.MemUtil::isMemoryLimited).thenReturn(true);
            assertThrows(RuntimeException.class, () -> queue.submit(chunk));
            verify(chunk, never()).call();
        }
    }

    @Test
    void cancellationPreservesAcceptedHistoryProcessor() {
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        var history = mock(com.fastasyncworldedit.core.queue.IBatchProcessor.class);
        queue.setPostProcessor(history);
        assertTrue(queue.cancel());
        assertSame(history, queue.getPostProcessor());
        assertThrows(RuntimeException.class, queue::checkFailure);
    }

    @Test
    void acceptedWriteCannotBeCancelledBeforeItsHistoryCompletes() throws Exception {
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        CompletableFuture<?> write = new CompletableFuture<>();
        try (MockedStatic<Fawe> fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            Future<?> completion = queue.submit(chunk(0, write));
            try {
                assertFalse(completion.cancel(true));
            } finally {
                write.complete(null);
            }
            assertDoesNotThrow(queue::flush);
        }
    }

    @Test
    void tickThreadCannotWaitForAnAcceptedWrite() throws Exception {
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        Future<?> write = mock(Future.class);
        when(write.get()).thenReturn(null);
        try (MockedStatic<Fawe> fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            queue.submit(chunk(0, write));
            assertThrows(IllegalStateException.class, queue::flush);
            verify(write, never()).get();
            assertFalse(queue.isEmpty());
            when(write.isDone()).thenReturn(true);
            assertDoesNotThrow(queue::flush);
        }
    }

    @Test
    void cancelledWritesAreNotReportedAsSuccessful() throws Exception {
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        CompletableFuture<?> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        try (MockedStatic<Fawe> fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            queue.submit(chunk(0, cancelled));

            assertInstanceOf(java.util.concurrent.CancellationException.class,
                    assertThrows(RuntimeException.class, queue::flush).getCause());
        }
    }

    @Test
    void failureStopsUnsubmittedChunksWithoutDiscardingAcceptedFutures() throws Exception {
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        IllegalStateException failure = new IllegalStateException("Audit capacity unavailable");
        IQueueChunk<?> rejected = chunk(0, null);
        when(rejected.call()).thenThrow(failure);
        IQueueChunk<?> unstarted = chunk(1, CompletableFuture.completedFuture(null));
        Field field = SingleThreadQueueExtent.class.getDeclaredField("chunks");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var chunks = (Long2ObjectLinkedOpenHashMap<IQueueChunk<?>>) field.get(queue);
        chunks.put(0, rejected);
        chunks.put(1, unstarted);
        try (MockedStatic<Fawe> fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            assertSame(failure, assertThrows(RuntimeException.class, queue::flush).getCause());
            verify(unstarted, never()).call();
            assertTrue(queue.isEmpty());
        }
    }

    @Test
    void queueCanBeReusedAfterFailedEdit() throws Exception {
        SingleThreadQueueExtent queue = new SingleThreadQueueExtent();
        com.sk89q.worldedit.world.World world = mock(com.sk89q.worldedit.world.World.class);
        when(world.isWorld()).thenReturn(true);
        queue.init(world, null, null);
        try (MockedStatic<Fawe> fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            queue.submit(chunk(0, CompletableFuture.failedFuture(new IllegalStateException("Write failed"))));
            assertThrows(RuntimeException.class, queue::flush);

            queue.init(world, null, null);
            queue.submit(chunk(0, CompletableFuture.completedFuture(null)));

            assertDoesNotThrow(queue::flush);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static IQueueChunk chunk(int x, Future<?> result) throws Exception {
        IQueueChunk chunk = mock(IQueueChunk.class);
        when(chunk.getX()).thenReturn(x);
        when(chunk.isEmpty()).thenReturn(false);
        when(chunk.call()).thenReturn(result);
        return chunk;
    }
}
