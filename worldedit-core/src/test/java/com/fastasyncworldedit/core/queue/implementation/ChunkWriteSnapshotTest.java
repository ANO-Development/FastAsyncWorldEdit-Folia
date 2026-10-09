package com.fastasyncworldedit.core.queue.implementation;

import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.ChunkWriteSnapshot;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.implementation.blocks.CharSetBlocks;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChunkWriteSnapshotTest {

    @Test
    void changedBlockRetainsItsCurrentTileEvenWhenMetadataIsEqual() {
        IChunkGet before = mock(IChunkGet.class);
        IChunkGet after = mock(IChunkGet.class);
        IChunkSet intended = CharSetBlocks.newInstance(0, 0);
        IChunkSet applied = CharSetBlocks.newInstance(0, 0);
        char[] original = new char[4096];
        char[] current = new char[4096];
        original[0] = 2;
        current[0] = 3;
        intended.setBlocks(0, current.clone());
        when(before.load(0)).thenReturn(original);
        when(after.load(0)).thenReturn(current);
        var tile = FaweCompoundTag.of(LinCompoundTag.builder().putString("CustomName", "unchanged").build());
        when(before.tiles()).thenReturn(Map.of(BlockVector3.ZERO, tile));
        when(after.tiles()).thenReturn(Map.of(BlockVector3.ZERO, tile));
        ChunkWriteSnapshot.captureChanges(before, after, intended, applied);
        assertSame(tile, applied.tiles().get(BlockVector3.ZERO));
    }

    @Test
    void metadataOnlyChangeIsKeptWithoutChangingBlockType() {
        IChunkGet before = mock(IChunkGet.class);
        IChunkGet after = mock(IChunkGet.class);
        IChunkSet intended = CharSetBlocks.newInstance(-5, 17);
        IChunkSet applied = CharSetBlocks.newInstance(-5, 17);
        char[] blocks = new char[4096];
        blocks[0] = 2;
        intended.setBlocks(-4, blocks.clone());
        when(before.load(-4)).thenReturn(blocks);
        when(after.load(-4)).thenReturn(blocks);
        var position = BlockVector3.at(-80, -64, 272);
        var original = FaweCompoundTag.of(LinCompoundTag.builder().putString("CustomName", "old").build());
        var current = FaweCompoundTag.of(LinCompoundTag.builder().putString("CustomName", "new").build());
        when(before.tiles()).thenReturn(Map.of(position, original));
        when(after.tiles()).thenReturn(Map.of(position, current));

        ChunkWriteSnapshot.captureChanges(before, after, intended, applied);

        assertNotNull(applied.loadIfPresent(-4));
        assertEquals(2, applied.loadIfPresent(-4)[0]);
        assertSame(current, applied.tiles().get(BlockVector3.at(0, -64, 0)));
    }

    @Test
    void biomeHistoryContainsOnlyObservedChangedCells() {
        IChunkGet before = mock(IChunkGet.class);
        IChunkGet after = mock(IChunkGet.class);
        IChunkSet intended = CharSetBlocks.newInstance(0, 0);
        IChunkSet applied = CharSetBlocks.newInstance(0, 0);
        BiomeType original = mock(BiomeType.class);
        BiomeType current = mock(BiomeType.class);
        intended.setBiome(0, -64, 0, current);
        intended.setBiome(4, -64, 0, current);
        when(before.getBiomeType(0, -64, 0)).thenReturn(original);
        when(after.getBiomeType(0, -64, 0)).thenReturn(current);
        when(before.getBiomeType(4, -64, 0)).thenReturn(original);
        when(after.getBiomeType(4, -64, 0)).thenReturn(original);

        ChunkWriteSnapshot.captureChanges(before, after, intended, applied);

        assertTrue(applied.hasBiomes(-4));
        assertSame(current, applied.getBiomes()[0][0]);
        assertNull(applied.getBiomes()[0][1]);
    }

    @Test
    void entityHistoryDoesNotInventUnexecutedSpawnsOrRemovals() {
        IChunkGet before = mock(IChunkGet.class);
        IChunkGet after = mock(IChunkGet.class);
        IChunkSet intended = CharSetBlocks.newInstance(0, 0);
        IChunkSet applied = CharSetBlocks.newInstance(0, 0);
        var removed = FaweCompoundTag.of(LinCompoundTag.builder().putLong("UUIDMost", 0).putLong("UUIDLeast", 1).build());
        var survived = FaweCompoundTag.of(LinCompoundTag.builder().putLong("UUIDMost", 0).putLong("UUIDLeast", 2).build());
        var spawned = FaweCompoundTag.of(LinCompoundTag.builder().putLong("UUIDMost", 0).putLong("UUIDLeast", 3).build());
        when(before.entities()).thenReturn(List.of(removed, survived));
        when(after.entities()).thenReturn(List.of(survived, spawned));
        ChunkWriteSnapshot.captureChanges(before, after, intended, applied);
        assertEquals(java.util.Set.of(new UUID(0, 1)), applied.getEntityRemoves());
        assertEquals(List.of(spawned), List.copyOf(applied.entities()));
    }

    @BeforeAll
    static void configureQueue() {
        if (Settings.settings().QUEUE == null) Settings.settings().QUEUE = new Settings.QUEUE();
    }

    @Test
    void failedWriteHistoryUsesActualChangesNotUnexecutedIntent() {
        IChunkGet before = mock(IChunkGet.class);
        IChunkGet after = mock(IChunkGet.class);
        IChunkSet intended = CharSetBlocks.newInstance(-5, 17);
        IChunkSet applied = CharSetBlocks.newInstance(-5, 17);
        char[] oldBlocks = new char[4096];
        char[] currentBlocks = new char[4096];
        char[] requested = new char[4096];
        oldBlocks[0] = 2;
        oldBlocks[1] = 4;
        oldBlocks[2] = 7;
        currentBlocks[0] = 3;
        currentBlocks[1] = 4;
        currentBlocks[2] = 8;
        requested[0] = 3;
        requested[1] = 6;
        intended.setBlocks(-4, requested);
        when(before.load(-4)).thenReturn(oldBlocks);
        when(after.load(-4)).thenReturn(currentBlocks);

        ChunkWriteSnapshot.captureChanges(before, after, intended, applied);

        char[] actual = applied.loadIfPresent(-4);
        assertNotNull(actual);
        assertEquals(3, actual[0]);
        assertEquals(0, actual[1], "Unexecuted intended change is not history");
        assertEquals(0, actual[2], "An unrelated changed cell is not history");
        assertArrayEquals(new char[]{2, 4, 7}, java.util.Arrays.copyOf(oldBlocks, 3));
    }
}
