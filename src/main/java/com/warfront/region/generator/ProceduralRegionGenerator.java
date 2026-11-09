package com.warfront.region.generator;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;

import java.util.ArrayList;
import java.util.Comparator;
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

    public record BasePlacementResult(BaseType baseType, BlockPos anchor) {}

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

    // River biomes strictly forbidden in base placement zones
    public static final String[] RIVER_BIOMES = new String[] {
            "minecraft:river",
            "minecraft:frozen_river"
    };

    // Beach and shore biomes strictly forbidden in base placement zones
    public static final String[] BEACH_BIOMES = new String[] {
            "minecraft:beach",
            "minecraft:snowy_beach",
            "minecraft:stony_shore"
    };

    // Physical base footprint sizes (in blocks)
    public static final int OUTPOST_FOOTPRINT = 32;
    public static final int HQ_WIDTH = 50;
    public static final int HQ_LENGTH = 90;
    public static final int MEGA_FOOTPRINT = 100;

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
    private final Map<Long, RegionData.RegionState> proceduralFullStateCache = new java.util.concurrent.ConcurrentHashMap<>();

    public static Holder<Biome> sampleBiomeFast(ServerLevel level, int blockX, int blockY, int blockZ) {
        return level.getNoiseBiome(blockX >> 2, blockY >> 2, blockZ >> 2);
    }

    public void invalidateFullStateCacheAround(int regionX, int regionZ) {
        proceduralFullStateCache.remove(ChunkPos.asLong(regionX, regionZ));
        proceduralFullStateCache.remove(ChunkPos.asLong(regionX, regionZ - 1));
        proceduralFullStateCache.remove(ChunkPos.asLong(regionX, regionZ + 1));
        proceduralFullStateCache.remove(ChunkPos.asLong(regionX - 1, regionZ));
        proceduralFullStateCache.remove(ChunkPos.asLong(regionX + 1, regionZ));
    }

    private static ServerLevel ensureOverworld(ServerLevel level) {
        if (level != null && level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            if (level.getServer() != null && level.getServer().overworld() != null) {
                return level.getServer().overworld();
            }
        }
        return level;
    }

    /**
     * Evaluates procedural region state for unsaved regions without calculating strength values, preventing recursion loops.
     * Reuses proceduralRawStateCache to make snapshot building instant.
     */
    public RegionData.RegionState generateRawRegionState(ServerLevel level, long worldSeed, int regionX, int regionZ) {
        level = ensureOverworld(level);
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
        level = ensureOverworld(level);
        long key = ChunkPos.asLong(regionX, regionZ);
        if (level != null) {
            RegionData.RegionState cached = proceduralFullStateCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        for (int i = 0; i < factionGenerators.size(); i++) {
            FactionGenerator generator = factionGenerators.get(i);
            Optional<RegionData.RegionState> result = generator.generateRegion(level, worldSeed, regionX, regionZ);

            if (result.isPresent()) {
                RegionData.RegionState state = result.get();
                if (level != null) {
                    proceduralFullStateCache.put(key, state);
                }
                return state;
            }
        }
        RegionData.RegionState unclaimed = new RegionData.RegionState(Faction.UNCLAIMED, 0.0F, 0.0F, BaseType.NONE, 0L);
        if (level != null) {
            proceduralFullStateCache.put(key, unclaimed);
        }
        return unclaimed;
    }

    public boolean biomeAvailable(ServerLevel level, int regionX, int regionZ) {
        return biomeAvailableForBase(level, regionX, regionZ);
    }

    private final Map<Long, Boolean> baseBiomeCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Boolean> expansionBiomeCache = new java.util.concurrent.ConcurrentHashMap<>();
    public record AnchorCacheKey(int regionX, int regionZ, BaseType baseType) {}

    private final Map<Long, Boolean> outpostRiverCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Boolean> mainBaseRiverCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Boolean> megaBaseRiverCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<AnchorCacheKey, Optional<BlockPos>> physicalBaseAnchorCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static long hashPos(long seed, int x, int z, long salt) {
        long h = seed + (long) x * 341873128712L + (long) z * 132897987541L + salt;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }

    public void clearBiomeCache() {
        baseBiomeCache.clear();
        expansionBiomeCache.clear();
        outpostRiverCache.clear();
        mainBaseRiverCache.clear();
        megaBaseRiverCache.clear();
        physicalBaseAnchorCache.clear();
    }

    public void clearAllCaches() {
        proceduralRawStateCache.clear();
        proceduralFullStateCache.clear();
        baseBiomeCache.clear();
        expansionBiomeCache.clear();
        outpostRiverCache.clear();
        mainBaseRiverCache.clear();
        megaBaseRiverCache.clear();
        physicalBaseAnchorCache.clear();
        com.warfront.region.terrain.BiomeElevation.clearCache();
        com.warfront.region.terrain.TerrainClassifier.clearCaches();
        for (FactionGenerator generator : factionGenerators) {
            if (generator instanceof GridClusterFactionGenerator gridGen) {
                gridGen.clearValidityCaches();
            }
        }
    }

    public boolean biomeAvailableForExpansion(ServerLevel level, int regionX, int regionZ) {
        level = ensureOverworld(level);
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
        level = ensureOverworld(level);
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

    /**
     * Checks if a biome holder corresponds to an ocean biome.
     */
    public static boolean isOceanBiome(Holder<Biome> biomeHolder) {
        if (biomeHolder == null) return false;
        if (biomeHolder.is(net.minecraft.tags.BiomeTags.IS_OCEAN)) return true;
        if (biomeHolder.is(net.minecraft.tags.BiomeTags.IS_DEEP_OCEAN)) return true;
        String id = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("");
        for (String ocean : RESTRICTED_BASE_BIOMES) {
            if (ocean.equalsIgnoreCase(id)) return true;
        }
        return id.contains("ocean");
    }

    /**
     * Checks if a biome holder corresponds to a river biome.
     */
    public static boolean isRiverBiome(Holder<Biome> biomeHolder) {
        if (biomeHolder == null) return false;
        if (biomeHolder.is(net.minecraft.tags.BiomeTags.IS_RIVER)) return true;
        String id = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("");
        for (String river : RIVER_BIOMES) {
            if (river.equalsIgnoreCase(id)) return true;
        }
        return id.contains("river");
    }

    /**
     * Checks if a biome holder corresponds to a beach or shore biome.
     */
    public static boolean isBeachBiome(Holder<Biome> biomeHolder) {
        if (biomeHolder == null) return false;
        if (biomeHolder.is(net.minecraft.tags.BiomeTags.IS_BEACH)) return true;
        String id = biomeHolder.unwrapKey().map(k -> k.location().toString()).orElse("");
        for (String beach : BEACH_BIOMES) {
            if (beach.equalsIgnoreCase(id)) return true;
        }
        return id.contains("beach") || id.contains("shore");
    }

    /**
     * Checks whether surface water or fluid exists at the given (x, z) coordinates.
     * Uses chunk fluid lookup when loaded, and 3D noise router getBaseHeight (WORLD_SURFACE_WG vs OCEAN_FLOOR_WG)
     * for unloaded chunks to accurately detect lakes, ponds, and river bends in non-aquatic biomes.
     */
    public static boolean isSurfaceWaterAt(ServerLevel level, int blockX, int blockZ) {
        if (level == null) return false;
        try {
            if (level.hasChunk(blockX >> 4, blockZ >> 4)) {
                int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
                if (surfaceY > level.getMinBuildHeight()) {
                    return !level.getFluidState(new BlockPos(blockX, surfaceY - 1, blockZ)).isEmpty();
                }
                return false;
            }
            // For unloaded chunks: fast O(1) multi-noise biome check
            Holder<Biome> biome = sampleBiomeFast(level, blockX, 64, blockZ);
            if (isOceanBiome(biome) || isRiverBiome(biome) || isBeachBiome(biome)) {
                return true;
            }

            // Query 3D noise router for fluid height differential (WORLD_SURFACE_WG includes fluids; OCEAN_FLOOR_WG stops at solid ground)
            var chunkSource = level.getChunkSource();
            var generator = chunkSource.getGenerator();
            var randomState = chunkSource.randomState();
            int surfaceY = generator.getBaseHeight(blockX, blockZ, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
            int oceanFloorY = generator.getBaseHeight(blockX, blockZ, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
            return surfaceY > oceanFloorY;
        } catch (Exception e) {
            Holder<Biome> biome = sampleBiomeFast(level, blockX, 64, blockZ);
            return isOceanBiome(biome) || isRiverBiome(biome) || isBeachBiome(biome);
        }
    }

    /**
     * Evaluates surface terrain height at the given coordinate.
     */
    public static int getSurfaceHeightAt(ServerLevel level, int blockX, int blockZ) {
        if (level == null) return 64;
        try {
            if (level.hasChunk(blockX >> 4, blockZ >> 4)) {
                return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
            }
            var chunkSource = level.getChunkSource();
            var generator = chunkSource.getGenerator();
            var randomState = chunkSource.randomState();
            return generator.getBaseHeight(blockX, blockZ, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        } catch (Exception e) {
            return 64;
        }
    }

    public record FootprintFitnessResult(boolean valid, double fitness, int anchorY, int deltaY, int waterPoints) {}

    /**
     * Evaluates the placement suitability and fitness score of a candidate base footprint.
     * Hard vetoes:
     * - Center block MUST be solid ground (not surface water, river, or ocean).
     * - Deep ocean biomes are strictly banned across the entire footprint.
     * - Vertical clearance within world bounds (anchorY + 25 < maxBuildHeight - 10, anchorY >= minBuildHeight + 10).
     * - Submerged ratio <= 35% (rejects predominantly sunken candidates).
     *
     * Fitness score:
     * - Heavy penalty for slope/elevation variance (Delta Y): prevents carving mountains and casting high foundation pillars.
     * - Moderate penalty for water points: prefers dry ground without rejecting regions with minor ponds or river bends.
     * - Terrain bonuses for naturally flat biomes (Plains, Savanna, Desert, Meadow).
     */
    public FootprintFitnessResult evaluateFootprintFitness(ServerLevel level, int minBlockX, int minBlockZ, int sizeX, int sizeZ) {
        if (level == null) {
            return new FootprintFitnessResult(true, 0.0, 64, 0, 0);
        }

        int centerBlockX = minBlockX + sizeX / 2;
        int centerBlockZ = minBlockZ + sizeZ / 2;

        // 1. Center of the base structure MUST be dry land and not ocean, river, beach, or surface water
        Holder<Biome> centerBiome = sampleBiomeFast(level, centerBlockX, 64, centerBlockZ);
        if (isOceanBiome(centerBiome) || isRiverBiome(centerBiome) || isBeachBiome(centerBiome) || isSurfaceWaterAt(level, centerBlockX, centerBlockZ)) {
            return new FootprintFitnessResult(false, Double.NEGATIVE_INFINITY, 64, 0, 0);
        }

        // 2. Vertical build space check
        int anchorY = getSurfaceHeightAt(level, centerBlockX, centerBlockZ);
        if (anchorY + 25 >= level.getMaxBuildHeight() - 10 || anchorY < level.getMinBuildHeight() + 10) {
            return new FootprintFitnessResult(false, Double.NEGATIVE_INFINITY, anchorY, 0, 0);
        }

        // 3. Perimeter corners fluid check: prevents base from straddling onto lakes, bays, or ocean inlets
        int maxX = minBlockX + sizeX - 1;
        int maxZ = minBlockZ + sizeZ - 1;
        int cornerWaterCount = 0;
        int[][] corners = new int[][] {
                { minBlockX, minBlockZ },
                { maxX, minBlockZ },
                { minBlockX, maxZ },
                { maxX, maxZ }
        };
        for (int[] c : corners) {
            Holder<Biome> cornerBiome = sampleBiomeFast(level, c[0], 64, c[1]);
            if (isOceanBiome(cornerBiome) || isRiverBiome(cornerBiome) || isBeachBiome(cornerBiome) || isSurfaceWaterAt(level, c[0], c[1])) {
                cornerWaterCount++;
            }
        }
        boolean isMega = Math.max(sizeX, sizeZ) >= 100;
        // Outposts & HQs veto if >= 2 corners are water.
        // Mega Bases (100x100) allow landfilling small rivers across 2 corners, but strictly veto whole lakes (>= 3 corners or any ocean corner)
        if (isMega) {
            if (cornerWaterCount >= 3) {
                return new FootprintFitnessResult(false, Double.NEGATIVE_INFINITY, anchorY, 0, cornerWaterCount);
            }
            if (cornerWaterCount >= 2) {
                int oceanCorners = 0;
                for (int[] c : corners) {
                    Holder<Biome> cb = sampleBiomeFast(level, c[0], 64, c[1]);
                    if (isOceanBiome(cb) || isBeachBiome(cb)) oceanCorners++;
                }
                if (oceanCorners > 0) {
                    return new FootprintFitnessResult(false, Double.NEGATIVE_INFINITY, anchorY, 0, cornerWaterCount);
                }
            }
        } else if (cornerWaterCount >= 2) {
            return new FootprintFitnessResult(false, Double.NEGATIVE_INFINITY, anchorY, 0, cornerWaterCount);
        }

        int step = (Math.max(sizeX, sizeZ) <= 32) ? 8 : 10;
        int totalSamples = 0;
        int waterSamples = cornerWaterCount;

        for (int ox = 0; ox < sizeX; ox += step) {
            for (int oz = 0; oz < sizeZ; oz += step) {
                int x = minBlockX + ox;
                int z = minBlockZ + oz;
                totalSamples++;

                Holder<Biome> biome = sampleBiomeFast(level, x, 64, z);
                if (isOceanBiome(biome)) {
                    String id = biome.unwrapKey().map(k -> k.location().toString()).orElse("");
                    if (id.contains("deep_ocean")) {
                        return new FootprintFitnessResult(false, Double.NEGATIVE_INFINITY, anchorY, 0, 0);
                    }
                }

                // Interior points use O(1) multi-noise biome check to preserve high performance
                if (isOceanBiome(biome) || isRiverBiome(biome) || isBeachBiome(biome)) {
                    waterSamples++;
                }
            }
        }

        // Reject if submerged ratio exceeds threshold:
        // Outposts (32x32) must be strictly on dry land (<= 10% water)
        // HQ (50x90) allows at most 15% water
        // Mega Bases (100x100) allow up to 20% water, permitting small rivers in plains to be landfilled with foundations
        double maxWaterRatio;
        if (Math.max(sizeX, sizeZ) <= 32) {
            maxWaterRatio = 0.10;
        } else if (Math.max(sizeX, sizeZ) <= 90) {
            maxWaterRatio = 0.15;
        } else {
            maxWaterRatio = 0.20;
        }
        if (totalSamples > 0 && ((double) waterSamples / totalSamples) > maxWaterRatio) {
            return new FootprintFitnessResult(false, Double.NEGATIVE_INFINITY, anchorY, 0, waterSamples);
        }

        // True 3D noise surface elevation variance across 9 strategic points (center + 4 corners + 4 edge midpoints)
        int minY = anchorY;
        int maxY = anchorY;
        int[] samplePointsX = new int[] {
                minBlockX, maxX, minBlockX, maxX,             // 4 corners
                centerBlockX, centerBlockX, minBlockX, maxX   // 4 edge midpoints
        };
        int[] samplePointsZ = new int[] {
                minBlockZ, minBlockZ, maxZ, maxZ,             // 4 corners
                minBlockZ, maxZ, centerBlockZ, centerBlockZ   // 4 edge midpoints
        };
        for (int i = 0; i < 8; i++) {
            int h = getSurfaceHeightAt(level, samplePointsX[i], samplePointsZ[i]);
            if (h < minY) minY = h;
            if (h > maxY) maxY = h;
        }

        int deltaY = Math.max(0, maxY - minY);

        // Terrain bonus for naturally flat biomes
        com.warfront.region.terrain.TerrainType terrain = com.warfront.region.terrain.TerrainType.fromBiome(centerBiome);
        int terrainBonus = switch (terrain) {
            case PLAINS, SAVANNA, DESERT -> 20;
            case HILLS, BADLANDS -> 5;
            case SWAMP, JUNGLE -> -10;
            case MOUNTAIN, TUNDRA -> -30;
            default -> 0;
        };

        // Option 1A: Soft nonlinear slope penalty (moderate for natural undulation, steep quadratic penalty for mountain slopes)
        double slopePenalty;
        if (deltaY <= 5) {
            slopePenalty = deltaY * 8.0;
        } else {
            slopePenalty = (deltaY * 8.0) + Math.pow(deltaY - 5, 2) * 4.0;
        }

        // Water penalty (40.0 for Mega Base to allow landfilling small rivers; 120.0 for Outpost/HQ)
        double waterPenalty = isMega ? (waterSamples * 40.0) : (waterSamples * 120.0);
        double fitness = - waterPenalty - slopePenalty + terrainBonus;
        return new FootprintFitnessResult(true, fitness, anchorY, deltaY, waterSamples);
    }

    public boolean isFootprintSuitable(ServerLevel level, int minBlockX, int minBlockZ, int sizeBlocks) {
        return evaluateFootprintFitness(level, minBlockX, minBlockZ, sizeBlocks, sizeBlocks).valid();
    }

    public boolean isFootprintSuitable(ServerLevel level, int minBlockX, int minBlockZ, int sizeX, int sizeZ) {
        return evaluateFootprintFitness(level, minBlockX, minBlockZ, sizeX, sizeZ).valid();
    }

    public int evaluateHQFootprintDrop(ServerLevel level, int minBlockX, int minBlockZ, int sizeX, int sizeZ) {
        FootprintFitnessResult res = evaluateFootprintFitness(level, minBlockX, minBlockZ, sizeX, sizeZ);
        return res.valid() ? res.deltaY() : -1;
    }

    /**
     * Finds a deterministic physical base anchor within the strategic region for the given base tier.
     *
     * Placement rules:
     * - OUTPOST: Single size variant (32x32 footprint). Allocated into a random subregion (0..3).
     *   First tries to fit into a valid subregion without shifts. Only applies shifting logic if all 4 subregions fail.
     * - HEADQUARTERS (Medium Base): 50x90 footprint. Centered across 2 adjacent subregions with zero overflow to the other 2 (7-block lateral margins).
     *   Tries 4 cardinal orientations straddling subregion borders (East, West, North, South).
     *   Fallback shifting moves strictly along the middle line axis (1D shift, never diagonal).
     * - MEGA_BASE: 64x64 footprint. Sits right in the middle (startBlockX + 64, startBlockZ + 64) and occupies all 4 subregions
     *   as a huge central building. Fallback slight 1D shifts along middle axes while still occupying all 4 subregions.
     */
    public Optional<BlockPos> findPhysicalBaseAnchor(ServerLevel level, long worldSeed, int regionX, int regionZ, BaseType baseType) {
        level = ensureOverworld(level);
        if (baseType == BaseType.NONE) {
            return Optional.empty();
        }

        AnchorCacheKey key = new AnchorCacheKey(regionX, regionZ, baseType);
        Optional<BlockPos> cached = physicalBaseAnchorCache.get(key);
        if (cached != null) {
            return cached;
        }

        if (level == null) {
            BlockPos fallback = new BlockPos(regionX * RegionData.REGION_SIZE_BLOCKS + 64, 64, regionZ * RegionData.REGION_SIZE_BLOCKS + 64);
            Optional<BlockPos> opt = Optional.of(fallback);
            physicalBaseAnchorCache.put(key, opt);
            return opt;
        }

        int startBlockX = regionX * RegionData.REGION_SIZE_BLOCKS;
        int startBlockZ = regionZ * RegionData.REGION_SIZE_BLOCKS;

        if (baseType == BaseType.MEGA_BASE) {
            // Megabases: 100x100 footprint centered in region, straddling all 4 subregions
            // Zero-shift: occupies ~61% of region area ([14..114]), canonically centered
            int candMinX = startBlockX + 14;
            int candMinZ = startBlockZ + 14;

            FootprintFitnessResult fit = evaluateFootprintFitness(level, candMinX, candMinZ, MEGA_FOOTPRINT, MEGA_FOOTPRINT);
            if (fit.valid()) {
                BlockPos anchor = new BlockPos(startBlockX + 64, fit.anchorY(), startBlockZ + 64);
                Optional<BlockPos> res = Optional.of(anchor);
                physicalBaseAnchorCache.put(key, res);
                return res;
            }

            physicalBaseAnchorCache.put(key, Optional.empty());
            return Optional.empty();
        } else if (baseType == BaseType.HEADQUARTERS) {
            // Headquarters (Medium Base): 52x93 or 93x52 footprint centered across 2 adjacent subregions
            int[][] hqConfigs = new int[][] {
                    { 96, 64, 0 }, // 0: EAST  - Subregions (1,0) & (1,1)
                    { 32, 64, 0 }, // 1: WEST  - Subregions (0,0) & (0,1)
                    { 64, 32, 1 }, // 2: NORTH - Subregions (0,0) & (1,0)
                    { 64, 96, 1 }  // 3: SOUTH - Subregions (0,1) & (1,1)
            };

            int[] axisDeltas = new int[] { 0, -5, 5, -10, 10, -15, 15 };

            long hash = hashPos(worldSeed, regionX, regionZ, 8888L);
            int startCard = (int) Math.floorMod(hash, 4);

            double bestFitness = Double.NEGATIVE_INFINITY;
            BlockPos bestAnchor = null;

            for (int i = 0; i < 4; i++) {
                int cardIdx = (startCard + i) % 4;
                int baseAnchorX = startBlockX + hqConfigs[cardIdx][0];
                int baseAnchorZ = startBlockZ + hqConfigs[cardIdx][1];
                int shiftAxis = hqConfigs[cardIdx][2]; // 0 = shift in Z (52x93), 1 = shift in X (93x52)

                int sizeX = (shiftAxis == 0) ? 52 : 93;
                int sizeZ = (shiftAxis == 0) ? 93 : 52;

                for (int delta : axisDeltas) {
                    int candAnchorX = baseAnchorX + (shiftAxis == 1 ? delta : 0);
                    int candAnchorZ = baseAnchorZ + (shiftAxis == 0 ? delta : 0);
                    int candMinX = candAnchorX - sizeX / 2;
                    int candMinZ = candAnchorZ - sizeZ / 2;

                    if (candMinX >= startBlockX + 4 && candMinX + sizeX <= startBlockX + 124 &&
                            candMinZ >= startBlockZ + 4 && candMinZ + sizeZ <= startBlockZ + 124) {
                        FootprintFitnessResult fit = evaluateFootprintFitness(level, candMinX, candMinZ, sizeX, sizeZ);
                        double tieBreaker = (delta == 0) ? 0.5 : 0.0;
                        if (fit.valid() && (fit.fitness() + tieBreaker) > bestFitness) {
                            bestFitness = fit.fitness() + tieBreaker;
                            bestAnchor = new BlockPos(candAnchorX, fit.anchorY(), candAnchorZ);
                        }
                    }
                }
            }

            if (bestAnchor != null) {
                Optional<BlockPos> res = Optional.of(bestAnchor);
                physicalBaseAnchorCache.put(key, res);
                return res;
            }
        } else if (baseType == BaseType.OUTPOST) {
            // Outpost: 32x32 footprint. Option 3A evaluates all 4 subregions with center and 4 quadrant shifts (20 candidates)
            int[][] subOffsets = new int[][] {
                    { 0, 0 },   // Subregion 0 (NW)
                    { 64, 0 },  // Subregion 1 (NE)
                    { 0, 64 },  // Subregion 2 (SW)
                    { 64, 64 }  // Subregion 3 (SE)
            };

            // Shifts within the 64x64 subregion: ideal center (16, 16) + 4 quadrant shifts
            int[][] shifts = new int[][] {
                    { 16, 16 }, // Center
                    { 8, 8 },   // NW quad
                    { 24, 8 },  // NE quad
                    { 8, 24 },  // SW quad
                    { 24, 24 }  // SE quad
            };

            long hash = hashPos(worldSeed, regionX, regionZ, 7777L);
            int startSub = (int) Math.floorMod(hash, 4);

            double bestFitness = Double.NEGATIVE_INFINITY;
            BlockPos bestAnchor = null;

            for (int i = 0; i < 4; i++) {
                int subIdx = (startSub + i) % 4;
                int subMinX = startBlockX + subOffsets[subIdx][0];
                int subMinZ = startBlockZ + subOffsets[subIdx][1];

                for (int[] s : shifts) {
                    int candMinX = subMinX + s[0];
                    int candMinZ = subMinZ + s[1];

                    FootprintFitnessResult fit = evaluateFootprintFitness(level, candMinX, candMinZ, OUTPOST_FOOTPRINT, OUTPOST_FOOTPRINT);
                    double tieBreaker = (s[0] == 16 && s[1] == 16) ? 0.5 : 0.0;
                    if (fit.valid() && (fit.fitness() + tieBreaker) > bestFitness) {
                        bestFitness = fit.fitness() + tieBreaker;
                        bestAnchor = new BlockPos(candMinX + OUTPOST_FOOTPRINT / 2, fit.anchorY(), candMinZ + OUTPOST_FOOTPRINT / 2);
                    }
                }
            }

            if (bestAnchor != null) {
                Optional<BlockPos> res = Optional.of(bestAnchor);
                physicalBaseAnchorCache.put(key, res);
                return res;
            }
        }

        Optional<BlockPos> empty = Optional.empty();
        physicalBaseAnchorCache.put(key, empty);
        return empty;
    }

    /**
     * Resolves the physical base placement for a region given a desired base tier.
     * Strictly preserves the requested base tier: if terrain cannot fit the structure,
     * cleanly assigns BaseType.NONE without cascading down to lower base tiers.
     */
    public BasePlacementResult resolveBasePlacement(ServerLevel level, long worldSeed, int regionX, int regionZ, BaseType desiredTier) {
        level = ensureOverworld(level);
        if (desiredTier == BaseType.NONE) {
            return new BasePlacementResult(BaseType.NONE, null);
        }

        Optional<BlockPos> anchor = findPhysicalBaseAnchor(level, worldSeed, regionX, regionZ, desiredTier);
        if (anchor.isPresent()) {
            return new BasePlacementResult(desiredTier, anchor.get());
        }

        return new BasePlacementResult(BaseType.NONE, null);
    }

    /**
     * Convenience method to retrieve the physical base anchor for an existing region.
     */
    public Optional<BlockPos> getPhysicalBaseAnchor(ServerLevel level, long worldSeed, int regionX, int regionZ, BaseType baseType) {
        return findPhysicalBaseAnchor(level, worldSeed, regionX, regionZ, baseType);
    }

    /**
     * Scans the specified chunk index range [minChunk, maxChunk] in the region for any river biomes.
     * Gracefully short-circuits and returns true on the very first river chunk detected.
     */
    public boolean hasRiverInChunkRange(ServerLevel level, int regionX, int regionZ, int minChunk, int maxChunk) {
        if (level == null) return false;

        int startBlockX = regionX * RegionData.REGION_SIZE_BLOCKS;
        int startBlockZ = regionZ * RegionData.REGION_SIZE_BLOCKS;

        for (int chunkX = minChunk; chunkX <= maxChunk; chunkX++) {
            for (int chunkZ = minChunk; chunkZ <= maxChunk; chunkZ++) {
                int sampleX = startBlockX + (chunkX * 16) + 8;
                int sampleZ = startBlockZ + (chunkZ * 16) + 8;

                Holder<Biome> biomeHolder = sampleBiomeFast(level, sampleX, 64, sampleZ);
                if (isRiverBiome(biomeHolder)) {
                    return true; // Graceful early break on first detected river chunk!
                }
            }
        }

        return false;
    }

    /**
     * Outpost river restriction: Checks the 4x4 center chunks (chunks 2..5 in X and Z).
     * Any river chunk triggers disqualification.
     */
    public boolean hasRiverForOutpost(ServerLevel level, int regionX, int regionZ) {
        if (level == null) return false;
        long key = ChunkPos.asLong(regionX, regionZ);
        Boolean cached = outpostRiverCache.get(key);
        if (cached != null) return cached;

        boolean result = hasRiverInChunkRange(level, regionX, regionZ, 2, 5);
        outpostRiverCache.put(key, result);
        return result;
    }

    /**
     * Main Base (Headquarters) river restriction: Checks the 6x6 center chunks (chunks 1..6 in X and Z).
     * Any river chunk triggers disqualification.
     */
    public boolean hasRiverForMainBase(ServerLevel level, int regionX, int regionZ) {
        if (level == null) return false;
        long key = ChunkPos.asLong(regionX, regionZ);
        Boolean cached = mainBaseRiverCache.get(key);
        if (cached != null) return cached;

        boolean result = hasRiverInChunkRange(level, regionX, regionZ, 1, 6);
        mainBaseRiverCache.put(key, result);
        return result;
    }

    /**
     * Mega Base river restriction: Total river ban across all 8x8 chunks (chunks 0..7 in X and Z).
     * A single river chunk anywhere disqualifies the mega base.
     */
    public boolean hasRiverForMegaBase(ServerLevel level, int regionX, int regionZ) {
        if (level == null) return false;
        long key = ChunkPos.asLong(regionX, regionZ);
        Boolean cached = megaBaseRiverCache.get(key);
        if (cached != null) return cached;

        boolean result = hasRiverInChunkRange(level, regionX, regionZ, 0, 7);
        megaBaseRiverCache.put(key, result);
        return result;
    }

    public boolean isLandRegion(ServerLevel level, int regionX, int regionZ) {
        level = ensureOverworld(level);
        if (level == null) {
            return true;
        }

        int startBlockX = regionX * RegionData.REGION_SIZE_BLOCKS;
        int startBlockZ = regionZ * RegionData.REGION_SIZE_BLOCKS;

        int waterCount = 0;
        int totalCenterSamples = 16; // 4x4 inner chunks

        for (int chunkX = 2; chunkX <= 5; chunkX++) {
            for (int chunkZ = 2; chunkZ <= 5; chunkZ++) {
                int sampleX = startBlockX + (chunkX * 16) + 8;
                int sampleZ = startBlockZ + (chunkZ * 16) + 8;

                Holder<Biome> biomeHolder = sampleBiomeFast(level, sampleX, 64, sampleZ);
                if (isOceanBiome(biomeHolder) || isRiverBiome(biomeHolder) || isBeachBiome(biomeHolder)) {
                    waterCount++;
                }

                if (waterCount > totalCenterSamples / 2) {
                    return false; // Majority (>50%) of region center is ocean or river -> reject
                }
            }
        }

        return waterCount <= (totalCenterSamples / 2);
    }
}
