package com.warfront.ai.strategy;

import com.warfront.region.RegionData;
import com.warfront.spawn.EnemyEncounterSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the geometric frontline and border spawn positions for Pillager invasion forces
 * attacking a target region.
 *
 * Frontline Types:
 *   - Type 1 (1 Enemy Border): Isosceles right triangle with 90-degree apex at region center (64, 64).
 *   - Type 2 (2 Adjacent Borders): Diagonal line cutting the region diagonally in half like a sandwich.
 *   - Type 3 (3 Enemy Borders): Inverted triangle wedge around the remaining friendly border.
 *   - Type 4 (4 Enemy Borders): Converges directly onto the center intersection.
 */
public final class FrontlineShape {

    public enum BorderSide {
        NORTH, SOUTH, WEST, EAST
    }

    private final int regionX;
    private final int regionZ;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;
    private final int centerX;
    private final int centerZ;
    private final List<BorderSide> attackingBorders;

    public FrontlineShape(int regionX, int regionZ, List<RegionData.SourcePos> sources) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.minX = regionX * RegionData.REGION_SIZE_BLOCKS;
        this.maxX = minX + RegionData.REGION_SIZE_BLOCKS - 1;
        this.minZ = regionZ * RegionData.REGION_SIZE_BLOCKS;
        this.maxZ = minZ + RegionData.REGION_SIZE_BLOCKS - 1;
        this.centerX = minX + RegionData.REGION_SIZE_BLOCKS / 2;
        this.centerZ = minZ + RegionData.REGION_SIZE_BLOCKS / 2;

        boolean hasNorth = false;
        boolean hasSouth = false;
        boolean hasWest = false;
        boolean hasEast = false;

        if (sources != null && !sources.isEmpty()) {
            for (RegionData.SourcePos src : sources) {
                if (src.z() < regionZ) hasNorth = true;
                if (src.z() > regionZ) hasSouth = true;
                if (src.x() < regionX) hasWest = true;
                if (src.x() > regionX) hasEast = true;
            }
        }

        // Default fallback if no valid sources present: North attack
        if (!hasNorth && !hasSouth && !hasWest && !hasEast) {
            hasNorth = true;
        }

