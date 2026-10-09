package com.fastasyncworldedit.core.queue.implementation;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.extent.processor.BatchProcessorHolder;
import com.fastasyncworldedit.core.extent.processor.EmptyBatchProcessor;
import com.fastasyncworldedit.core.extent.processor.MultiBatchProcessor;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.ChunkWriteScope;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChunkAdmissionTest {

    @BeforeAll
    static void configureQueue() {
        if (Settings.settings().QUEUE == null) Settings.settings().QUEUE = new Settings.QUEUE();
    }

    @Test
    void laterAdmissionFailureSettlesEarlierWriteBeforeClosing() throws Exception {
        List<String> calls = new ArrayList<>();
        IBatchProcessor first = new BatchProcessorHolder() {
            public AutoCloseable prepareChunk(IChunk chunk, IChunkSet changes) {
                return scope("first", calls);
            }
        };
        IBatchProcessor second = mock(IBatchProcessor.class);
        var rejected = new IllegalStateException("Admission failed");
        when(second.prepareChunk(null, null)).thenThrow(rejected);
        assertSame(rejected, assertThrows(IllegalStateException.class,
                () -> new MultiBatchProcessor(first, second).prepareChunk(null, null)));
        assertEquals(List.of("first:NOT_STARTED", "first:close"), calls);
    }

    @Test
    void composedAdmissionPreservesWriteLifecycle() throws Exception {
        List<String> calls = new ArrayList<>();
        IBatchProcessor first = new BatchProcessorHolder() {
            public AutoCloseable prepareChunk(IChunk chunk, IChunkSet changes) {
                return scope("first", calls);
            }
        };
        IBatchProcessor second = new BatchProcessorHolder() {
            public AutoCloseable prepareChunk(IChunk chunk, IChunkSet changes) {
                return scope("second", calls);
            }
        };
        try (AutoCloseable admission = new MultiBatchProcessor(first, second).prepareChunk(null, null)) {
            ChunkWriteScope write = assertInstanceOf(ChunkWriteScope.class, admission);
            write.beforeWrite();
            write.complete(ChunkWriteScope.Outcome.APPLIED, null);
        }
        assertEquals(List.of("first:prepare", "second:prepare", "first:APPLIED", "second:APPLIED",
                "second:close", "first:close"), calls);
    }

    private static ChunkWriteScope scope(String name, List<String> calls) {
        return new ChunkWriteScope() {
            public void beforeWrite() {
                calls.add(name + ":prepare");
            }

            public void complete(Outcome outcome, Throwable failure) {
                calls.add(name + ":" + outcome);
            }

            public void close() {
                calls.add(name + ":close");
            }
        };
    }

    @Test
    void admissionFailureClosesEarlierReservationsInReverseOrder() throws Exception {
        IBatchProcessor first = mock(IBatchProcessor.class);
        IBatchProcessor second = mock(IBatchProcessor.class);
        IBatchProcessor third = mock(IBatchProcessor.class);
        AutoCloseable firstScope = mock(AutoCloseable.class);
        AutoCloseable secondScope = mock(AutoCloseable.class);
        var chunk = mock(IChunk.class);
        var set = mock(IChunkSet.class);
        var rejected = new IllegalStateException("Audit writer unavailable");
        when(first.prepareChunk(chunk, set)).thenReturn(firstScope);
        when(second.prepareChunk(chunk, set)).thenReturn(secondScope);
        when(third.prepareChunk(chunk, set)).thenThrow(rejected);
        var processors = new MultiBatchProcessor(first, second, third);

        assertSame(rejected, assertThrows(IllegalStateException.class, () -> processors.prepareChunk(chunk, set)));
        var order = inOrder(secondScope, firstScope);
        order.verify(secondScope).close();
        order.verify(firstScope).close();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void tickThreadIsRejectedBeforeAdmissionCanWait() throws Exception {
        IQueueExtent owner = mock(IQueueExtent.class);
        IChunkSet set = mock(IChunkSet.class);
        IBatchProcessor processor = mock(IBatchProcessor.class);
        when(owner.getProcessor()).thenReturn(processor);
        ChunkHolder holder = ChunkHolder.newInstance();
        holder.init(owner, 0, 0);
        try (var fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(true);
            assertThrows(IllegalStateException.class, () -> holder.call(owner, set, () -> {}));
        }
        verify(processor, never()).prepareChunk(any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsBeforeChunkLocksAndAuditProcessing() throws Exception {
        IQueueExtent owner = mock(IQueueExtent.class);
        IChunkGet get = mock(IChunkGet.class);
        IChunkSet set = mock(IChunkSet.class);
        ChunkHolder holder = ChunkHolder.newInstance();
        holder.init(owner, 0, 0);
        var rejected = new IllegalStateException("Audit admission unavailable");
        var gate = new BatchProcessorHolder() {
            public AutoCloseable prepareChunk(IChunk chunk, IChunkSet changes) {
                assertFalse(Thread.holdsLock(chunk));
                verify(get, never()).lockCall();
                throw rejected;
            }
        };
        when(owner.getProcessor()).thenReturn(gate);
        when(owner.getPostProcessor()).thenReturn(EmptyBatchProcessor.getInstance());
        when(owner.getCachedGet(0, 0)).thenReturn(get);
        when(get.acquireWrite(anyLong())).thenReturn(() -> {});
        try (var fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::isMainThread).thenReturn(false);
            assertSame(rejected, assertThrows(IllegalStateException.class, () -> holder.call(owner, set, () -> {})));
        }
        verify(owner, never()).processSet(any(), any(), any());
        verify(get, never()).call(any(), any(), any());
    }
}
