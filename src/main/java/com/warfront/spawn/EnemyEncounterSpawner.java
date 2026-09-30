package com.warfront.spawn;

import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Central spawn service for enemy encounters.
 *
 * Provides two explicit, decoupled spawn pathways:
 *   1. spawnWanderingEncounter: Out-of-war exploration roaming mobs (ExplorationSpawnManager).
 *   2. spawnMissionEncounter: Active campaign mission wave mobs (KillCountMissionHandler).
 *
 * Stability has no effect on this system. Resistance drives role selection and tiering.
 */
public final class EnemyEncounterSpawner {

    private EnemyEncounterSpawner() {
    }

    /**
     * Checks whether a surface position at (x, y, z) is a valid, dry-land ground spawn location
     * (strictly non-water, non-liquid, with solid footing and clear headroom).
     */
    public static boolean isValidDryLandSpawn(ServerLevel level, int x, int z, int y) {
        if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight() - 1) {
            return false;
        }

        BlockPos spawnPos = new BlockPos(x, y, z);
        BlockPos groundPos = spawnPos.below();
        BlockPos headPos = spawnPos.above();

        // Check ground block (must have solid non-liquid footing)
        FluidState groundFluid = level.getFluidState(groundPos);
        if (!groundFluid.isEmpty()) return false;
        BlockState groundState = level.getBlockState(groundPos);
        if (groundState.isAir() || groundState.liquid()) return false;

        // Check feet position (must not be in liquid or suffocating solid block)
        FluidState spawnFluid = level.getFluidState(spawnPos);
        if (!spawnFluid.isEmpty()) return false;
        BlockState spawnState = level.getBlockState(spawnPos);
        if (spawnState.liquid() || spawnState.blocksMotion()) return false;

        // Check head position (clear headroom)
        FluidState headFluid = level.getFluidState(headPos);
        if (!headFluid.isEmpty()) return false;
        BlockState headState = level.getBlockState(headPos);
        if (headState.liquid() || headState.blocksMotion()) return false;

