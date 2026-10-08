package com.sk89q.worldedit.extent.clipboard;

import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.entity.EntityType;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClipboardPasteTest {

    @ParameterizedTest
    @CsvSource({"0,0,9", "1,1,9", "-17,-1,12", "-32,16,9"})
    void finishesEachDestinationChunkBeforeMovingOn(int destinationX, int destinationZ, int expectedChunks) {
        CuboidRegion source = new CuboidRegion(null, BlockVector3.at(7, -3, 11), BlockVector3.at(41, 0, 43), false);
        BlockVector3 origin = BlockVector3.at(12, 9, -20);
        BlockVector3 minimum = BlockVector3.at(destinationX, 64, destinationZ);
        BlockVector3 destination = minimum.add(origin).subtract(source.getMinimumPoint());
        BaseBlock block = mock(BaseBlock.class);
        Clipboard clipboard = clipboard(source, origin, block);
        RecordingExtent target = new RecordingExtent(block);

        clipboard.paste(target, destination, true, false, false);

        assertEquals(4620, target.positions.size());
        assertEquals(expectedChunks, target.chunks.size());
        for (int x = destinationX; x < destinationX + 35; x++) {
            for (int z = destinationZ; z < destinationZ + 33; z++) {
                for (int y = 64; y < 68; y++) {
                    assertTrue(target.positions.contains(BlockVector3.at(x, y, z)));
                }
            }
        }
    }

    @Test
    void preservesBlockPayloadsAndBiomesWhenFilteringAirAcrossAChunkBoundary() {
        CuboidRegion source = new CuboidRegion(null, BlockVector3.at(7, -3, 11), BlockVector3.at(9, -3, 11), false);
        BaseBlock first = block(false);
        BaseBlock air = block(true);
        BaseBlock last = block(false);
        BiomeType biome = mock(BiomeType.class);
        Clipboard clipboard = clipboard(source, BlockVector3.at(7, -3, 11), first);
        doAnswer(invocation -> switch (invocation.getArgument(0, BlockVector3.class).x()) {
            case 7 -> first;
            case 8 -> air;
            case 9 -> last;
            default -> throw new AssertionError("Read outside clipboard");
        }).when(clipboard).getFullBlock(any(BlockVector3.class));
        doReturn(true).when(clipboard).hasBiomes();
        doReturn(biome).when(clipboard).getBiome(any(BlockVector3.class));
        Map<BlockVector3, BlockStateHolder<?>> blocks = new HashMap<>();
        Map<BlockVector3, BiomeType> biomes = new HashMap<>();
        NullExtent target = new NullExtent() {
            @Override
            public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T value) {
                blocks.put(BlockVector3.at(x, y, z), value);
                return true;
            }

            @Override
            public boolean setBiome(int x, int y, int z, BiomeType value) {
                biomes.put(BlockVector3.at(x, y, z), value);
                return true;
            }
        };

        clipboard.paste(target, BlockVector3.at(15, 64, -1), false, false, true);

        assertEquals(Map.of(BlockVector3.at(15, 64, -1), first, BlockVector3.at(17, 64, -1), last), blocks);
        assertEquals(Map.of(BlockVector3.at(15, 64, -1), biome, BlockVector3.at(16, 64, -1), biome,
                BlockVector3.at(17, 64, -1), biome), biomes);
    }

    @Test
    void preservesNonCuboidClipboardMembership() {
        CuboidRegion bounds = new CuboidRegion(null, BlockVector3.ZERO, BlockVector3.at(31, 3, 31), false);
        BaseBlock block = mock(BaseBlock.class);
        Clipboard clipboard = clipboard(bounds, BlockVector3.ZERO, block);
        Region region = mock(Region.class);
        doReturn(region).when(clipboard).getRegion();
        doAnswer(ignored -> List.of(BlockVector3.ZERO, BlockVector3.at(31, 3, 31)).iterator()).when(clipboard).iterator();
        RecordingExtent target = new RecordingExtent(block);

        clipboard.paste(target, BlockVector3.ZERO, true, false, false);

        assertEquals(Set.of(BlockVector3.ZERO, BlockVector3.at(31, 3, 31)), target.positions);
    }

    @Test
    void propagatesCancellationWithoutContinuingThePaste() {
        CuboidRegion source = new CuboidRegion(null, BlockVector3.ZERO, BlockVector3.at(31, 3, 31), false);
        Clipboard clipboard = clipboard(source, BlockVector3.ZERO, mock(BaseBlock.class));
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException cancellation = new IllegalStateException("Edit cancelled");
        NullExtent target = new NullExtent() {
            @Override
            public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block) {
                if (attempts.incrementAndGet() == 17) {
                    throw cancellation;
                }
                return true;
            }
        };

        assertSame(cancellation, assertThrows(IllegalStateException.class,
                () -> clipboard.paste(target, BlockVector3.ZERO, true, false, false)));
        assertEquals(17, attempts.get());
    }

    private static BaseBlock block(boolean air) {
        BaseBlock block = mock(BaseBlock.class);
        BlockType type = mock(BlockType.class);
        BlockMaterial material = mock(BlockMaterial.class);
        when(block.getBlockType()).thenReturn(type);
        when(type.getMaterial()).thenReturn(material);
        when(material.isAir()).thenReturn(air);
        return block;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesEntityOffsetsAndPasteFlag(boolean pasteEntities) {
        CuboidRegion source = new CuboidRegion(null, BlockVector3.at(7, -3, 11), BlockVector3.at(8, -3, 11), false);
        Clipboard clipboard = clipboard(source, BlockVector3.at(7, -3, 11), mock(BaseBlock.class));
        Entity entity = mock(Entity.class);
        BaseEntity state = mock(BaseEntity.class);
        EntityType type = mock(EntityType.class);
        when(type.id()).thenReturn("minecraft:pig");
        when(state.getType()).thenReturn(type);
        when(entity.getState()).thenReturn(state);
        when(entity.getLocation()).thenReturn(new Location(clipboard, 7.5, -2.5, 11.25, 90, 15));
        doReturn(List.of(entity)).when(clipboard).getEntities();
        List<Location> locations = new ArrayList<>();
        NullExtent target = new NullExtent() {
            @Override
            public Entity createEntity(Location location, BaseEntity value) {
                assertSame(state, value);
                locations.add(location);
                return null;
            }
        };

        clipboard.paste(target, BlockVector3.at(-17, 64, -1), true, pasteEntities, false);

        assertEquals(pasteEntities ? 1 : 0, locations.size());
        if (pasteEntities) {
            Location location = locations.getFirst();
            assertEquals(-16.5, location.x());
            assertEquals(64.5, location.y());
            assertEquals(-0.75, location.z());
            assertEquals(90, location.getYaw());
            assertEquals(15, location.getPitch());
        }
    }

    private static Clipboard clipboard(CuboidRegion region, BlockVector3 origin, BaseBlock block) {
        Clipboard clipboard = mock(Clipboard.class, CALLS_REAL_METHODS);
        doReturn(region).when(clipboard).getRegion();
        doReturn(region.getMinimumPoint()).when(clipboard).getMinimumPoint();
        doReturn(region.getMaximumPoint()).when(clipboard).getMaximumPoint();
        doReturn(origin).when(clipboard).getOrigin();
        doAnswer(ignored -> region.iterator_old()).when(clipboard).iterator();
        doAnswer(invocation -> {
            assertTrue(region.contains(invocation.getArgument(0, BlockVector3.class)));
            return block;
        }).when(clipboard).getFullBlock(any(BlockVector3.class));
        return clipboard;
    }

    private static final class RecordingExtent extends NullExtent {
        private final BaseBlock expectedBlock;
        private final Set<BlockVector3> positions = new HashSet<>();
        private final Set<Long> chunks = new HashSet<>();
        private Long currentChunk;

        private RecordingExtent(BaseBlock expectedBlock) {
            this.expectedBlock = expectedBlock;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(int x, int y, int z, T block) {
            long chunk = ((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL);
            if (currentChunk == null || currentChunk != chunk) {
                assertTrue(chunks.add(chunk), "Paste revisited a completed destination chunk");
                currentChunk = chunk;
            }
            assertTrue(positions.add(BlockVector3.at(x, y, z)), "Paste wrote the same position twice");
            assertSame(expectedBlock, block);
            return true;
        }
    }
}
