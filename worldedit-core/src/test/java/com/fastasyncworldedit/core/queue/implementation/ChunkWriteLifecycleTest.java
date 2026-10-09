package com.fastasyncworldedit.core.queue.implementation;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.queue.ChunkWriteScope;
import com.fastasyncworldedit.core.queue.ChunkWriteSnapshot;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkHolder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class ChunkWriteLifecycleTest {

    @Test
    void legacyScopeCannotLeakOwnershipWhenNativeOmitsCallback() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.owner.getProcessor().prepareChunk(any(), any())).thenReturn(() -> {});
        doReturn(null).when(fixture.get).call(any(), any(), any());
        assertThrows(IllegalStateException.class, fixture::call, () -> "Observed callbacks: " + fixture.calls);
        assertFalse(fixture.owned.get());
    }

    @Test
    void legacyScopeFailureNeedsWorkerSettlementEvenIfNativeFutureIsDone() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.owner.getProcessor().prepareChunk(any(), any())).thenReturn(() -> {});
        when(fixture.get.call(any(), any(), any())).thenReturn(CompletableFuture.failedFuture(new IOException("Native failed")));
        Future<?> result = fixture.call();
        assertFalse(result.isDone());
        assertTrue(fixture.owned.get());
        assertThrows(ExecutionException.class, result::get);
        assertFalse(fixture.owned.get());
    }

    @BeforeAll
    static void configureQueue() {
        if (Settings.settings().QUEUE == null) Settings.settings().QUEUE = new Settings.QUEUE();
    }

    @Test
    void ownershipIsAcquiredBeforeAdmissionAndRetainedUntilAuditSettles() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.owner.getProcessor().prepareChunk(any(), any())).thenAnswer(invocation -> {
            assertTrue(fixture.owned.get());
            return fixture;
        });
        AtomicReference<Runnable> finalizer = new AtomicReference<>();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        when(fixture.get.call(any(), any(), any())).thenAnswer(invocation -> {
            finalizer.set(invocation.getArgument(2));
            return completion;
        });
        Future<?> result = fixture.call();
        assertTrue(fixture.owned.get());
        finalizer.get().run();
        assertFalse(fixture.owned.get());
        completion.complete(null);
        result.get();
    }

    @Test
    void nativeFailureRetainsObservedPartialHistoryBeforeUncertainAudit() throws Exception {
        Fixture fixture = new Fixture();
        IChunkSet partial = mock(IChunkSet.class);
        IChunkGet before = mock(IChunkGet.class);
        when(fixture.get.getFailureSnapshot(0)).thenReturn(new ChunkWriteSnapshot(before, partial));
        var failure = new IllegalStateException("Native write interrupted");
        when(fixture.get.call(any(), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, fixture::call));
        assertEquals(List.of("process", "prepare", "close", "history", "finalize", "UNCERTAIN"), fixture.calls);
        verify(fixture.owner).postProcess(fixture.holder, before, partial);
        verify(fixture.owner, never()).postProcess(fixture.holder, fixture.get, fixture.set);
    }

    @Test
    void checkpointRunsBeforeNativeAndAppliedFollowsHistory() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.get.call(any(), any(), any())).thenAnswer(invocation -> {
            fixture.calls.add("native");
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        });
        fixture.call();
        assertEquals(List.of("process", "prepare", "native", "history", "finalize", "APPLIED", "close"), fixture.calls);
    }

    @Test
    void checkpointFailureNeverReachesNativeOrHistory() throws Exception {
        Fixture fixture = new Fixture();
        fixture.prepareFailure = new IOException("Writer unavailable");
        assertThrows(IllegalStateException.class, fixture::call);
        assertEquals(List.of("process", "prepare", "close", "NOT_STARTED"), fixture.calls);
        assertSame(fixture.prepareFailure, fixture.failure);
        verify(fixture.get, never()).call(any(), any(), any());
    }

    @Test
    void filteredChunkSettlesWithoutStartingWrite() throws Exception {
        Fixture fixture = new Fixture();
        doReturn(null).when(fixture.owner).processSet(any(), any(), any());
        assertNull(fixture.call());
        assertEquals(List.of("NOT_STARTED", "close"), fixture.calls);
    }

    @Test
    void nativeFailureIsUncertainAndPreservesOriginalCause() throws Exception {
        Fixture fixture = new Fixture();
        var failure = new IllegalStateException("Native write interrupted");
        when(fixture.get.call(any(), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, fixture::call));
        assertEquals(List.of("process", "prepare", "close", "UNCERTAIN"), fixture.calls);
        assertSame(failure, fixture.failure);
    }

    @Test
    void nestedFailureSettlesAfterOriginalWorkerDetaches() throws Exception {
        Fixture fixture = new Fixture();
        var failure = new IOException("Chunk load failed");
        when(fixture.get.call(any(), any(), any())).thenReturn(
                CompletableFuture.completedFuture(CompletableFuture.failedFuture(failure)));
        Future<?> outer = fixture.call();
        assertEquals(List.of("process", "prepare", "close"), fixture.calls);
        Future<?> inner = (Future<?>) outer.get();
        assertSame(failure, assertThrows(ExecutionException.class, inner::get).getCause());
        assertEquals(List.of("process", "prepare", "close", "UNCERTAIN"), fixture.calls);
        assertSame(failure, fixture.failure);
    }

    @Test
    void auditAcknowledgementFailureRetainsHistoryAndFailsCompletion() throws Exception {
        Fixture fixture = new Fixture();
        fixture.completionFailure = new IOException("Audit acknowledgement failed");
        when(fixture.get.call(any(), any(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        });
        assertThrows(IllegalStateException.class, fixture::call);
        assertEquals(List.of("process", "prepare", "history", "finalize", "APPLIED", "close"), fixture.calls);
    }

    @Test
    void historyFailureStillSettlesAppliedAuditAndRunsFinalizer() throws Exception {
        Fixture fixture = new Fixture();
        var failure = new IllegalStateException("History storage failed");
        doThrow(failure).when(fixture.owner).postProcess(any(), any(), any());
        when(fixture.get.call(any(), any(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        });
        assertSame(failure, assertThrows(IllegalStateException.class, fixture::call));
        assertEquals(List.of("process", "prepare", "finalize", "APPLIED", "close"), fixture.calls);
        assertSame(failure, fixture.failure);
    }

    @Test
    void admittedFutureCannotBeCancelledAndCompletesExactlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        AtomicReference<Runnable> finalizer = new AtomicReference<>();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        when(fixture.get.call(any(), any(), any())).thenAnswer(invocation -> {
            finalizer.set(invocation.getArgument(2));
            return completion;
        });
        Future<?> result = fixture.call();
        assertFalse(result.cancel(true));
        finalizer.get().run();
        completion.complete(null);
        assertNull(result.get());
        assertNull(result.get());
        assertEquals(List.of("process", "prepare", "close", "history", "finalize", "APPLIED"), fixture.calls);
    }

    @Test
    void detachmentFailureDoesNotLosePendingNativeOutcome() throws Exception {
        Fixture fixture = new Fixture();
        fixture.closeFailure = new IOException("Scope detach failed");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        when(fixture.get.call(any(), any(), any())).thenReturn(completion);
        Future<?> result = fixture.call();
        var nativeFailure = new IOException("Native write failed");
        completion.completeExceptionally(nativeFailure);
        assertSame(nativeFailure, assertThrows(ExecutionException.class, result::get).getCause());
        assertSame(nativeFailure, fixture.failure);
        assertArrayEquals(new Throwable[]{fixture.closeFailure}, nativeFailure.getSuppressed());
        assertEquals(List.of("process", "prepare", "close", "UNCERTAIN"), fixture.calls);
    }

    @Test
    void unsettledCompletedFutureIsNotSafeToDrainOnRegionThread() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.get.call(any(), any(), any())).thenReturn(
                CompletableFuture.failedFuture(new IOException("Native write failed")));
        Future<?> result = fixture.call();
        assertFalse(result.isDone());
        assertThrows(ExecutionException.class, result::get);
        assertTrue(result.isDone());
    }

    private static final class Fixture implements ChunkWriteScope {
        final List<String> calls = new ArrayList<>();
        final IQueueExtent owner = mock(IQueueExtent.class);
        final IChunkGet get = mock(IChunkGet.class);
        final IChunkSet set = mock(IChunkSet.class);
        final ChunkHolder holder = ChunkHolder.newInstance();
        final AtomicBoolean owned = new AtomicBoolean();
        IOException prepareFailure;
        IOException completionFailure;
        IOException closeFailure;
        Throwable failure;

        Fixture() throws Exception {
            holder.init(owner, 0, 0);
            IBatchProcessor processor = mock(IBatchProcessor.class);
            when(processor.prepareChunk(any(), any())).thenReturn(this);
            when(owner.getProcessor()).thenReturn(processor);
            when(owner.getPostProcessor()).thenReturn(mock(IBatchProcessor.class));
            when(owner.getCachedGet(0, 0)).thenReturn(get);
            when(get.acquireWrite(anyLong())).thenAnswer(invocation -> {
                assertTrue(owned.compareAndSet(false, true));
                return (AutoCloseable) () -> assertTrue(owned.compareAndSet(true, false));
            });
            when(get.getCopy(0)).thenReturn(get);
            when(owner.processSet(any(), any(), any())).thenAnswer(invocation -> {
                calls.add("process");
                return set;
            });
            doAnswer(invocation -> {
                calls.add("history");
                return null;
            }).when(owner).postProcess(any(), any(), any());
        }

        Future<?> call() {
            try (var fawe = mockStatic(Fawe.class)) {
                fawe.when(Fawe::isMainThread).thenReturn(false);
                return holder.call(owner, set, () -> calls.add("finalize"));
            }
        }

        @Override
        public void beforeWrite() throws IOException {
            calls.add("prepare");
            if (prepareFailure != null) throw prepareFailure;
        }

        @Override
        public void complete(Outcome outcome, Throwable failure) throws IOException {
            calls.add(outcome.name());
            this.failure = failure;
            if (completionFailure != null) throw completionFailure;
        }

        @Override
        public void close() throws IOException {
            calls.add("close");
            if (closeFailure != null) throw closeFailure;
        }
    }
}
