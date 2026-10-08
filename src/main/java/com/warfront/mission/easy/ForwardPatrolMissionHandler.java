package com.warfront.mission.easy;

import com.warfront.entity.ModEntities;
import com.warfront.entity.PillagerScoutEntity;
import com.warfront.entity.PillagerWarriorEntity;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.mission.MissionObjectiveHandler;
import com.warfront.region.Faction;
import com.warfront.spawn.EnemyEncounterSpawner;
import com.warfront.spawn.ExplorationSpawnManager;
import com.warfront.spawn.MissionEntityTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Objective handler for {@link com.warfront.mission.MissionType#FORWARD_PATROL}.
 *
 * Spawns a cohesive light reconnaissance patrol squad of Pillager Scouts and Warriors.
 * Eliminating all tracked squad members secures the subregion.
 */
public final class ForwardPatrolMissionHandler implements MissionObjectiveHandler {

    private static final ForwardPatrolMissionHandler INSTANCE = new ForwardPatrolMissionHandler();

    private ForwardPatrolMissionHandler() {
    }

    public static ForwardPatrolMissionHandler getInstance() {
        return INSTANCE;
    }

    @Override
    public void onPlayerInSubregion(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            ServerPlayer player
    ) {
        if (progress == null || progress.isCompleted()) {
            return;
        }

        // If patrol squad has already been spawned, do not spawn another
        if (!progress.trackedEntityUuids().isEmpty()) {
            return;
        }

        // Determine spawn origin (18–26 blocks from player) on dry land
        int subMinX = regionX * com.warfront.region.RegionData.REGION_SIZE_BLOCKS + subX * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = regionZ * com.warfront.region.RegionData.REGION_SIZE_BLOCKS + subZ * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;

        int spawnX = -1;
        int spawnZ = -1;
        int spawnY = Integer.MIN_VALUE;

        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = level.getRandom().nextDouble() * 2 * Math.PI;
            double dist = 18.0 + level.getRandom().nextDouble() * 8.0;
            int cx = Math.clamp((int) (player.getBlockX() + Math.cos(angle) * dist), subMinX + 4, subMaxX - 4);
            int cz = Math.clamp((int) (player.getBlockZ() + Math.sin(angle) * dist), subMinZ + 4, subMaxZ - 4);

            int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, cx, cz);
            if (y != Integer.MIN_VALUE) {
                spawnX = cx;
                spawnZ = cz;
                spawnY = y;
                break;
            }
        }

        if (spawnY == Integer.MIN_VALUE) {
            return; // No suitable dry land found yet
        }

        // Spawn a cohesive 5-mob patrol squad: 2 Scouts and 3 Warriors
        int squadSize = Math.max(3, progress.targetProgress());
        int spawnedCount = 0;
        UUID instanceId = progress.missionInstanceId();

        for (int i = 0; i < squadSize; i++) {
            Mob mob;
            if (i < 2) {
                mob = ModEntities.PILLAGER_SCOUT.get().create(level);
            } else {
                mob = ModEntities.PILLAGER_WARRIOR.get().create(level);
            }

            if (mob == null) continue;

            int ox = spawnX + (i % 3) * 2 - 2;
            int oz = spawnZ + (i / 3) * 2 - 2;
            int oy = EnemyEncounterSpawner.findDryLandSurfaceY(level, ox, oz);
            if (oy == Integer.MIN_VALUE) oy = spawnY;

            mob.moveTo(ox + 0.5D, oy, oz + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(new BlockPos(ox, oy, oz)), MobSpawnType.EVENT, null);

            if (level.addFreshEntity(mob)) {
                mob.addTag("warfront_mission");
                mob.getPersistentData().putBoolean("isMissionMob", true);
                mob.getPersistentData().putUUID("missionInstanceId", instanceId);
                mob.getPersistentData().putInt("originRegionX", regionX);
                mob.getPersistentData().putInt("originRegionZ", regionZ);
                mob.getPersistentData().putInt("originSubX", subX);
                mob.getPersistentData().putInt("originSubZ", subZ);
                mob.getPersistentData().putInt("faction", progress.targetFaction().id());

                MissionEntityTracker.registerMissionMob(mob, instanceId, regionX, regionZ, subX, subZ, progress.targetFaction());
                progress.trackedEntityUuids().add(mob.getUUID());
                spawnedCount++;
            }
        }

        if (spawnedCount > 0) {
            // Update authoritative target progress to actual spawned squad size
            progress.setTargetProgress(spawnedCount);
            com.warfront.region.RegionData regions = com.warfront.region.RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
            }
            ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
        }
    }

    @Override
    public boolean onEntityKilled(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            Faction mobFaction,
            String mobRoleName,
            Mob mob
    ) {
        if (progress == null || progress.isCompleted()) {
            return false;
        }

        UUID mobUuid = (mob != null) ? mob.getUUID() : null;
        if (mobUuid != null && progress.trackedEntityUuids().contains(mobUuid)) {
            progress.trackedEntityUuids().remove(mobUuid);
            progress.incrementProgress();

            com.warfront.region.RegionData regions = com.warfront.region.RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
            }

            if (progress.currentProgress() >= progress.targetProgress() || progress.trackedEntityUuids().isEmpty()) {
                ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
            } else {
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean onBlockBroken(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            BlockPos pos,
            BlockState state,
            ServerPlayer player
    ) {
        return false;
    }

    @Override
    public void onCleanup(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        // Tracked mobs are automatically handled by MissionEntityTracker (staggered terminal despawn)
    }
}
