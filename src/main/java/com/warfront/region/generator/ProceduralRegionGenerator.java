package com.warfront.region.generator;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

public class ProceduralRegionGenerator {
    private static final ProceduralRegionGenerator INSTANCE = new ProceduralRegionGenerator();

    // =========================================================================================
    // TWEAKABLE CONFIGURATION PARAMETERS
    // =========================================================================================
    // Biomes where NO expansion / attacks can occur (e.g. Oceans)
    public static final String[] RESTRICTED_EXPANSION_BIOMES = new String[] {
            "minecraft:ocean",
            "minecraft:deep_ocean",
            "minecraft:warm_ocean",
            "minecraft:lukewarm_ocean",
            "minecraft:deep_lukewarm_ocean",
            "minecraft:cold_ocean",
            "minecraft:deep_cold_ocean",
            "minecraft:frozen_ocean",
            "minecraft:deep_frozen_ocean"
    };

    // Biomes where NO base (Headquarters, Outpost, etc.) can be constructed (e.g. Oceans & Rivers)
    public static final String[] RESTRICTED_BASE_BIOMES = new String[] {
            "minecraft:ocean",
            "minecraft:deep_ocean",
            "minecraft:warm_ocean",
            "minecraft:lukewarm_ocean",
            "minecraft:deep_lukewarm_ocean",
            "minecraft:cold_ocean",
            "minecraft:deep_cold_ocean",
            "minecraft:frozen_ocean",
            "minecraft:deep_frozen_ocean",
            "minecraft:river",
            "minecraft:frozen_river"
    };

    // 1. FACTION_BUFFER_DISTANCE: Minimum number of unclaimed regions required between different faction borders.
    public static final int FACTION_BUFFER_DISTANCE = 2;

    private final List<FactionGenerator> factionGenerators = new ArrayList<>();

    public ProceduralRegionGenerator() {
        // --- PILLAGER CONQUERORS GENERATOR (Priority 0) ---
        registerGenerator(new GridClusterFactionGenerator(
                Faction.PILLAGER_CONQUERORS,
                new FactionGeneratorConfig(
                        com.warfront.config.WarfrontConfig.PILLAGER_SEPARATION.get(),
                        1, // minClusterSize
                        2, // maxClusterSize
                        2, // minDistanceFromSpawn
                        0.8F, // defaultStability
                        0.6F, // defaultResistance
                        1001L // seedSalt
                )));

        // --- ZOMBIE HORDE GENERATOR (Priority 1) ---
        registerGenerator(new GridClusterFactionGenerator(
                Faction.ZOMBIE_HORDE,
                new FactionGeneratorConfig(
                        com.warfront.config.WarfrontConfig.ZOMBIE_SEPARATION.get(),
                        1, // minClusterSize
                        2, // maxClusterSize
                        2, // minDistanceFromSpawn
                        0.7F, // defaultStability
                        0.4F, // defaultResistance
                        2002L // seedSalt
                )));
    }

    public static ProceduralRegionGenerator getInstance() {
        return INSTANCE;
    }

    public void registerGenerator(FactionGenerator generator) {
        factionGenerators.add(generator);
    }

    public RegionData.RegionState generateRegion(long worldSeed, int regionX, int regionZ) {
        return generateRegion(null, worldSeed, regionX, regionZ);
    }

    private final Map<Long, RegionData.RegionState> proceduralRawStateCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Evaluates procedural region state for unsaved regions without calculating strength values, preventing recursion loops.
     * Reuses proceduralRawStateCache to make snapshot building instant.
     */
    public RegionData.RegionState generateRawRegionState(ServerLevel level, long worldSeed, int regionX, int regionZ) {
        long key = ChunkPos.asLong(regionX, regionZ);
        RegionData.RegionState cached = proceduralRawStateCache.get(key);
        if (cached != null) {
            return cached;
        }

        for (int i = 0; i < factionGenerators.size(); i++) {
            FactionGenerator generator = factionGenerators.get(i);
            if (generator instanceof GridClusterFactionGenerator gridGen) {
                Optional<RegionData.RegionState> result = gridGen.generateRawRegionState(level, worldSeed, regionX, regionZ);
                if (result.isPresent()) {
                    if (biomeAvailableForBase(level, regionX, regionZ)) {
                        RegionData.RegionState state = result.get();
                        proceduralRawStateCache.put(key, state);
                        return state;
                    }
                }
            }
        }
        RegionData.RegionState unclaimed = new RegionData.RegionState(Faction.UNCLAIMED, 0.0F, 0.0F, BaseType.NONE, 0L);
        proceduralRawStateCache.put(key, unclaimed);
        return unclaimed;
    }

