package com.warfront.region.strength;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.generator.ProceduralRegionGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

public final class RegionalStrengthCalculator {

    public record RegionalStrength(float resistance, float stability) {
    }

    public static final float MIN_STRENGTH = 0.0F;
    public static final float MAX_STRENGTH = 100.0F;

    private static final long RESISTANCE_SALT = 0x5555AAAA11112222L;
    private static final long STABILITY_SALT = 0x3333CCCC44445555L;

    private RegionalStrengthCalculator() {
    }

    /**
     * Centralized, deterministic calculation of initial Resistance and Stability for a region.
     * Evaluates independent base-type baselines, weighted 64-chunk biome composition, neighboring region cohesion,
     * and independent deterministic +-3.0F regional variations.
     */
    public static RegionalStrength calculateInitialStrength(
            ServerLevel level,
            int regionX,
            int regionZ,
            Faction faction,
            BaseType baseType,
            long clusterId,
            long worldSeed
    ) {
        if (faction == Faction.UNCLAIMED) {
            return new RegionalStrength(0.0F, 0.0F);
        }

        // 1. Base-Type Baselines (Independent starting values)
        float baseResistance = getBaseTypeResistance(baseType);
        float baseStability = getBaseTypeStability(baseType);

        // 2. Weighted 64-Chunk Biome Terrain Composition
        float biomeResistanceMod = 0.0F;
        float biomeStabilityMod = 0.0F;
        if (level != null) {
            int startX = regionX * RegionData.REGION_SIZE_BLOCKS;
            int startZ = regionZ * RegionData.REGION_SIZE_BLOCKS;
            int chunksPerSide = Math.max(1, RegionData.REGION_SIZE_BLOCKS / 16);
            float totalChunkCount = chunksPerSide * chunksPerSide;

            float totalBiomeResistance = 0.0F;
            float totalBiomeStability = 0.0F;

            for (int cx = 0; cx < chunksPerSide; cx++) {
                for (int cz = 0; cz < chunksPerSide; cz++) {
                    int sampleX = startX + cx * 16 + 8;
                    int sampleZ = startZ + cz * 16 + 8;
                    BlockPos samplePos = new BlockPos(sampleX, 64, sampleZ);
                    Holder<Biome> biomeHolder = level.getBiome(samplePos);
                    String biomePath = biomeHolder.unwrapKey().map(key -> key.location().getPath().toLowerCase()).orElse("");

                    if (isMountainBiome(biomePath)) {
                        totalBiomeResistance += 15.0F;
                        totalBiomeStability -= 5.0F;
                    } else if (isSwampOrJungleBiome(biomePath)) {
                        totalBiomeResistance += 10.0F;
                        totalBiomeStability -= 10.0F;
                    } else if (isDesertOrBadlandsBiome(biomePath)) {
                        totalBiomeResistance += 5.0F;
                        totalBiomeStability -= 5.0F;
                    } else if (isPlainsOrForestBiome(biomePath)) {
                        totalBiomeResistance += 0.0F;
                        totalBiomeStability += 10.0F;
                    } else if (isSnowOrIceBiome(biomePath)) {
                        totalBiomeResistance += 5.0F;
                        totalBiomeStability -= 5.0F;
                    }
                    // Unknown / unclassified biomes contribute +0.0F / +0.0F
                }
            }

            biomeResistanceMod = totalBiomeResistance / totalChunkCount;
            biomeStabilityMod = totalBiomeStability / totalChunkCount;
        }

        // 3. Neighbor Cohesion (Cardinal Neighbors)
        float neighborResistanceMod = 0.0F;
        float neighborStabilityMod = 0.0F;
        int[][] cardinalOffsets = new int[][] { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 } };

        for (int[] offset : cardinalOffsets) {
            int nx = regionX + offset[0];
            int nz = regionZ + offset[1];

            Faction neighborOwner;
            long neighborClusterId;

            RegionData.RegionState savedState = (level != null) ? RegionData.get(level).getSavedRegionState(nx, nz) : null;

            if (savedState != null) {
                neighborOwner = savedState.owner();
                neighborClusterId = savedState.clusterId();
            } else {
                RegionData.RegionState pState = ProceduralRegionGenerator.getInstance().generateRawRegionState(level, worldSeed, nx, nz);
                neighborOwner = pState.owner();
                neighborClusterId = pState.clusterId();
            }

            if (neighborOwner == faction && faction != Faction.UNCLAIMED) {
                neighborResistanceMod += 5.0F;
                neighborStabilityMod += 7.5F;

                if (clusterId != 0L && neighborClusterId == clusterId) {
                    neighborStabilityMod += 2.5F;
                }
            }
        }

        // 4. Deterministic Independent Variation (-3.0F to +3.0F)
        long resHash = hashRegion(worldSeed, regionX, regionZ, clusterId, RESISTANCE_SALT);
        long stabHash = hashRegion(worldSeed, regionX, regionZ, clusterId, STABILITY_SALT);

        float resistanceVar = Math.floorMod(resHash, 6001L) / 1000.0F - 3.0F;
        float stabilityVar = Math.floorMod(stabHash, 6001L) / 1000.0F - 3.0F;

        // 5. Raw Independent Values & Centralized Clamping [0.0F, 100.0F]
        float finalResistance = Math.clamp(baseResistance + biomeResistanceMod + neighborResistanceMod + resistanceVar, MIN_STRENGTH, MAX_STRENGTH);
        float finalStability = Math.clamp(baseStability + biomeStabilityMod + neighborStabilityMod + stabilityVar, MIN_STRENGTH, MAX_STRENGTH);

        return new RegionalStrength(finalResistance, finalStability);
    }

    private static float getBaseTypeResistance(BaseType baseType) {
        return switch (baseType) {
            case MEGA_BASE -> 85.0F;
            case HEADQUARTERS -> 70.0F;
            case OUTPOST -> 50.0F;
            case NONE -> 30.0F;
        };
    }

    private static float getBaseTypeStability(BaseType baseType) {
        return switch (baseType) {
            case MEGA_BASE -> 95.0F;
            case HEADQUARTERS -> 80.0F;
            case OUTPOST -> 45.0F;
            case NONE -> 25.0F;
        };
    }

    private static boolean isMountainBiome(String path) {
        return path.contains("mountain") || path.contains("peaks") || path.contains("windswept") || path.contains("hills");
    }

    private static boolean isSwampOrJungleBiome(String path) {
        return path.contains("swamp") || path.contains("jungle");
    }

    private static boolean isDesertOrBadlandsBiome(String path) {
        return path.contains("desert") || path.contains("badlands");
    }

    private static boolean isPlainsOrForestBiome(String path) {
        return path.contains("plains") || path.contains("forest") || path.contains("taiga") || path.contains("meadow");
    }

    private static boolean isSnowOrIceBiome(String path) {
        return path.contains("snow") || path.contains("ice") || path.contains("frozen") || path.contains("grove");
    }

    private static long hashRegion(long worldSeed, int regionX, int regionZ, long clusterId, long salt) {
        long h = worldSeed ^ salt ^ clusterId;
        h = h * 6364136223846793005L + regionX;
        h = h * 6364136223846793005L + regionZ;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        return h ^ (h >>> 31);
    }
}
