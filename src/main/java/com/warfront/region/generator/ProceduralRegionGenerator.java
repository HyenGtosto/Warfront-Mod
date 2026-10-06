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
    public static final int HQ_FOOTPRINT = 48;
    public static final int MEGA_FOOTPRINT = 64;

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
        baseBiomeCache.clear();
        expansionBiomeCache.clear();
        outpostRiverCache.clear();
        mainBaseRiverCache.clear();
        megaBaseRiverCache.clear();
        physicalBaseAnchorCache.clear();
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
     * Uses fast biome lookup for unloaded chunks to prevent expensive 3D noise router getBaseHeight evaluations.
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
            // For unloaded chunks: fast O(1) multi-noise biome check avoids heavy 3D noise router getBaseHeight evaluations
            Holder<Biome> biome = level.getBiome(new BlockPos(blockX, 64, blockZ));
            return isOceanBiome(biome) || isRiverBiome(biome) || isBeachBiome(biome);
        } catch (Exception e) {
            Holder<Biome> biome = level.getBiome(new BlockPos(blockX, 64, blockZ));
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

    /**
     * Validates whether a physical base footprint can be placed at [minBlockX, minBlockZ] with size sizeBlocks.
     * Uses a dense, deterministic sample grid tailored to the footprint size to prevent rivers from going undetected.
     * Checks that the center is solid land (never water, ocean, or river) and no more than 20% of the footprint is water.
     */
    /**
     * Validates whether a physical base footprint can be placed at [minBlockX, minBlockZ] with size sizeBlocks.
     * Uses a dense, deterministic sample grid tailored to the footprint size.
     * ZERO TOLERANCE: Any sample point that touches ocean, river, beach/shore, or surface water
     * immediately disqualifies the candidate location.
     */
    public boolean isFootprintSuitable(ServerLevel level, int minBlockX, int minBlockZ, int sizeBlocks) {
        if (level == null) return true;

        int halfSize = sizeBlocks / 2;
        int centerBlockX = minBlockX + halfSize;
        int centerBlockZ = minBlockZ + halfSize;

        // 1. Center of the base structure MUST be dry land and not ocean, river, beach, or surface water
        Holder<Biome> centerBiome = level.getBiome(new BlockPos(centerBlockX, 64, centerBlockZ));
        if (isOceanBiome(centerBiome) || isRiverBiome(centerBiome) || isBeachBiome(centerBiome) || isSurfaceWaterAt(level, centerBlockX, centerBlockZ)) {
            return false;
        }

        // 2. 4 corners check
        int[][] corners = new int[][] {
                { minBlockX, minBlockZ },
                { minBlockX + sizeBlocks - 1, minBlockZ },
                { minBlockX, minBlockZ + sizeBlocks - 1 },
                { minBlockX + sizeBlocks - 1, minBlockZ + sizeBlocks - 1 }
        };
        for (int[] c : corners) {
            Holder<Biome> cornerBiome = level.getBiome(new BlockPos(c[0], 64, c[1]));
            if (isOceanBiome(cornerBiome) || isRiverBiome(cornerBiome) || isBeachBiome(cornerBiome) || isSurfaceWaterAt(level, c[0], c[1])) {
                return false;
            }
        }

        // 3. Dense deterministic grid of sample points across interior and perimeter
        int step = (sizeBlocks <= 32) ? 6 : 8;

        for (int ox = 0; ox < sizeBlocks; ox += step) {
            for (int oz = 0; oz < sizeBlocks; oz += step) {
                int x = minBlockX + ox;
                int z = minBlockZ + oz;

                if (x == centerBlockX && z == centerBlockZ) {
                    continue; // Already verified center
                }

                Holder<Biome> biome = level.getBiome(new BlockPos(x, 64, z));
                if (isOceanBiome(biome) || isRiverBiome(biome) || isBeachBiome(biome) || isSurfaceWaterAt(level, x, z)) {
                    return false; // Zero tolerance: ANY water, river, beach, or ocean point rejects candidate!
                }
            }
        }

        return true;
    }

    /**
     * Finds a deterministic physical base anchor within the strategic region for the given base tier.
     *
     * Placement rules:
     * - OUTPOST: Single size variant (32x32 footprint). Allocated into a random subregion (0..3).
     *   First tries to fit into a valid subregion without shifts. Only applies shifting logic if all 4 subregions fail.
     * - HEADQUARTERS (Normal Base): 48x48 footprint. Occupies parts of 2 adjacent subregions with slight overflow to the other 2.
     *   Tries 4 cardinal orientations straddling subregion borders (East, West, North, South).
     *   Fallback shifting moves strictly along the middle line axis (1D shift, never diagonal).
     * - MEGA_BASE: 64x64 footprint. Sits right in the middle (startBlockX + 64, startBlockZ + 64) and occupies all 4 subregions
     *   as a huge central building. Fallback slight 1D shifts along middle axes while still occupying all 4 subregions.
     */
    public Optional<BlockPos> findPhysicalBaseAnchor(ServerLevel level, long worldSeed, int regionX, int regionZ, BaseType baseType) {
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
            // Megabases: sit right in the middle and occupy all 4 subregions (64x64 footprint)
            int candMinX = startBlockX + 32;
            int candMinZ = startBlockZ + 32;

            // Phase 1: Test exact center
            if (isFootprintSuitable(level, candMinX, candMinZ, MEGA_FOOTPRINT)) {
                int anchorX = startBlockX + 64;
                int anchorZ = startBlockZ + 64;
                int anchorY = getSurfaceHeightAt(level, anchorX, anchorZ);
                Optional<BlockPos> res = Optional.of(new BlockPos(anchorX, anchorY, anchorZ));
                physicalBaseAnchorCache.put(key, res);
                return res;
            }

            // Phase 2: Apply 1D shifting logic along the middle axes while still occupying all 4 subregions (never diagonal)
            int[][] megaShifts = new int[][] {
                    { -6, 0 }, { 6, 0 }, { 0, -6 }, { 0, 6 },
                    { -12, 0 }, { 12, 0 }, { 0, -12 }, { 0, 12 },
                    { -16, 0 }, { 16, 0 }, { 0, -16 }, { 0, 16 }
            };
            for (int[] shift : megaShifts) {
                int shiftedMinX = candMinX + shift[0];
                int shiftedMinZ = candMinZ + shift[1];
                // Still straddles both X=startBlockX+64 and Z=startBlockZ+64 (occupies all 4 subregions)
                if (shiftedMinX < startBlockX + 64 && shiftedMinX + MEGA_FOOTPRINT > startBlockX + 64 &&
                        shiftedMinZ < startBlockZ + 64 && shiftedMinZ + MEGA_FOOTPRINT > startBlockZ + 64) {
                    if (isFootprintSuitable(level, shiftedMinX, shiftedMinZ, MEGA_FOOTPRINT)) {
                        int anchorX = shiftedMinX + 32;
                        int anchorZ = shiftedMinZ + 32;
                        int anchorY = getSurfaceHeightAt(level, anchorX, anchorZ);
                        Optional<BlockPos> res = Optional.of(new BlockPos(anchorX, anchorY, anchorZ));
                        physicalBaseAnchorCache.put(key, res);
                        return res;
                    }
                }
            }
        } else if (baseType == BaseType.HEADQUARTERS) {
            // Normal bases (HQ): 48x48 footprint, occupy parts of 2 adjacent subregions with slight overflow to other 2.
            // 4 cardinal orientations:
            // 0: EAST - Subregions (subX=1, subZ=0) & (subX=1, subZ=1) mandatory (36 blocks wide); (0,0) & (0,1) overflow (12 blocks). Shift along Z (shiftAxis=0).
            // 1: WEST - Subregions (subX=0, subZ=0) & (subX=0, subZ=1) mandatory (36 blocks wide); (1,0) & (1,1) overflow (12 blocks). Shift along Z (shiftAxis=0).
            // 2: NORTH - Subregions (subX=0, subZ=0) & (subX=1, subZ=0) mandatory (36 blocks tall); (0,1) & (1,1) overflow (12 blocks). Shift along X (shiftAxis=1).
            // 3: SOUTH - Subregions (subX=0, subZ=1) & (subX=1, subZ=1) mandatory (36 blocks tall); (0,0) & (1,0) overflow (12 blocks). Shift along X (shiftAxis=1).
            int[][] hqConfigs = new int[][] {
                    { 76, 64, 0 }, // 0: EAST
                    { 52, 64, 0 }, // 1: WEST
                    { 64, 52, 1 }, // 2: NORTH
                    { 64, 76, 1 }  // 3: SOUTH
            };

            long hash = hashPos(worldSeed, regionX, regionZ, 8888L);
            int startCard = (int) Math.floorMod(hash, 4);
            int[] cardOrder = new int[] { startCard, (startCard + 1) % 4, (startCard + 2) % 4, (startCard + 3) % 4 };

            // Phase 1: Test ideal positions of the 4 cardinal orientations
            for (int cardIdx : cardOrder) {
                int candAnchorX = startBlockX + hqConfigs[cardIdx][0];
                int candAnchorZ = startBlockZ + hqConfigs[cardIdx][1];
                int candMinX = candAnchorX - 24;
                int candMinZ = candAnchorZ - 24;

                if (isFootprintSuitable(level, candMinX, candMinZ, HQ_FOOTPRINT)) {
                    int anchorY = getSurfaceHeightAt(level, candAnchorX, candAnchorZ);
                    Optional<BlockPos> res = Optional.of(new BlockPos(candAnchorX, anchorY, candAnchorZ));
                    physicalBaseAnchorCache.put(key, res);
                    return res;
                }
            }

            // Phase 2: Apply 1D shifting strictly along the middle line axis (never diagonal)
            int[] axisDeltas = new int[] { -6, 6, -12, 12, -18, 18, -24, 24 };

            for (int cardIdx : cardOrder) {
                int baseAnchorX = startBlockX + hqConfigs[cardIdx][0];
                int baseAnchorZ = startBlockZ + hqConfigs[cardIdx][1];
                int shiftAxis = hqConfigs[cardIdx][2]; // 0 = shift in Z, 1 = shift in X

                for (int delta : axisDeltas) {
                    int candAnchorX = baseAnchorX + (shiftAxis == 1 ? delta : 0);
                    int candAnchorZ = baseAnchorZ + (shiftAxis == 0 ? delta : 0);
                    int candMinX = candAnchorX - 24;
                    int candMinZ = candAnchorZ - 24;

                    if (candMinX >= startBlockX + 8 && candMinX + HQ_FOOTPRINT <= startBlockX + 120 &&
                            candMinZ >= startBlockZ + 8 && candMinZ + HQ_FOOTPRINT <= startBlockZ + 120) {
                        if (isFootprintSuitable(level, candMinX, candMinZ, HQ_FOOTPRINT)) {
                            int anchorY = getSurfaceHeightAt(level, candAnchorX, candAnchorZ);
                            Optional<BlockPos> res = Optional.of(new BlockPos(candAnchorX, anchorY, candAnchorZ));
                            physicalBaseAnchorCache.put(key, res);
                            return res;
                        }
                    }
                }
            }
        } else if (baseType == BaseType.OUTPOST) {
            // Outpost: single size variant (32x32 footprint).
            // Allocate into a random subregion, first fit into a valid subregion, then apply shifting if all 4 fail.
            long hash = hashPos(worldSeed, regionX, regionZ, 7777L);
            int startSub = (int) Math.floorMod(hash, 4);
            int[] subOrder = new int[] { startSub, (startSub + 1) % 4, (startSub + 2) % 4, (startSub + 3) % 4 };

            // 4 subregions (64x64 blocks each) using standard (subX, subZ) inner coordinates:
            // 0: (subX=0, subZ=0) -> [0, 0]
            // 1: (subX=1, subZ=0) -> [64, 0]
            // 2: (subX=0, subZ=1) -> [0, 64]
            // 3: (subX=1, subZ=1) -> [64, 64]
            int[][] subOffsets = new int[][] {
                    { 0, 0 },   // subX = 0, subZ = 0
                    { 64, 0 },  // subX = 1, subZ = 0
                    { 0, 64 },  // subX = 0, subZ = 1
                    { 64, 64 }  // subX = 1, subZ = 1
            };

            // Phase 1: First fit into a valid subregion (ideal center of each subregion)
            for (int subIdx : subOrder) {
                int subMinX = startBlockX + subOffsets[subIdx][0];
                int subMinZ = startBlockZ + subOffsets[subIdx][1];
                int candMinX = subMinX + 16;
                int candMinZ = subMinZ + 16;

                if (isFootprintSuitable(level, candMinX, candMinZ, OUTPOST_FOOTPRINT)) {
                    int anchorX = candMinX + 16;
                    int anchorZ = candMinZ + 16;
                    int anchorY = getSurfaceHeightAt(level, anchorX, anchorZ);
                    Optional<BlockPos> res = Optional.of(new BlockPos(anchorX, anchorY, anchorZ));
                    physicalBaseAnchorCache.put(key, res);
                    return res;
                }
            }

            // Phase 2: If all 4 subregions fail their ideal positions, apply shifting logic inside subregions
            int[][] outpostShifts = new int[][] {
                    { -8, 0 }, { 8, 0 }, { 0, -8 }, { 0, 8 },
                    { -12, 0 }, { 12, 0 }, { 0, -12 }, { 0, 12 },
                    { -8, -8 }, { 8, -8 }, { -8, 8 }, { 8, 8 },
                    { -16, 0 }, { 16, 0 }, { 0, -16 }, { 0, 16 }
            };

            for (int subIdx : subOrder) {
                int subMinX = startBlockX + subOffsets[subIdx][0];
                int subMinZ = startBlockZ + subOffsets[subIdx][1];

                for (int[] shift : outpostShifts) {
                    int candMinX = subMinX + 16 + shift[0];
                    int candMinZ = subMinZ + 16 + shift[1];

                    // Keep footprint within subregion boundaries [subMin, subMin + 64]
                    if (candMinX >= subMinX && candMinX + OUTPOST_FOOTPRINT <= subMinX + 64 &&
                            candMinZ >= subMinZ && candMinZ + OUTPOST_FOOTPRINT <= subMinZ + 64) {
                        if (isFootprintSuitable(level, candMinX, candMinZ, OUTPOST_FOOTPRINT)) {
                            int anchorX = candMinX + 16;
                            int anchorZ = candMinZ + 16;
                            int anchorY = getSurfaceHeightAt(level, anchorX, anchorZ);
                            Optional<BlockPos> res = Optional.of(new BlockPos(anchorX, anchorY, anchorZ));
                            physicalBaseAnchorCache.put(key, res);
                            return res;
                        }
                    }
                }
            }
        }

        Optional<BlockPos> empty = Optional.empty();
        physicalBaseAnchorCache.put(key, empty);
        return empty;
    }

    /**
     * Resolves the physical base placement for a region given a desired base tier.
     * If the desired tier cannot fit safely within the region, gracefully downgrades:
     * MEGA_BASE -> HEADQUARTERS -> OUTPOST -> NONE.
     */
    public BasePlacementResult resolveBasePlacement(ServerLevel level, long worldSeed, int regionX, int regionZ, BaseType desiredTier) {
        if (desiredTier == BaseType.NONE) {
            return new BasePlacementResult(BaseType.NONE, null);
        }

        BaseType currentTier = desiredTier;
        while (currentTier != BaseType.NONE) {
            Optional<BlockPos> anchor = findPhysicalBaseAnchor(level, worldSeed, regionX, regionZ, currentTier);
            if (anchor.isPresent()) {
                return new BasePlacementResult(currentTier, anchor.get());
            }
            // Graceful downgrade
            currentTier = switch (currentTier) {
                case MEGA_BASE -> BaseType.HEADQUARTERS;
                case HEADQUARTERS -> BaseType.OUTPOST;
                case OUTPOST -> BaseType.NONE;
                default -> BaseType.NONE;
            };
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
                BlockPos samplePos = new BlockPos(sampleX, 64, sampleZ);

                Holder<Biome> biomeHolder = level.getBiome(samplePos);
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
                BlockPos samplePos = new BlockPos(sampleX, 64, sampleZ);

                Holder<Biome> biomeHolder = level.getBiome(samplePos);
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
