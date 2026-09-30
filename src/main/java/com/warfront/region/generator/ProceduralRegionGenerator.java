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
    // Biomes where NO base or territory can generate (Oceans)
    public static final String[] RESTRICTED_BASE_BIOMES = new String[] {
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

    public static final String[] RESTRICTED_EXPANSION_BIOMES = RESTRICTED_BASE_BIOMES;

    // 1. FACTION_BUFFER_DISTANCE: Minimum number of unclaimed regions required between different faction borders.
    public static final int FACTION_BUFFER_DISTANCE = 2;

    private final List<FactionGenerator> factionGenerators = new ArrayList<>();

    public ProceduralRegionGenerator() {
        // --- PILLAGER CONQUERORS GENERATOR (Priority 0) ---
        registerGenerator(new GridClusterFactionGenerator(
                Faction.PILLAGER_CONQUERORS,
                new FactionGeneratorConfig(
                        16,
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
                        16,
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
                    RegionData.RegionState state = result.get();
                    if (level != null) {
                        proceduralRawStateCache.put(key, state);
                    }
                    return state;
                }
            }
        }
        RegionData.RegionState unclaimed = new RegionData.RegionState(Faction.UNCLAIMED, 0.0F, 0.0F, BaseType.NONE, 0L);
        if (level != null) {
            proceduralRawStateCache.put(key, unclaimed);
        }
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
                return result.get();
            }
        }
        return new RegionData.RegionState(Faction.UNCLAIMED, 0.0F, 0.0F, BaseType.NONE, 0L);
    }

    public boolean biomeAvailable(ServerLevel level, int regionX, int regionZ) {
        return biomeAvailableForBase(level, regionX, regionZ);
    }

    private final Map<Long, Boolean> baseBiomeCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Boolean> expansionBiomeCache = new java.util.concurrent.ConcurrentHashMap<>();

    public void clearBiomeCache() {
        baseBiomeCache.clear();
        expansionBiomeCache.clear();
    }

    public void clearAllCaches() {
        proceduralRawStateCache.clear();
        baseBiomeCache.clear();
        expansionBiomeCache.clear();
        for (FactionGenerator generator : factionGenerators) {
            if (generator instanceof GridClusterFactionGenerator gridGen) {
                gridGen.clearValidityCaches();
            }
        }
    }

    public boolean biomeAvailableForExpansion(ServerLevel level, int regionX, int regionZ) {
        if (level == null) {
            return true;
        }
        long key = ChunkPos.asLong(regionX, regionZ);
        Boolean cached = expansionBiomeCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = isLandRegion(level, regionX, regionZ);
        expansionBiomeCache.put(key, result);
        return result;
    }

    public boolean biomeAvailableForBase(ServerLevel level, int regionX, int regionZ) {
        if (level == null) {
            return true;
        }
        long key = ChunkPos.asLong(regionX, regionZ);
        Boolean cached = baseBiomeCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean result = isLandRegion(level, regionX, regionZ);
        baseBiomeCache.put(key, result);
        return result;
    }

    public boolean isLandRegion(ServerLevel level, int regionX, int regionZ) {
        if (level == null) {
            return true;
        }

        int startBlockX = regionX * RegionData.REGION_SIZE_BLOCKS;
        int startBlockZ = regionZ * RegionData.REGION_SIZE_BLOCKS;

        int oceanCount = 0;
        int totalCenterSamples = 16; // 4x4 inner chunks

        for (int chunkX = 2; chunkX <= 5; chunkX++) {
            for (int chunkZ = 2; chunkZ <= 5; chunkZ++) {
                int sampleX = startBlockX + (chunkX * 16) + 8;
                int sampleZ = startBlockZ + (chunkZ * 16) + 8;
                BlockPos samplePos = new BlockPos(sampleX, 64, sampleZ);

                Holder<Biome> biomeHolder = level.getBiome(samplePos);
                String biomeId = biomeHolder.unwrapKey().map(key -> key.location().toString()).orElse("");

                for (String ocean : RESTRICTED_BASE_BIOMES) {
                    if (ocean.equalsIgnoreCase(biomeId)) {
                        oceanCount++;
                        break;
                    }
                }

                if (oceanCount > totalCenterSamples / 2) {
                    return false; // Majority of region center is ocean -> reject
                }
            }
        }

        return oceanCount <= (totalCenterSamples / 2);
    }
}
