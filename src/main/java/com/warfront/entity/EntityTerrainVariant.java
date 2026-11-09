package com.warfront.entity;

import com.warfront.region.SubRegionPos;
import com.warfront.region.terrain.TerrainClassifier;
import com.warfront.region.terrain.TerrainType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;

/**
 * Authoritative regional terrain texture variant classification for Warfront entities.
 *
 * Maps the 6 main tactical terrain categories used by biome-specific missions
 * (Plains, Desert, Forest, Mountain, Swamp, Tundra) based on the spawned subregion's
 * primary {@link TerrainType}.
 */
public enum EntityTerrainVariant {
    STANDARD((byte) 0), // Default / Grasslands (PLAINS, SAVANNA, RIVER, OCEAN)
    DESERT((byte) 1),   // Arid Wastes & Canyons (DESERT, BADLANDS)
    ARCTIC((byte) 2),   // Glacial Arctic (TUNDRA)
    FOREST((byte) 3),   // Woodlands (FOREST, TAIGA)
    MOUNTAIN((byte) 4), // Highlands (MOUNTAIN, HILLS)
    SWAMP((byte) 5);    // Wetlands & Jungles (SWAMP, JUNGLE)

    private final byte id;

    EntityTerrainVariant(byte id) {
        this.id = id;
    }

    public byte id() {
        return id;
    }

    public static EntityTerrainVariant byId(byte id) {
        return switch (id) {
            case 1 -> DESERT;
            case 2 -> ARCTIC;
            case 3 -> FOREST;
            case 4 -> MOUNTAIN;
            case 5 -> SWAMP;
            default -> STANDARD;
        };
    }

    /**
     * Maps a subregion's primary {@link TerrainType} to its corresponding 6-category {@link EntityTerrainVariant},
     * matching the exact grouping in {@code TerrainSpawnDoctrine} for biome-specific missions.
     */
    public static EntityTerrainVariant fromPrimaryTerrain(TerrainType primary) {
        if (primary == null) {
            return STANDARD;
        }
        return switch (primary) {
            case DESERT, BADLANDS -> DESERT;
            case TUNDRA -> ARCTIC;
            case FOREST, TAIGA -> FOREST;
            case MOUNTAIN, HILLS -> MOUNTAIN;
            case SWAMP, JUNGLE -> SWAMP;
            case PLAINS, SAVANNA, RIVER, OCEAN -> STANDARD;
        };
    }

    /**
     * Resolves the authoritative {@link EntityTerrainVariant} for a spawned mob on the server.
     * Evaluates the mob's origin subregion (if tagged by patrol/mission/garrison spawners)
     * or the subregion containing the mob's spawn position via $O(1)$ {@link TerrainClassifier}.
     */
    public static EntityTerrainVariant resolveForMob(Mob mob, ServerLevel level) {
        if (mob == null || level == null) {
            return STANDARD;
        }
        ServerLevel overworld = level.getServer() != null ? level.getServer().overworld() : level;
        if (overworld == null) {
            return STANDARD;
        }

        CompoundTag data = mob.getPersistentData();
        int rx;
        int rz;
        int sx;
        int sz;

        if (data.contains("originRegionX") && data.contains("originRegionZ")) {
            rx = data.getInt("originRegionX");
            rz = data.getInt("originRegionZ");
            if (data.contains("originSubX") && data.contains("originSubZ")) {
                sx = Math.clamp(data.getInt("originSubX"), 0, 1);
                sz = Math.clamp(data.getInt("originSubZ"), 0, 1);
            } else {
                ChunkPos cp = mob.chunkPosition();
                SubRegionPos subPos = SubRegionPos.fromChunk(cp.x, cp.z);
                sx = subPos.subX();
                sz = subPos.subZ();
            }
        } else {
            ChunkPos cp = mob.chunkPosition();
            SubRegionPos subPos = SubRegionPos.fromChunk(cp.x, cp.z);
            rx = subPos.regionX();
            rz = subPos.regionZ();
            sx = subPos.subX();
            sz = subPos.subZ();
        }

        TerrainClassifier.SubRegionTerrainData subTerrain = TerrainClassifier.getSubRegionTerrain(overworld, rx, rz, sx, sz);
        return fromPrimaryTerrain(subTerrain.primary());
    }
}
