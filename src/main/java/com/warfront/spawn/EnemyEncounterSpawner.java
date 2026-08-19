package com.warfront.spawn;

import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Central spawn service for out-of-war roaming enemy encounters.
 *
 * Pipeline:
 *   Region Resistance
 *     → EnemyResistanceTier
 *     → encounter size (bounded by tier)
 *     → available roles pool (faction-specific tier unlocks)
 *     → weighted role selection
 *     → EnemyEntityResolver (role → current entity)
 *     → entity placement inside selected subregion
 *
 * This class does not contain faction-specific AI, active-war logic,
 * mission generation, or Stability-based behavior.
 *
 * Stability has no effect on this system. Resistance drives everything here.
 */
public final class EnemyEncounterSpawner {

    private EnemyEncounterSpawner() {
    }

    /**
     * Spawns a roaming encounter in enemy territory outside an active war,
     * scattering entities around an origin point inside the selected subregion.
     *
     * @param level        the server level
     * @param regionX      the region X coordinate
     * @param regionZ      the region Z coordinate
     * @param subX         the subregion X coordinate (0 or 1)
     * @param subZ         the subregion Z coordinate (0 or 1)
     * @param faction      the enemy faction owning the subregion
     * @param resistance   the region's current Resistance value (0–100)
     * @param originBlockX block-level X of the spawn origin within the subregion
     * @param originBlockZ block-level Z of the spawn origin within the subregion
     * @return the number of entities successfully spawned
     */
    public static int spawnRoamingEncounter(
            ServerLevel level,
            int regionX,
            int regionZ,
            int subX,
            int subZ,
            Faction faction,
            float resistance,
            int originBlockX,
            int originBlockZ) {

        EnemyResistanceTier tier = EnemyResistanceTier.fromResistance(resistance);
        int encounterSize = determineEncounterSize(tier, level.getRandom());

        List<Object> rolePool;
        if (faction == Faction.ZOMBIE_HORDE) {
            rolePool = buildZombiePool(tier);
        } else if (faction == Faction.PILLAGER_CONQUERORS) {
            rolePool = buildPillagerPool(tier);
        } else {
            return 0; // Only AI factions use roaming encounter spawns
        }

        if (rolePool.isEmpty()) {
            return 0;
        }

        // Subregion block boundaries — enemies must stay strictly within the selected subregion
        int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;

        // Scatter radius: enemies are placed randomly within ±12 blocks of origin,
        // clamped to remain inside the owning subregion.
        final int SCATTER = 12;

        int spawned = 0;
        for (int i = 0; i < encounterSize; i++) {
            Object role = selectWeightedRole(rolePool, level.getRandom());
            if (role == null) continue;

            int spawnX = Math.clamp(
                    originBlockX + level.getRandom().nextInt(2 * SCATTER + 1) - SCATTER,
                    subMinX, subMaxX);
            int spawnZ = Math.clamp(
                    originBlockZ + level.getRandom().nextInt(2 * SCATTER + 1) - SCATTER,
                    subMinZ, subMaxZ);
            int spawnY = level.getHeight(Heightmap.Types.WORLD_SURFACE, spawnX, spawnZ);

            Entity entity = resolveEntity(faction, role, level);
            if (entity == null) continue;

            entity.moveTo(spawnX + 0.5D, spawnY, spawnZ + 0.5D,
                    level.getRandom().nextFloat() * 360.0F, 0.0F);

            if (entity instanceof Mob mob) {
                mob.finalizeSpawn(level,
                        level.getCurrentDifficultyAt(new BlockPos(spawnX, spawnY, spawnZ)),
                        MobSpawnType.EVENT, null);
            }

            if (level.addFreshEntity(entity)) {
                spawned++;
                if (entity instanceof Mob mob) {
                    RoamingEntityTracker.register(mob, regionX, regionZ, subX, subZ, faction);
                }
            }
        }

        return spawned;
    }

    // -----------------------------------------------------------------------
    // Encounter size
    // -----------------------------------------------------------------------

    /**
     * Determines the number of enemies to spawn for this encounter.
     * Result is always within the tier's [minSpawn, maxSpawn] range.
     */
    private static int determineEncounterSize(EnemyResistanceTier tier, RandomSource random) {
        int min = tier.minSpawn();
        int max = tier.maxSpawn();
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
    }

    // -----------------------------------------------------------------------
    // Role pool construction
    // -----------------------------------------------------------------------

    /**
     * Builds the weighted pool of zombie roles available at the given tier.
     * Each role is added once per unit of weight, so selection by random index
     * produces the correct relative frequency without extra arithmetic.
     */
    private static List<Object> buildZombiePool(EnemyResistanceTier tier) {
        List<Object> pool = new ArrayList<>();
        for (ZombieEnemyRole role : ZombieEnemyRole.values()) {
            if (role.isAvailableAt(tier)) {
                for (int w = 0; w < role.weight(); w++) {
                    pool.add(role);
                }
            }
        }
        return pool;
    }

    /**
     * Builds the weighted pool of pillager roles available at the given tier.
     * CATAPULT is always excluded via PillagerEnemyRole.isAvailableForRoaming().
     */
    private static List<Object> buildPillagerPool(EnemyResistanceTier tier) {
        List<Object> pool = new ArrayList<>();
        for (PillagerEnemyRole role : PillagerEnemyRole.values()) {
            if (role.isAvailableForRoaming(tier)) {
                for (int w = 0; w < role.weight(); w++) {
                    pool.add(role);
                }
            }
        }
        return pool;
    }

    // -----------------------------------------------------------------------
    // Weighted role selection
    // -----------------------------------------------------------------------

    /**
     * Picks one role uniformly from the pre-weighted pool.
     * Since each weight unit is one entry in the pool, uniform random selection
     * produces the desired weighted distribution.
     */
    private static Object selectWeightedRole(List<Object> pool, RandomSource random) {
        if (pool.isEmpty()) return null;
        return pool.get(random.nextInt(pool.size()));
    }

    // -----------------------------------------------------------------------
    // Entity resolution
    // -----------------------------------------------------------------------

    private static Entity resolveEntity(Faction faction, Object role, ServerLevel level) {
        if (faction == Faction.ZOMBIE_HORDE && role instanceof ZombieEnemyRole zr) {
            return EnemyEntityResolver.resolveZombieRole(zr, level);
        }
        if (faction == Faction.PILLAGER_CONQUERORS && role instanceof PillagerEnemyRole pr) {
            return EnemyEntityResolver.resolvePillagerRole(pr, level);
        }
        return null;
    }
}
