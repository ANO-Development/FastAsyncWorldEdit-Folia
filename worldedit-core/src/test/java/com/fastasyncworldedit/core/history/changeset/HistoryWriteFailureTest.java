package com.fastasyncworldedit.core.history.changeset;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import org.enginehub.linbus.tree.LinCompoundTag;
import com.sk89q.worldedit.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Isolated
class HistoryWriteFailureTest {

    @Test
    void partialHistoryDoesNotRemoveTilesInUnexecutedRemainder() {
        var history = mock(AbstractChangeSet.class,
                withSettings().useConstructor(mock(World.class)).defaultAnswer(CALLS_REAL_METHODS));
        IChunk chunk = mock(IChunk.class);
        IChunkGet before = mock(IChunkGet.class);
        IChunkSet applied = mock(IChunkSet.class);
        var tile = FaweCompoundTag.of(LinCompoundTag.builder().putString("id", "minecraft:chest").build());
        when(before.tiles()).thenReturn(java.util.Map.of(BlockVector3.at(-80, -64, 272), tile));
        var oldBlock = mock(BlockState.class);
        var untouched = mock(BlockState.class);
        when(untouched.getOrdinal()).thenReturn(0);
        when(before.getBlock(0, -64, 0)).thenReturn(oldBlock);
        when(applied.getBlock(0, -64, 0)).thenReturn(untouched);
        history.processSet(chunk, before, applied);
        verify(history, never()).addTileRemove(any(FaweCompoundTag.class));
    }

    @Test
    void removedContainerMetadataIsPreservedEvenWhenBlockTypeDidNotChange() {
        var history = mock(AbstractChangeSet.class,
                withSettings().useConstructor(mock(World.class)).defaultAnswer(CALLS_REAL_METHODS));
        IChunk chunk = mock(IChunk.class);
        IChunkGet before = mock(IChunkGet.class);
        IChunkSet applied = mock(IChunkSet.class);
        var tile = FaweCompoundTag.of(LinCompoundTag.builder().putString("id", "minecraft:chest").build());
        when(before.tiles()).thenReturn(java.util.Map.of(BlockVector3.at(-80, -64, 272), tile));
        var block = mock(BlockState.class);
        when(block.getOrdinal()).thenReturn(20);
        when(before.getBlock(0, -64, 0)).thenReturn(block);
        when(applied.getBlock(0, -64, 0)).thenReturn(block);
        history.processSet(chunk, before, applied);
        var tag = org.mockito.ArgumentCaptor.forClass(FaweCompoundTag.class);
        verify(history).addTileRemove(tag.capture());
        assertEquals(-80, tag.getValue().linTag().getTag("x", org.enginehub.linbus.tree.LinTagType.intTag()).value());
        assertEquals(272, tag.getValue().linTag().getTag("z", org.enginehub.linbus.tree.LinTagType.intTag()).value());
    }

    @Test
    void diskUndoCannotReturnSuccessAfterHistoryCloseFailure() throws Exception {
        var history = mock(com.fastasyncworldedit.core.history.DiskStorageHistory.class, CALLS_REAL_METHODS);
        var failure = new IOException("Cannot finalize history stream");
        doThrow(failure).when(history).close();
        assertSame(failure, assertThrows(RuntimeException.class, () -> history.undo(null, null)).getCause());
        assertSame(failure, assertThrows(RuntimeException.class, () -> history.redo(null, null)).getCause());
    }

    @Test
    void blockHistoryIoFailureCannotBeReportedAsWritten() throws Exception {
        var history = mock(FaweStreamChangeSet.class,
                withSettings().useConstructor(mock(World.class), 0, true, false).defaultAnswer(CALLS_REAL_METHODS));
        var failure = new IOException("History storage unavailable");
        doThrow(failure).when(history).getBlockOS(1, 2, 3);
        assertSame(failure, assertThrows(RuntimeException.class, () -> history.add(1, 2, 3, 1, 2)).getCause());
    }

    @Test
    void asynchronousHistoryFailureIsReportedByFutureAndFlush() throws Exception {
        var history = mock(AbstractChangeSet.class,
                withSettings().useConstructor(mock(World.class)).defaultAnswer(CALLS_REAL_METHODS));
        var faweInstance = mock(Fawe.class);
        var handler = mock(QueueHandler.class);
        var worker = Executors.newSingleThreadExecutor();
        when(faweInstance.getQueueHandler()).thenReturn(handler);
        when(handler.async(any(Runnable.class))).thenAnswer(call -> worker.submit(call.getArgument(0, Runnable.class)));
        var failure = new IllegalStateException("Injected history write failure");
        try (var fawe = mockStatic(Fawe.class)) {
            fawe.when(Fawe::instance).thenReturn(faweInstance);
            var result = history.addWriteTask(() -> { throw failure; }, false);
            assertSame(failure, assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause());
            assertThrows(RuntimeException.class, history::flush);
        } finally {
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
