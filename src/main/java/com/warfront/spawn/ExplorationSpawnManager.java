package com.warfront.spawn;

import com.warfront.config.WarfrontConfig;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages out-of-war exploration enemy spawning around the player using subregions
 * as the spatial spawn granularity.
 *
 * Strategic hierarchy:
 *   Region (128×128)  — Strategic container: faction ownership, resistance, stability, siege status.
 *   Subregion (64×64) — Spatial spawn source: local encounter location & subregion-level cooldown.
 *   Player            — Driver of exploration evaluation & relevance.
 *
 * Performance guarantees:
 *   - Uses O(1) subRegionAt() which relies on saved state or raw procedural state.
 *   - Does NOT trigger regionAt() or calculateInitialStrength() biome scans.
 *   - Evaluation is gated on chunk-change events.
 */
public final class ExplorationSpawnManager {

    /**
     * Region-coordinate radius scanned around the player each evaluation.
     * Radius 2 = 5×5 region square = 640×640 blocks around player.
     */
    private static final int SCAN_RADIUS_REGIONS = 2;

    /** Subregion size in blocks (128 / 2 = 64 blocks = 4×4 chunks). */
    public static final int SUBREGION_SIZE_BLOCKS = 64;

    /** Minimum block distance from player for candidate spawn origin. */
    public static final int MIN_SPAWN_DIST_BLOCKS = 16;

    /** Maximum block distance from player for candidate spawn origin & subregion relevance. */
    public static final int MAX_SPAWN_DIST_BLOCKS = 96;

    /** Number of candidate positions tested inside a selected subregion. */
    private static final int MAX_SPAWN_CANDIDATES = 16;

    /**
     * Subregion cooldown map: subRegionKey -> last spawn game time.
     * Key structure: (regionId << 2) | ((subZ & 1) << 1) | (subX & 1)
     */
    private static final Map<Long, Long> SUBREGION_SPAWN_COOLDOWNS = new HashMap<>();

    private ExplorationSpawnManager() {
    }

    /**
     * Container record for an eligible subregion during evaluation.
     */
    public record EligibleSubRegion(
            int regionX, int regionZ,
            int subX, int subZ,
            Faction owner,
            int subMinX, int subMaxX,
            int subMinZ, int subMaxZ,
            double distToPlayer
    ) {}

