package com.fastasyncworldedit.core.queue;

import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.util.NbtUtils;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Ownership of a pre-write snapshot and changes observed after a failed native write. */
public record ChunkWriteSnapshot(IChunkGet before, IChunkSet applied) {

    public ChunkWriteSnapshot {
        Objects.requireNonNull(before);
        Objects.requireNonNull(applied);
    }

    /** Derive partial history from owned snapshots, never from the unexecuted remainder of an intended write. */
    public static void captureChanges(IChunkGet before, IChunkGet after, IChunkSet intended, IChunkSet applied) {
        for (int layer = intended.getMinSectionPosition(); layer <= intended.getMaxSectionPosition(); layer++) {
            if (!intended.hasSection(layer)) continue;
            char[] requested = intended.loadIfPresent(layer);
            char[] original = Objects.requireNonNull(before.load(layer));
            char[] current = Objects.requireNonNull(after.load(layer));
            char[] changed = null;
            for (int index = 0; index < requested.length; index++) {
                if (requested[index] != 0 && original[index] != current[index]) {
                    if (changed == null) changed = new char[4096];
                    changed[index] = current[index];
                }
            }
            if (changed != null) applied.setBlocks(layer, changed);
        }
        captureTiles(before, after, intended, applied);
        captureBiomes(before, after, intended, applied);
        Map<UUID, FaweCompoundTag> oldEntities = entitiesById(before);
        Map<UUID, FaweCompoundTag> currentEntities = entitiesById(after);
        for (UUID uuid : oldEntities.keySet()) {
            if (!currentEntities.containsKey(uuid)) applied.removeEntity(uuid);
        }
        currentEntities.forEach((uuid, tag) -> {
            if (!oldEntities.containsKey(uuid)) applied.entity(tag);
        });
    }

    private static void captureTiles(IChunkGet before, IChunkGet after, IChunkSet intended, IChunkSet applied) {
        var positions = new HashSet<>(before.tiles().keySet());
        positions.addAll(after.tiles().keySet());
        for (BlockVector3 position : positions) {
            int x = position.x() & 15;
            int y = position.y();
            int z = position.z() & 15;
            int index = (y & 15) << 8 | z << 4 | x;
            char[] requested = intended.loadIfPresent(y >> 4);
            if ((requested == null || requested[index] == 0) && !intended.tiles().containsKey(BlockVector3.at(x, y, z))) {
                continue;
            }
            FaweCompoundTag oldTag = before.tiles().get(position);
            FaweCompoundTag currentTag = after.tiles().get(position);
            char[] changed = applied.loadIfPresent(y >> 4);
            if ((changed == null || changed[index] == 0)
                    && Objects.equals(oldTag == null ? null : oldTag.linTag(), currentTag == null ? null : currentTag.linTag())) {
                continue;
            }
            if (changed == null) {
                changed = new char[4096];
                applied.setBlocks(y >> 4, changed);
            }
            changed[index] = after.load(y >> 4)[index];
            if (currentTag != null) applied.tile(x, y, z, currentTag);
        }
    }

    private static void captureBiomes(IChunkGet before, IChunkGet after, IChunkSet intended, IChunkSet applied) {
        if (intended.getBiomes() == null) return;
        for (int layer = intended.getMinSectionPosition(); layer <= intended.getMaxSectionPosition(); layer++) {
            if (!intended.hasBiomes(layer)) continue;
            BiomeType[] requested = intended.getBiomes()[layer - intended.getMinSectionPosition()];
            for (int index = 0; index < 64; index++) {
                if (requested[index] == null) continue;
                int x = (index & 3) << 2;
                int z = index & 12;
                int y = (layer << 4) + ((index >> 4) << 2);
                BiomeType current = after.getBiomeType(x, y, z);
                if (!Objects.equals(before.getBiomeType(x, y, z), current)) applied.setBiome(x, y, z, current);
            }
        }
    }

    private static Map<UUID, FaweCompoundTag> entitiesById(IChunkGet snapshot) {
        Map<UUID, FaweCompoundTag> entities = new HashMap<>();
        for (FaweCompoundTag tag : snapshot.entities()) entities.put(NbtUtils.uuid(tag), tag);
        return entities;
    }
}