        List<BorderSide> borders = new ArrayList<>();
        if (hasNorth) borders.add(BorderSide.NORTH);
        if (hasSouth) borders.add(BorderSide.SOUTH);
        if (hasWest) borders.add(BorderSide.WEST);
        if (hasEast) borders.add(BorderSide.EAST);
        this.attackingBorders = borders;
    }

    /**
     * Calculates the frontline destination for a squad spawned at (spawnX, spawnZ).
     *
     * Advances the unit forward along its attack axis into the region until reaching the
     * perimeter boundary of the calculated frontline shape (Type 1 wedge, Type 2 diagonal, or Type 3 inverted wedge).
     * This distributes squads evenly across the entire battle line without corner clumping.
     */
    public BlockPos calculateFrontlineDestination(int spawnX, int spawnZ, ServerLevel level) {
        boolean n = attackingBorders.contains(BorderSide.NORTH);
        boolean s = attackingBorders.contains(BorderSide.SOUTH);
        boolean w = attackingBorders.contains(BorderSide.WEST);
        boolean e = attackingBorders.contains(BorderSide.EAST);
        int count = attackingBorders.size();

        int targetX = centerX;
        int targetZ = centerZ;

        if (count == 1) {
            // Type 1: Isosceles triangle pointing to center (64, 64)
            if (n) {
                int dx = (spawnX <= centerX) ? (spawnX - minX) : (maxX - spawnX);
                targetX = spawnX;
                targetZ = minZ + dx;
            } else if (s) {
                int dx = (spawnX <= centerX) ? (spawnX - minX) : (maxX - spawnX);
                targetX = spawnX;
                targetZ = maxZ - dx;
            } else if (w) {
                int dz = (spawnZ <= centerZ) ? (spawnZ - minZ) : (maxZ - spawnZ);
                targetX = minX + dz;
                targetZ = spawnZ;
            } else { // e
                int dz = (spawnZ <= centerZ) ? (spawnZ - minZ) : (maxZ - spawnZ);
                targetX = maxX - dz;
                targetZ = spawnZ;
            }
        } else if (count == 2) {
            // Type 2: Flank sandwich cut (corner to corner through center)
            if (n && w) {
                if (Math.abs(spawnZ - minZ) < Math.abs(spawnX - minX)) { // Spawned on North
                    targetX = spawnX;
                    targetZ = maxZ - (spawnX - minX);
                } else { // Spawned on West
                    targetX = maxX - (spawnZ - minZ);
                    targetZ = spawnZ;
                }
            } else if (n && e) {
                if (Math.abs(spawnZ - minZ) < Math.abs(maxX - spawnX)) { // Spawned on North
                    targetX = spawnX;
                    targetZ = minZ + (spawnX - minX);
                } else { // Spawned on East
                    targetX = minX + (spawnZ - minZ);
                    targetZ = spawnZ;
                }
            } else if (s && w) {
                if (Math.abs(maxZ - spawnZ) < Math.abs(spawnX - minX)) { // Spawned on South
                    targetX = spawnX;
                    targetZ = minZ + (spawnX - minX);
                } else { // Spawned on West
                    targetX = minX + (spawnZ - minZ);
                    targetZ = spawnZ;
                }
            } else if (s && e) {
                if (Math.abs(maxZ - spawnZ) < Math.abs(maxX - spawnX)) { // Spawned on South
                    targetX = spawnX;
                    targetZ = maxZ - (spawnX - minX);
                } else { // Spawned on East
                    targetX = maxX - (spawnZ - minZ);
                    targetZ = spawnZ;
                }
            } else {
                // Opposite borders (N+S or W+E) -> Dual Type 1
                if (n && Math.abs(spawnZ - minZ) <= 10) {
                    int dx = (spawnX <= centerX) ? (spawnX - minX) : (maxX - spawnX);
                    targetX = spawnX;
                    targetZ = minZ + dx;
                } else if (s && Math.abs(maxZ - spawnZ) <= 10) {
                    int dx = (spawnX <= centerX) ? (spawnX - minX) : (maxX - spawnX);
                    targetX = spawnX;
                    targetZ = maxZ - dx;
                } else if (w && Math.abs(spawnX - minX) <= 10) {
                    int dz = (spawnZ <= centerZ) ? (spawnZ - minZ) : (maxZ - spawnZ);
                    targetX = minX + dz;
                    targetZ = spawnZ;
                } else {
                    int dz = (spawnZ <= centerZ) ? (spawnZ - minZ) : (maxZ - spawnZ);
                    targetX = maxX - dz;
                    targetZ = spawnZ;
                }
            }
        } else if (count == 3) {
            // Type 3: Inverted triangle on the sole remaining friendly border
            if (!s) { // South is friendly
                if (Math.abs(spawnZ - minZ) <= 10) { // Spawned on North
                    targetX = spawnX;
                    targetZ = (spawnX <= centerX) ? (maxZ - (spawnX - minX)) : (minZ + (spawnX - minX));
                } else if (Math.abs(spawnX - minX) <= 10) { // Spawned on West
                    targetX = (spawnZ >= centerZ) ? (minX + (maxZ - spawnZ)) : centerX;
                    targetZ = (spawnZ >= centerZ) ? spawnZ : centerZ;
                } else { // Spawned on East
                    targetX = (spawnZ >= centerZ) ? (maxX - (maxZ - spawnZ)) : centerX;
                    targetZ = (spawnZ >= centerZ) ? spawnZ : centerZ;
                }
            } else if (!n) { // North is friendly
                if (Math.abs(maxZ - spawnZ) <= 10) { // Spawned on South
                    targetX = spawnX;
                    targetZ = (spawnX <= centerX) ? (minZ + (spawnX - minX)) : (maxZ - (spawnX - minX));
                } else if (Math.abs(spawnX - minX) <= 10) { // Spawned on West
                    targetX = (spawnZ <= centerZ) ? (minX + (spawnZ - minZ)) : centerX;
                    targetZ = (spawnZ <= centerZ) ? spawnZ : centerZ;
                } else { // Spawned on East
                    targetX = (spawnZ <= centerZ) ? (maxX - (spawnZ - minZ)) : centerX;
                    targetZ = (spawnZ <= centerZ) ? spawnZ : centerZ;
                }
            } else if (!e) { // East is friendly
                if (Math.abs(spawnX - minX) <= 10) { // Spawned on West
                    targetX = (spawnZ <= centerZ) ? (minX + (spawnZ - minZ)) : (minX + (maxZ - spawnZ));
                    targetZ = spawnZ;
                } else if (Math.abs(spawnZ - minZ) <= 10) { // Spawned on North
                    targetX = (spawnX >= centerX) ? spawnX : centerX;
                    targetZ = (spawnX >= centerX) ? (minZ + (maxX - spawnX)) : centerZ;
                } else { // Spawned on South
                    targetX = (spawnX >= centerX) ? spawnX : centerX;
                    targetZ = (spawnX >= centerX) ? (maxZ - (maxX - spawnX)) : centerZ;
                }
            } else { // West is friendly
                if (Math.abs(maxX - spawnX) <= 10) { // Spawned on East
                    targetX = (spawnZ <= centerZ) ? (maxX - (spawnZ - minZ)) : (maxX - (maxZ - spawnZ));
                    targetZ = spawnZ;
                } else if (Math.abs(spawnZ - minZ) <= 10) { // Spawned on North
                    targetX = (spawnX <= centerX) ? spawnX : centerX;
                    targetZ = (spawnX <= centerX) ? (minZ + (spawnX - minX)) : centerZ;
                } else { // Spawned on South
                    targetX = (spawnX <= centerX) ? spawnX : centerX;
                    targetZ = (spawnX <= centerX) ? (maxZ - (spawnX - minX)) : centerZ;
                }
            }
        } else {
            // Type 4: Encircled -> Converge at center
            targetX = centerX;
            targetZ = centerZ;
        }

        // Clamp inside region
        targetX = Math.clamp(targetX, minX + 4, maxX - 4);
        targetZ = Math.clamp(targetZ, minZ + 4, maxZ - 4);

        int dryY = EnemyEncounterSpawner.findDryLandSurfaceY(level, targetX, targetZ);
        if (dryY != Integer.MIN_VALUE) {
            return new BlockPos(targetX, dryY, targetZ);
        }

        // Search small radius for nearest dry land
        for (int r = 1; r <= 5; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int tx = Math.clamp(targetX + dx, minX + 4, maxX - 4);
                    int tz = Math.clamp(targetZ + dz, minZ + 4, maxZ - 4);
                    int dy = EnemyEncounterSpawner.findDryLandSurfaceY(level, tx, tz);
                    if (dy != Integer.MIN_VALUE) {
                        return new BlockPos(tx, dy, tz);
                    }
                }
            }
        }

        int dryCenterY = EnemyEncounterSpawner.findDryLandSurfaceY(level, centerX, centerZ);
        return new BlockPos(centerX, dryCenterY != Integer.MIN_VALUE ? dryCenterY : 64, centerZ);
    }

    /**
     * Picks a random valid dry-land spawn position on one of the active attacking borders.
     */
    public BlockPos getRandomBorderSpawnPos(ServerLevel level, RandomSource random) {
        if (attackingBorders.isEmpty()) {
            return null;
        }

        final int INSET = 3; // Inset 3 blocks inside the border to avoid chunk boundary clipping
        for (int attempt = 0; attempt < 16; attempt++) {
            BorderSide side = attackingBorders.get(random.nextInt(attackingBorders.size()));
            int candX;
            int candZ;

            switch (side) {
                case NORTH -> {
                    candX = minX + INSET + random.nextInt(RegionData.REGION_SIZE_BLOCKS - (INSET * 2));
                    candZ = minZ + INSET;
                }
                case SOUTH -> {
                    candX = minX + INSET + random.nextInt(RegionData.REGION_SIZE_BLOCKS - (INSET * 2));
                    candZ = maxZ - INSET;
                }
                case WEST -> {
                    candX = minX + INSET;
                    candZ = minZ + INSET + random.nextInt(RegionData.REGION_SIZE_BLOCKS - (INSET * 2));
                }
                case EAST -> {
                    candX = maxX - INSET;
                    candZ = minZ + INSET + random.nextInt(RegionData.REGION_SIZE_BLOCKS - (INSET * 2));
                }
                default -> {
                    candX = centerX;
                    candZ = centerZ;
                }
            }

            int spawnY = EnemyEncounterSpawner.findDryLandSurfaceY(level, candX, candZ);
            if (spawnY != Integer.MIN_VALUE) {
                return new BlockPos(candX, spawnY, candZ);
            }
        }

        return null;
    }

    public int regionX() { return regionX; }
    public int regionZ() { return regionZ; }
    public int centerX() { return centerX; }
    public int centerZ() { return centerZ; }
    public List<BorderSide> attackingBorders() { return attackingBorders; }
}