    /**
     * Evaluates procedural region state for unsaved regions.
     * Evaluates symmetrical grid cluster generators for Pillagers and Zombies.
     */
    public RegionData.RegionState generateRegion(ServerLevel level, long worldSeed, int regionX, int regionZ) {
        for (int i = 0; i < factionGenerators.size(); i++) {
            FactionGenerator generator = factionGenerators.get(i);
            Optional<RegionData.RegionState> result = generator.generateRegion(level, worldSeed, regionX, regionZ);

            if (result.isPresent()) {
                if (biomeAvailableForBase(level, regionX, regionZ)) {
                    return result.get();
                }
            }
        }
        return new RegionData.RegionState(Faction.UNCLAIMED, 0.0F, 0.0F, BaseType.NONE, 0L);
    }

    /**
     * Legacy helper method, checks against RESTRICTED_BASE_BIOMES.
     */
    public boolean biomeAvailable(ServerLevel level, int regionX, int regionZ) {
        return biomeAvailableForBase(level, regionX, regionZ);
    }

    private final Map<Long, Boolean> baseBiomeCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Boolean> expansionBiomeCache = new java.util.concurrent.ConcurrentHashMap<>();

    public void clearBiomeCache() {
        baseBiomeCache.clear();
        expansionBiomeCache.clear();
    }

    /**
     * Checks if the region at (regionX, regionZ) is allowed for faction expansion/attacks (excludes if restricted biomes exceed 50% across 8x8 chunks).
     */
    public boolean biomeAvailableForExpansion(ServerLevel level, int regionX, int regionZ) {
        if (level == null) {
            return true;
        }
        long key = ChunkPos.asLong(regionX, regionZ);
        Boolean cached = expansionBiomeCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = isBiomeAllowedFullRegion(level, regionX, regionZ, RESTRICTED_EXPANSION_BIOMES);
        expansionBiomeCache.put(key, result);
        return result;
    }

    /**
     * Relaxed center-focused biome check for initial procedural base placement.
     * Evaluates the inner 4x4 chunk area (16 sample points) surrounding the center of the region.
     */
    public boolean biomeAvailableForBase(ServerLevel level, int regionX, int regionZ) {
        if (level == null) {
            return true;
        }

        long key = ChunkPos.asLong(regionX, regionZ);
        Boolean cached = baseBiomeCache.get(key);
        if (cached != null) {
            return cached;
        }

        boolean result = computeBiomeAvailableForBase(level, regionX, regionZ);
        baseBiomeCache.put(key, result);
        return result;
    }

    private boolean computeBiomeAvailableForBase(ServerLevel level, int regionX, int regionZ) {
        int startBlockX = regionX * RegionData.REGION_SIZE_BLOCKS;
        int startBlockZ = regionZ * RegionData.REGION_SIZE_BLOCKS;

        int restrictedCount = 0;
        int totalCenterSamples = 16; // 4x4 inner chunks

        // Sample center 4x4 chunks (chunk offset 2 to 5) of the 8x8 region
        for (int chunkX = 2; chunkX <= 5; chunkX++) {
            for (int chunkZ = 2; chunkZ <= 5; chunkZ++) {
                int sampleX = startBlockX + (chunkX * 16) + 8;
                int sampleZ = startBlockZ + (chunkZ * 16) + 8;
                BlockPos samplePos = new BlockPos(sampleX, 64, sampleZ);

                Holder<Biome> biomeHolder = level.getBiome(samplePos);
                String biomeId = biomeHolder.unwrapKey().map(key -> key.location().toString()).orElse("");

                for (String restricted : RESTRICTED_BASE_BIOMES) {
                    if (restricted.equalsIgnoreCase(biomeId)) {
                        restrictedCount++;
                        break;
                    }
                }

                if (restrictedCount > totalCenterSamples / 2) {
                    return false; // Reject if center area is mostly ocean/river
                }
            }
        }

        return restrictedCount <= (totalCenterSamples / 2);
    }

    private boolean isBiomeAllowedFullRegion(ServerLevel level, int regionX, int regionZ, String[] restrictedBiomes) {
        if (level == null) {
            return true;
        }

        int startBlockX = regionX * RegionData.REGION_SIZE_BLOCKS;
        int startBlockZ = regionZ * RegionData.REGION_SIZE_BLOCKS;

        int restrictedCount = 0;
        int totalSamples = 64; // 8x8 chunk centers

        for (int chunkX = 0; chunkX < 8; chunkX++) {
            for (int chunkZ = 0; chunkZ < 8; chunkZ++) {
                int sampleX = startBlockX + (chunkX * 16) + 8;
                int sampleZ = startBlockZ + (chunkZ * 16) + 8;
                BlockPos samplePos = new BlockPos(sampleX, 64, sampleZ);

                Holder<Biome> biomeHolder = level.getBiome(samplePos);
                String biomeId = biomeHolder.unwrapKey().map(key -> key.location().toString()).orElse("");

                for (String restricted : restrictedBiomes) {
                    if (restricted.equalsIgnoreCase(biomeId)) {
                        restrictedCount++;
                        break;
                    }
                }

                if (restrictedCount > totalSamples / 2) {
                    return false;
                }
            }
        }

        return restrictedCount <= (totalSamples / 2);
    }
}
