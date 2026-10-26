package com.warfront.mission.site;

import com.warfront.mission.WeightedMissionSelector;
import com.warfront.region.RegionData;
import com.warfront.spawn.EnemyEncounterSpawner;
import com.warfront.spawn.ExplorationSpawnManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Deterministic bounded search algorithm for resolving dry-land temporary mission site anchor coordinates.
 *
 * Implements footprint-aware terrain evaluation:
 * - Deterministic seed: siteSeed = missionSeed ^ (subX * 104729L) ^ (subZ * 224737L)
 * - Restricts placement strictly within the 64x64 subregion bounds with structure-aware margins
 * - Validates that NO part of the structure footprint touches water, rivers, lakes, or oceans
 * - Evaluates terrain slope variance across the entire footprint to prevent spawning on steep cliffs/mountains
 * - Returns the median/average surface level across the footprint so structures sit flush
 */
public final class MissionSiteAnchorResolver {

    private static final int SUBREGION_SIZE = ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS; // 64
    private static final int BORDER_MARGIN = 12;

    private MissionSiteAnchorResolver() {
    }

    /**
     * Resolves a deterministic, dry-land anchor position for a standard small site.
     */
    public static BlockPos resolveDryLandAnchor(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            long missionSeed
    ) {
        return resolveDryLandAnchor(level, regionX, regionZ, subX, subZ, missionSeed, 10, 10);
    }

