package com.warfront.mission.easy;

import com.warfront.entity.ModEntities;
import com.warfront.entity.PillagerMarksmanEntity;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.mission.MissionObjectiveHandler;
import com.warfront.mission.WeightedMissionSelector;
import com.warfront.mission.site.MissionSiteSnapshot;
import com.warfront.mission.site.MissionSiteSnapshotManager;
import com.warfront.mission.site.TemporaryStructureBuilder;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.spawn.EnemyEncounterSpawner;
import com.warfront.spawn.ExplorationSpawnManager;
import com.warfront.spawn.MissionEntityTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Objective handler for {@link com.warfront.mission.MissionType#SCOUT_NETWORK}.
 *
 * Constructs 2–3 distributed observation nests across the sector, each guarded by a
 * sniper Marksman. Destroying all observation relay nodes completes the objective.
 */
public final class ScoutNetworkMissionHandler implements MissionObjectiveHandler {

    private static final ScoutNetworkMissionHandler INSTANCE = new ScoutNetworkMissionHandler();

    private ScoutNetworkMissionHandler() {
    }

    public static ScoutNetworkMissionHandler getInstance() {
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

        // If observation network has already been constructed, do not build again
        if (!progress.trackedBlockPositions().isEmpty()) {
            return;
        }

        RegionData regions = RegionData.get(level);
        long missionSeed = (regions != null) ? regions.calculateMissionSeed(regionX, regionZ) : 0L;
        long siteSeed = missionSeed ^ (subX * 104729L) ^ (subZ * 224737L);

        int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;

        UUID instanceId = progress.missionInstanceId();
        MissionSiteSnapshot snapshot = MissionSiteSnapshotManager.getOrCreate(instanceId, regionX, regionZ, subX, subZ);

        int targetNestCount = Math.max(3, progress.targetProgress());
        int generatedNests = 0;

        int[][] sectorRanges = {
                { 8, 28, 8, 28 },   // NW sector
                { 36, 56, 8, 28 },  // NE sector
                { 12, 36, 36, 56 }, // SW sector
                { 36, 56, 36, 56 }  // SE sector
        };

        for (int i = 0; i < targetNestCount; i++) {
            int[] sector = sectorRanges[i % sectorRanges.length];
            BlockPos chosenAnchor = null;

            for (int attempt = 0; attempt < 16; attempt++) {
                long hashX = WeightedMissionSelector.mix64(siteSeed ^ (i * 0x9E3779B9L) ^ (attempt * 1013904223L));
                long hashZ = WeightedMissionSelector.mix64(siteSeed ^ ((i + 7) * 0x85EBCA6BL) ^ (attempt * 1664525L));

                int spanX = Math.max(1, sector[1] - sector[0]);
                int spanZ = Math.max(1, sector[3] - sector[2]);
                int localX = sector[0] + (int) ((hashX & 0x7FFFFFFF) % spanX);
                int localZ = sector[2] + (int) ((hashZ & 0x7FFFFFFF) % spanZ);

                int worldX = subMinX + localX;
                int worldZ = subMinZ + localZ;

                int surfaceY = EnemyEncounterSpawner.findDryLandSurfaceY(level, worldX, worldZ);
                if (surfaceY != Integer.MIN_VALUE) {
                    BlockPos candidate = new BlockPos(worldX, surfaceY, worldZ);
                    boolean tooClose = false;
                    for (BlockPos existing : progress.trackedBlockPositions()) {
                        if (existing.distSqr(candidate) < 144) { // at least 12 blocks distance
                            tooClose = true;
                            break;
                        }
                    }
                    if (!tooClose) {
                        chosenAnchor = candidate;
                        break;
                    }
                }
            }

            // Fallback if specific sector was entirely water/obstructed
            if (chosenAnchor == null) {
                chosenAnchor = com.warfront.mission.site.MissionSiteAnchorResolver.resolveDryLandAnchor(
                        level, regionX, regionZ, subX, subZ, siteSeed ^ (i * 7919L));
            }

            if (chosenAnchor != null) {
                BlockPos relayPos = TemporaryStructureBuilder.buildScoutLookoutNest(level, chosenAnchor, snapshot);
                progress.trackedBlockPositions().add(relayPos);

                // Spawn sniper Marksman inside/on nest
                Mob sniper = ModEntities.PILLAGER_MARKSMAN.get().create(level);
                if (sniper != null) {
                    sniper.moveTo(chosenAnchor.getX() + 0.5D, chosenAnchor.getY() + 3.5D, chosenAnchor.getZ() + 0.5D, 0.0F, 0.0F);
                    net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(sniper, level, level.getCurrentDifficultyAt(relayPos), MobSpawnType.EVENT, null);
                    if (level.addFreshEntity(sniper)) {
                        sniper.addTag("warfront_mission");
                        sniper.getPersistentData().putBoolean("isMissionMob", true);
                        sniper.getPersistentData().putUUID("missionInstanceId", instanceId);
                        sniper.getPersistentData().putInt("originRegionX", regionX);
                        sniper.getPersistentData().putInt("originRegionZ", regionZ);
                        sniper.getPersistentData().putInt("originSubX", subX);
                        sniper.getPersistentData().putInt("originSubZ", subZ);
                        sniper.getPersistentData().putInt("faction", progress.targetFaction().id());

                        MissionEntityTracker.registerMissionMob(sniper, instanceId, regionX, regionZ, subX, subZ, progress.targetFaction());
                        progress.trackedEntityUuids().add(sniper.getUUID());
                    }
                }
                generatedNests++;
            }
        }

        if (generatedNests > 0) {
            progress.setTargetProgress(generatedNests);
            progress.setCurrentProgress(0);
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

        if (mob != null && progress.trackedEntityUuids().remove(mob.getUUID())) {
            RegionData regions = RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
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
        if (progress == null || progress.isCompleted()) {
            return false;
        }

        if (progress.trackedBlockPositions().remove(pos)) {
            level.playSound(null, pos, SoundEvents.ANVIL_DESTROY, SoundSource.BLOCKS, 1.2F, 1.1F);
            level.sendParticles(ParticleTypes.EXPLOSION, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 3, 0.2D, 0.2D, 0.2D, 0.05D);

            progress.incrementProgress();
            RegionData regions = RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
            }

            if (progress.currentProgress() >= progress.targetProgress() || progress.trackedBlockPositions().isEmpty()) {
                ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
            } else {
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
            }
            return true;
        }
        return false;
    }

    @Override
    public void onCleanup(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        MissionSiteSnapshotManager.restoreAndRemove(level, progress.missionInstanceId());
    }
}