        return true;
    }

    /**
     * Attempts to find a valid dry-land surface spawn Y coordinate at (x, z).
     * Returns the valid spawn Y, or Integer.MIN_VALUE if the column is water, liquid, or obstructed.
     */
    public static int findDryLandSurfaceY(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (isValidDryLandSpawn(level, x, z, y)) {
            return y;
        }
        int worldSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (worldSurfaceY != y && isValidDryLandSpawn(level, x, z, worldSurfaceY)) {
            return worldSurfaceY;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Spawns a wandering roaming encounter in enemy territory outside an active war.
     * Managed exclusively by ExplorationSpawnManager and RoamingEntityTracker.
     */
    public static int spawnWanderingEncounter(
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

        List<Object> rolePool = buildRolePool(faction, tier);
        if (rolePool.isEmpty()) {
            return 0;
        }

        int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;
        final int SCATTER = 12;

        int spawned = 0;
        for (int i = 0; i < encounterSize; i++) {
            Object role = selectWeightedRole(rolePool, level.getRandom());
            if (role == null) continue;

            int spawnX = originBlockX;
            int spawnZ = originBlockZ;
            int spawnY = Integer.MIN_VALUE;

            // Attempt to scatter onto valid dry ground within SCATTER radius
            for (int attempt = 0; attempt < 8; attempt++) {
                int testX = Math.clamp(originBlockX + level.getRandom().nextInt(2 * SCATTER + 1) - SCATTER, subMinX, subMaxX);
                int testZ = Math.clamp(originBlockZ + level.getRandom().nextInt(2 * SCATTER + 1) - SCATTER, subMinZ, subMaxZ);
                int testY = findDryLandSurfaceY(level, testX, testZ);
                if (testY != Integer.MIN_VALUE) {
                    spawnX = testX;
                    spawnZ = testZ;
                    spawnY = testY;
                    break;
                }
            }

            // Fallback: check origin if scatter attempts landed in water
            if (spawnY == Integer.MIN_VALUE) {
                spawnY = findDryLandSurfaceY(level, originBlockX, originBlockZ);
                spawnX = originBlockX;
                spawnZ = originBlockZ;
            }

            // If still in water or invalid, skip this mob to prevent spawning in water
            if (spawnY == Integer.MIN_VALUE) {
                continue;
            }

            Entity entity = resolveEntity(faction, role, level);
            if (entity == null) continue;

            entity.moveTo(spawnX + 0.5D, spawnY, spawnZ + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);

            if (entity instanceof Mob mob) {
                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(new BlockPos(spawnX, spawnY, spawnZ)), MobSpawnType.EVENT, null);
            }

            if (level.addFreshEntity(entity)) {
                spawned++;
                if (entity instanceof Mob mob) {
                    mob.addTag("warfront_roaming");
                    mob.getPersistentData().putBoolean("warfront_roaming", true);
                    mob.getPersistentData().putBoolean("isMissionMob", false);
                    mob.getPersistentData().putInt("originRegionX", regionX);
                    mob.getPersistentData().putInt("originRegionZ", regionZ);
                    mob.getPersistentData().putInt("originSubX", subX);
                    mob.getPersistentData().putInt("originSubZ", subZ);
                    mob.getPersistentData().putInt("faction", faction.id());
                    mob.getPersistentData().putString("targetRoleName", role.toString());

                    RoamingEntityTracker.registerWandering(mob, regionX, regionZ, subX, subZ, faction);
                }
            }
        }

        return spawned;
    }

    /**
     * Spawns a mission wave encounter tied to a specific active mission instance.
     * Managed exclusively by KillCountMissionHandler and MissionEntityTracker.
     */
    public static int spawnMissionEncounter(
            ServerLevel level,
            int regionX,
            int regionZ,
            int subX,
            int subZ,
            Faction faction,
            float resistance,
            int originBlockX,
            int originBlockZ,
            UUID missionInstanceId,
            String targetRoleName) {

        if (missionInstanceId == null) {
            return 0;
        }

        EnemyResistanceTier tier = EnemyResistanceTier.fromResistance(resistance);
        int encounterSize = determineEncounterSize(tier, level.getRandom());

        List<Object> rolePool = buildRolePool(faction, tier);
        if (rolePool.isEmpty()) {
            return 0;
        }

        int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;
        final int SCATTER = 12;

        int spawned = 0;
        for (int i = 0; i < encounterSize; i++) {
            Object role = selectWeightedRole(rolePool, level.getRandom());
            if (role == null) continue;

            int spawnX = originBlockX;
            int spawnZ = originBlockZ;
            int spawnY = Integer.MIN_VALUE;

            // Attempt to scatter onto valid dry ground within SCATTER radius
            for (int attempt = 0; attempt < 8; attempt++) {
                int testX = Math.clamp(originBlockX + level.getRandom().nextInt(2 * SCATTER + 1) - SCATTER, subMinX, subMaxX);
                int testZ = Math.clamp(originBlockZ + level.getRandom().nextInt(2 * SCATTER + 1) - SCATTER, subMinZ, subMaxZ);
                int testY = findDryLandSurfaceY(level, testX, testZ);
                if (testY != Integer.MIN_VALUE) {
                    spawnX = testX;
                    spawnZ = testZ;
                    spawnY = testY;
                    break;
                }
            }

            // Fallback: check origin if scatter attempts landed in water
            if (spawnY == Integer.MIN_VALUE) {
                spawnY = findDryLandSurfaceY(level, originBlockX, originBlockZ);
                spawnX = originBlockX;
                spawnZ = originBlockZ;
            }

            // If still in water or invalid, skip this mob to prevent spawning in water
            if (spawnY == Integer.MIN_VALUE) {
                continue;
            }

            Entity entity = resolveEntity(faction, role, level);
            if (entity == null) continue;

            entity.moveTo(spawnX + 0.5D, spawnY, spawnZ + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);

            if (entity instanceof Mob mob) {
                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(new BlockPos(spawnX, spawnY, spawnZ)), MobSpawnType.EVENT, null);
            }

            if (level.addFreshEntity(entity)) {
                spawned++;
                if (entity instanceof Mob mob) {
                    mob.addTag("warfront_mission");
                    mob.addTag("warfront_sub_" + regionX + "_" + regionZ + "_" + subX + "_" + subZ);
                    mob.getPersistentData().putBoolean("warfront_roaming", true);
                    mob.getPersistentData().putBoolean("isMissionMob", true);
                    mob.getPersistentData().putUUID("missionInstanceId", missionInstanceId);
                    mob.getPersistentData().putInt("originRegionX", regionX);
                    mob.getPersistentData().putInt("originRegionZ", regionZ);
                    mob.getPersistentData().putInt("originSubX", subX);
                    mob.getPersistentData().putInt("originSubZ", subZ);
                    mob.getPersistentData().putInt("faction", faction.id());
                    mob.getPersistentData().putString("targetRoleName", targetRoleName != null ? targetRoleName : role.toString());
                    mob.getPersistentData().putLong("spawnGameTime", level.getGameTime());

                    MissionEntityTracker.registerMissionMob(mob, missionInstanceId, regionX, regionZ, subX, subZ, faction);
                }
            }
        }

        return spawned;
    }

    /** 8x8 area spread offsets for squad members to hold ground in formation without overcrowding. */
    private static final int[][] SQUAD_SPREAD_OFFSETS = new int[][] {
            {  0,  0 }, // Center (Squad leader)
            { -2, -2 },
            {  2, -2 },
            { -2,  2 },
            {  2,  2 },
            {  0, -3 },
            {  0,  3 },
            { -3,  0 },
            {  3,  0 },
            { -3, -3 },
            {  3, -3 },
            { -3,  3 },
            {  3,  3 },
            { -1,  2 },
            {  1, -2 },
            {  2, -1 }
    };

    /**
     * Spawns an Attack Roamer squad of Pillagers at a border location, configured to march toward
     * target destination coordinates (destX, destZ) on the frontline.
     *
     * @return List of spawned Mob entities in this squad.
     */
    public static List<Mob> spawnPillagerAttackRoamerSquad(
            ServerLevel level,
            int regionX,
            int regionZ,
            float resistance,
            int borderX,
            int borderZ,
            int destX,
            int destZ,
            UUID squadId) {

        if (squadId == null) {
            return List.of();
        }

        EnemyResistanceTier tier = EnemyResistanceTier.fromResistance(resistance);
        int squadSize = Math.clamp(determineEncounterSize(tier, level.getRandom()), 4, 14);

        List<Object> rolePool = buildPillagerPool(tier);
        if (rolePool.isEmpty()) {
            return List.of();
        }

        List<Mob> spawnedMobs = new ArrayList<>();
        final int SCATTER = 4; // Tight squad formation at border

        boolean hasCommander = false;
        for (int i = 0; i < squadSize; i++) {
            Object role = null;

            // In HIGH / EXTREME tiers, the squad leader (at center {0, 0}) has a high chance (65%) to be a Commander
            if (i == 0 && (tier == EnemyResistanceTier.HIGH || tier == EnemyResistanceTier.EXTREME)) {
                if (level.getRandom().nextFloat() < 0.65F) {
                    role = PillagerEnemyRole.COMMANDER;
                    hasCommander = true;
                }
            }

            if (role == null) {
                role = selectWeightedRole(rolePool, level.getRandom());
                // Avoid having multiple commanders in a single squad
                if (role == PillagerEnemyRole.COMMANDER) {
                    if (hasCommander) {
                        // Replace duplicate commander with an alternative combatant from the pool
                        Object alt = null;
                        for (int retry = 0; retry < 5; retry++) {
                            alt = selectWeightedRole(rolePool, level.getRandom());
                            if (alt != PillagerEnemyRole.COMMANDER) {
                                break;
                            }
                        }
                        role = (alt != null && alt != PillagerEnemyRole.COMMANDER) ? alt : PillagerEnemyRole.RANGED;
                    } else {
                        hasCommander = true;
                    }
                }
            }

            if (role == null) continue;

            int spawnX = borderX;
            int spawnZ = borderZ;
            int spawnY = Integer.MIN_VALUE;

            for (int attempt = 0; attempt < 8; attempt++) {
                int testX = borderX + level.getRandom().nextInt(2 * SCATTER + 1) - SCATTER;
                int testZ = borderZ + level.getRandom().nextInt(2 * SCATTER + 1) - SCATTER;
                int testY = findDryLandSurfaceY(level, testX, testZ);
                if (testY != Integer.MIN_VALUE) {
                    spawnX = testX;
                    spawnZ = testZ;
                    spawnY = testY;
                    break;
                }
            }

            if (spawnY == Integer.MIN_VALUE) {
                spawnY = findDryLandSurfaceY(level, borderX, borderZ);
                spawnX = borderX;
                spawnZ = borderZ;
            }

            if (spawnY == Integer.MIN_VALUE) {
                continue;
            }

            Entity entity = resolveEntity(Faction.PILLAGER_CONQUERORS, role, level);
            if (!(entity instanceof Mob mob)) {
                continue;
            }

            mob.moveTo(spawnX + 0.5D, spawnY, spawnZ + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(new BlockPos(spawnX, spawnY, spawnZ)), MobSpawnType.EVENT, null);

            // Configure AI goals:
            // 1. Remove random wander goals so they don't wander randomly away from the march corridor
            mob.goalSelector.getAvailableGoals().removeIf(g ->
                    g.getGoal() instanceof net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
                    || g.getGoal() instanceof net.minecraft.world.entity.ai.goal.RandomStrollGoal);

            int[] offset = SQUAD_SPREAD_OFFSETS[i % SQUAD_SPREAD_OFFSETS.length];
            int mobDestX = destX + offset[0];
            int mobDestZ = destZ + offset[1];
            int mobDestY = findDryLandSurfaceY(level, mobDestX, mobDestZ);
            if (mobDestY == Integer.MIN_VALUE) {
                mobDestX = destX;
                mobDestZ = destZ;
            }

            // 2. Add Phase 1 (March to destination) and Phase 3 (Hold ground in 8x8 spread formation)
            mob.goalSelector.addGoal(2, new com.warfront.ai.goal.AdvanceToLocationGoal(mob, mobDestX, mobDestZ, 0.42D));
            mob.goalSelector.addGoal(3, new com.warfront.ai.goal.HoldGroundGoal(mob, mobDestX, mobDestZ));

            // 3. Add Phase 2 (Target & attack players)
            mob.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                    mob, net.minecraft.world.entity.player.Player.class, true));

            if (level.addFreshEntity(mob)) {
                mob.addTag("warfront_attack_roamer");
                mob.addTag("warfront_squad_" + squadId);
                mob.getPersistentData().putBoolean("warfront_roaming", true);
                mob.getPersistentData().putBoolean("isMissionMob", false);
                mob.getPersistentData().putBoolean("isAttackRoamer", true);
                mob.getPersistentData().putUUID("squadId", squadId);
                mob.getPersistentData().putInt("originRegionX", regionX);
                mob.getPersistentData().putInt("originRegionZ", regionZ);
                mob.getPersistentData().putInt("faction", Faction.PILLAGER_CONQUERORS.id());
                mob.getPersistentData().putString("targetRoleName", role.toString());
                mob.getPersistentData().putLong("spawnGameTime", level.getGameTime());

                spawnedMobs.add(mob);
            }
        }

        return spawnedMobs;
    }

    private static List<Object> buildRolePool(Faction faction, EnemyResistanceTier tier) {
        if (faction == Faction.ZOMBIE_HORDE) {
            return buildZombiePool(tier);
        } else if (faction == Faction.PILLAGER_CONQUERORS) {
            return buildPillagerPool(tier);
        }
        return List.of();
    }

    private static int determineEncounterSize(EnemyResistanceTier tier, RandomSource random) {
        int min = tier.minSpawn();
        int max = tier.maxSpawn();
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
    }

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

    private static Object selectWeightedRole(List<Object> pool, RandomSource random) {
        if (pool.isEmpty()) return null;
        return pool.get(random.nextInt(pool.size()));
    }

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