    /**
     * Entry point called when a player enters a new chunk.
     * Scans nearby subregions around the player and triggers an exploration spawn
     * in an eligible, non-cooldown subregion.
     *
     * @param player the server player who moved
     * @param level  the server level
     */
    public static void evaluateNearbyRegions(ServerPlayer player, ServerLevel level) {
        RegionData regions = RegionData.get(level);
        long gameTime = level.getGameTime();

        int playerBlockX = (int) player.getX();
        int playerBlockZ = (int) player.getZ();
        int playerRegionX = Math.floorDiv(playerBlockX, RegionData.REGION_SIZE_BLOCKS);
        int playerRegionZ = Math.floorDiv(playerBlockZ, RegionData.REGION_SIZE_BLOCKS);

        long cooldownTicks = WarfrontConfig.SUBREGION_SPAWN_COOLDOWN_SECONDS.get() * 20L;

        List<EligibleSubRegion> eligibleSubRegions = new ArrayList<>();

        for (int drx = -SCAN_RADIUS_REGIONS; drx <= SCAN_RADIUS_REGIONS; drx++) {
            for (int drz = -SCAN_RADIUS_REGIONS; drz <= SCAN_RADIUS_REGIONS; drz++) {
                int rx = playerRegionX + drx;
                int rz = playerRegionZ + drz;

                // Out-of-war guard: skip region if under active siege
                if (regions.getSiege(rx, rz) != null) {
                    continue;
                }

                long regionId = ChunkPos.asLong(rx, rz);

                for (int subX = 0; subX <= 1; subX++) {
                    for (int subZ = 0; subZ <= 1; subZ++) {
                        long subKey = (regionId << 2) | ((subZ & 1) << 1) | (subX & 1);

                        // Cooldown check at subregion level
                        Long lastSpawn = SUBREGION_SPAWN_COOLDOWNS.get(subKey);
                        if (lastSpawn != null && (gameTime - lastSpawn) < cooldownTicks) {
                            continue;
                        }

                        // Lightweight O(1) subregion state query (saved or raw procedural fallback)
                        RegionData.SubRegionState subState = regions.subRegionAt(rx, rz, subX, subZ);
                        Faction owner = subState.owner();

                        if (!owner.isAI() || subState.underSiege()) {
                            continue; // Skip non-hostile subregions or subregions under siege
                        }

                        int subMinX = rx * RegionData.REGION_SIZE_BLOCKS + subX * SUBREGION_SIZE_BLOCKS;
                        int subMaxX = subMinX + SUBREGION_SIZE_BLOCKS - 1;
                        int subMinZ = rz * RegionData.REGION_SIZE_BLOCKS + subZ * SUBREGION_SIZE_BLOCKS;
                        int subMaxZ = subMinZ + SUBREGION_SIZE_BLOCKS - 1;

                        // Shortest distance from player to subregion bounding box
                        int dx = Math.max(0, Math.max(subMinX - playerBlockX, playerBlockX - subMaxX));
                        int dz = Math.max(0, Math.max(subMinZ - playerBlockZ, playerBlockZ - subMaxZ));
                        double distToPlayer = Math.hypot(dx, dz);

                        if (distToPlayer > MAX_SPAWN_DIST_BLOCKS) {
                            continue; // Too far from player
                        }

                        eligibleSubRegions.add(new EligibleSubRegion(
                                rx, rz, subX, subZ, owner,
                                subMinX, subMaxX, subMinZ, subMaxZ, distToPlayer
                        ));
                    }
                }
            }
        }

        if (eligibleSubRegions.isEmpty()) {
            return;
        }

        // Select an eligible subregion randomly among candidates
        EligibleSubRegion selected = eligibleSubRegions.get(level.getRandom().nextInt(eligibleSubRegions.size()));

        // Generate candidate positions inside the selected subregion
        int[] origin = selectSpawnOriginInSubRegion(level, selected, playerBlockX, playerBlockZ);
        if (origin == null) {
            return;
        }

        // Retrieve resistance from saved/calculated stored state (no recalculation)
        float resistance = regions.calculateEffectiveResistance(selected.regionX(), selected.regionZ());

        int spawned = EnemyEncounterSpawner.spawnRoamingEncounter(
                level,
                selected.regionX(), selected.regionZ(),
                selected.subX(), selected.subZ(),
                selected.owner(), resistance,
                origin[0], origin[1]
        );

        if (spawned > 0) {
            long subKey = (ChunkPos.asLong(selected.regionX(), selected.regionZ()) << 2) | ((selected.subZ() & 1) << 1) | (selected.subX() & 1);
            SUBREGION_SPAWN_COOLDOWNS.put(subKey, gameTime);
        }
    }

    /**
     * Generates candidate spawn origins inside the selected subregion's 64×64 block area,
     * validating each against distance constraints [MIN_SPAWN_DIST_BLOCKS, MAX_SPAWN_DIST_BLOCKS]
     * and valid surface terrain.
     */
    private static int[] selectSpawnOriginInSubRegion(
            ServerLevel level,
            EligibleSubRegion sub,
            int playerBlockX, int playerBlockZ) {

        for (int attempt = 0; attempt < MAX_SPAWN_CANDIDATES; attempt++) {
            int candX = sub.subMinX() + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS);
            int candZ = sub.subMinZ() + level.getRandom().nextInt(SUBREGION_SIZE_BLOCKS);

            double dist = Math.hypot(candX - playerBlockX, candZ - playerBlockZ);
            if (dist < MIN_SPAWN_DIST_BLOCKS || dist > MAX_SPAWN_DIST_BLOCKS) {
                continue;
            }

            int spawnY = level.getHeight(Heightmap.Types.WORLD_SURFACE, candX, candZ);
            if (spawnY <= level.getMinBuildHeight()) {
                continue;
            }

            return new int[] { candX, candZ };
        }

        return null;
    }
}