    /**
     * Resolves a deterministic, dry-land anchor position validating the complete footprint of the structure.
     *
     * @param level           ServerLevel
     * @param regionX         Region X coordinate
     * @param regionZ         Region Z coordinate
     * @param subX            Subregion X index (0 or 1)
     * @param subZ            Subregion Z index (0 or 1)
     * @param missionSeed     Region authoritative mission seed
     * @param footprintWidth  Structure horizontal X size
     * @param footprintLength Structure horizontal Z size
     * @return Validated {@link BlockPos} anchor centered on flat, dry land
     */
    public static BlockPos resolveDryLandAnchor(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            long missionSeed,
            int footprintWidth, int footprintLength
    ) {
        int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * SUBREGION_SIZE;
        int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * SUBREGION_SIZE;

        int halfW = Math.max(1, footprintWidth / 2);
        int halfL = Math.max(1, footprintLength / 2);

        int marginX = Math.clamp(halfW + 3, 6, 26);
        int marginZ = Math.clamp(halfL + 3, 6, 26);
        int spanX = Math.max(1, SUBREGION_SIZE - 2 * marginX);
        int spanZ = Math.max(1, SUBREGION_SIZE - 2 * marginZ);

        long siteSeed = missionSeed ^ (subX * 104729L) ^ (subZ * 224737L);

        BlockPos bestPos = null;
        int bestVariance = Integer.MAX_VALUE;

        // 1. Attempt up to 32 deterministic pseudo-random candidates
        for (int attempt = 0; attempt < 32; attempt++) {
            long hashX = WeightedMissionSelector.mix64(siteSeed ^ (attempt * 0x9E3779B9L));
            long hashZ = WeightedMissionSelector.mix64(siteSeed ^ ((attempt + 19) * 0x85EBCA6BL));

            int localX = marginX + (int) ((hashX & 0x7FFFFFFF) % spanX);
            int localZ = marginZ + (int) ((hashZ & 0x7FFFFFFF) % spanZ);

            int worldX = subMinX + localX;
            int worldZ = subMinZ + localZ;

            if (isCollidingWithBase(level, regionX, regionZ, worldX, worldZ, halfW, halfL)) {
                continue;
            }

            FootprintEvaluation eval = evaluateFootprint(level, worldX, worldZ, halfW, halfL);
            if (eval.valid()) {
                if (eval.variance() <= 2) {
                    // Ideal flat dry terrain found!
                    return new BlockPos(worldX, eval.averageY(), worldZ);
                }
                if (eval.variance() < bestVariance) {
                    bestVariance = eval.variance();
                    bestPos = new BlockPos(worldX, eval.averageY(), worldZ);
                }
            }
        }

        if (bestPos != null && bestVariance <= 5) {
            return bestPos;
        }

        // 2. Deterministic grid scan across the valid interior span
        int stepX = Math.max(4, spanX / 5);
        int stepZ = Math.max(4, spanZ / 5);
        for (int lx = marginX; lx <= marginX + spanX; lx += stepX) {
            for (int lz = marginZ; lz <= marginZ + spanZ; lz += stepZ) {
                int worldX = subMinX + lx;
                int worldZ = subMinZ + lz;

                if (isCollidingWithBase(level, regionX, regionZ, worldX, worldZ, halfW, halfL)) {
                    continue;
                }

                FootprintEvaluation eval = evaluateFootprint(level, worldX, worldZ, halfW, halfL);
                if (eval.valid()) {
                    if (eval.variance() <= 2) {
                        return new BlockPos(worldX, eval.averageY(), worldZ);
                    }
                    if (eval.variance() < bestVariance) {
                        bestVariance = eval.variance();
                        bestPos = new BlockPos(worldX, eval.averageY(), worldZ);
                    }
                }
            }
        }

        if (bestPos != null) {
            return bestPos;
        }

        // 3. Fallback: Subregion center on highest dry solid ground
        int fallbackX = subMinX + 32;
        int fallbackZ = subMinZ + 32;
        int fallbackY = EnemyEncounterSpawner.findDryLandSurfaceY(level, fallbackX, fallbackZ);
        if (fallbackY == Integer.MIN_VALUE) {
            fallbackY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fallbackX, fallbackZ);
        }
        return new BlockPos(fallbackX, Math.max(level.getMinBuildHeight() + 5, fallbackY), fallbackZ);
    }

    private record FootprintEvaluation(boolean valid, int variance, int averageY) {}

    private static FootprintEvaluation evaluateFootprint(ServerLevel level, int cx, int cz, int hw, int hl) {
        int[][] samplePoints = new int[][] {
                { cx, cz }, // Center
                { cx - hw, cz - hl }, { cx + hw, cz - hl }, // 4 Corners
                { cx - hw, cz + hl }, { cx + hw, cz + hl },
                { cx - hw, cz }, { cx + hw, cz },           // 4 Edge midpoints
                { cx, cz - hl }, { cx, cz + hl },
                { cx - hw / 2, cz - hl / 2 }, { cx + hw / 2, cz - hl / 2 }, // Inner quadrants
                { cx - hw / 2, cz + hl / 2 }, { cx + hw / 2, cz + hl / 2 }
        };

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int sumY = 0;
        int count = 0;

        for (int[] pt : samplePoints) {
            int px = pt[0];
            int pz = pt[1];

            int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, px, pz);
            if (y == Integer.MIN_VALUE) {
                return new FootprintEvaluation(false, 0, 0); // Liquid or void detected in footprint
            }

            BlockPos pos = new BlockPos(px, y, pz);
            if (!level.getFluidState(pos).isEmpty() || level.getBlockState(pos).liquid()
                    || !level.getFluidState(pos.above()).isEmpty() || level.getBlockState(pos.above()).liquid()) {
                return new FootprintEvaluation(false, 0, 0); // Water / river boundary detected
            }

            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            sumY += y;
            count++;
        }

        int variance = maxY - minY;
        int avgY = Math.round((float) sumY / count);
        return new FootprintEvaluation(true, variance, avgY);
    }

    private static boolean isCollidingWithBase(ServerLevel level, int regionX, int regionZ, int worldX, int worldZ, int halfW, int halfL) {
        if (level == null) return false;
        RegionData regions = RegionData.get(level);
        if (regions == null) return false;
        RegionData.Region reg = regions.regionAt(regionX, regionZ);
        if (reg == null || reg.baseType() == com.warfront.region.BaseType.NONE || reg.baseAnchor() == null) {
            return false;
        }
        int baseHalf = switch (reg.baseType()) {
            case MEGA_BASE -> 52;
            case HEADQUARTERS -> 48;
            default -> 18;
        };
        int dx = Math.abs(worldX - reg.baseAnchor().getX());
        int dz = Math.abs(worldZ - reg.baseAnchor().getZ());
        return (dx < baseHalf + halfW + 6) && (dz < baseHalf + halfL + 6);
    }
}
