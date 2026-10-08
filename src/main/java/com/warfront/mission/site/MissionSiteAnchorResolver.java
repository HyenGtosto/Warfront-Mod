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
 * Implements Section 6.2 of the Pillager Mission Blueprint:
 * - Deterministic seed: siteSeed = missionSeed ^ (subX * 104729L) ^ (subZ * 224737L)
 * - Restricts placement strictly within the 64x64 subregion bounds with a 12-block border margin
 * - Validates solid non-liquid surface footing and adequate headroom
 */
public final class MissionSiteAnchorResolver {

    private static final int SUBREGION_SIZE = ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS; // 64
    private static final int BORDER_MARGIN = 12; // 12 blocks away from subregion edges

    private MissionSiteAnchorResolver() {
    }

    /**
     * Resolves a deterministic, dry-land anchor position for a temporary mission site inside a subregion.
     *
     * @param level       ServerLevel
     * @param regionX     Region X coordinate
     * @param regionZ     Region Z coordinate
     * @param subX        Subregion X index (0 or 1)
     * @param subZ        Subregion Z index (0 or 1)
     * @param missionSeed Region authoritative mission seed
     * @return Validated {@link BlockPos} anchor on solid ground
     */
    public static BlockPos resolveDryLandAnchor(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            long missionSeed
    ) {
        int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * SUBREGION_SIZE;
        int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * SUBREGION_SIZE;

        long siteSeed = missionSeed ^ (subX * 104729L) ^ (subZ * 224737L);
        int span = SUBREGION_SIZE - 2 * BORDER_MARGIN; // 40 blocks span (12 to 51)

        // Attempt up to 16 deterministic candidate offsets
        for (int attempt = 0; attempt < 16; attempt++) {
            long hashX = WeightedMissionSelector.mix64(siteSeed ^ (attempt * 0x9E3779B9L));
            long hashZ = WeightedMissionSelector.mix64(siteSeed ^ ((attempt + 17) * 0x85EBCA6BL));

            int localX = BORDER_MARGIN + (int) ((hashX & 0x7FFFFFFF) % span);
            int localZ = BORDER_MARGIN + (int) ((hashZ & 0x7FFFFFFF) % span);

            int worldX = subMinX + localX;
            int worldZ = subMinZ + localZ;

            int surfaceY = EnemyEncounterSpawner.findDryLandSurfaceY(level, worldX, worldZ);
            if (surfaceY != Integer.MIN_VALUE) {
                return new BlockPos(worldX, surfaceY, worldZ);
            }
        }

        // Fallback: subregion center
        int fallbackX = subMinX + 32;
        int fallbackZ = subMinZ + 32;
        int fallbackY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fallbackX, fallbackZ);
        return new BlockPos(fallbackX, Math.max(level.getMinBuildHeight() + 5, fallbackY), fallbackZ);
    }
}
