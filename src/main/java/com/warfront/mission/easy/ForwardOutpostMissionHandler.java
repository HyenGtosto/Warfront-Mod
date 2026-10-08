package com.warfront.mission.easy;

import com.warfront.entity.ModEntities;
import com.warfront.entity.PillagerMarksmanEntity;
import com.warfront.entity.PillagerWarriorEntity;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.mission.MissionObjectiveHandler;
import com.warfront.mission.site.MissionSiteAnchorResolver;
import com.warfront.mission.site.MissionSiteSnapshot;
import com.warfront.mission.site.MissionSiteSnapshotManager;
import com.warfront.mission.site.TemporaryStructureBuilder;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
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
 * Objective handler for {@link com.warfront.mission.MissionType#FORWARD_OUTPOST}.
 *
 * Constructs a temporary palisade watchtower containing a central Command Core block
 * guarded by sniper and shock infantry defenders. Demolishing the core secures the sector.
 */
public final class ForwardOutpostMissionHandler implements MissionObjectiveHandler {

    private static final ForwardOutpostMissionHandler INSTANCE = new ForwardOutpostMissionHandler();

    private ForwardOutpostMissionHandler() {
    }

    public static ForwardOutpostMissionHandler getInstance() {
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

        // If watchtower site has already been constructed, do not build again
        if (progress.missionSiteAnchor() != null && !progress.trackedBlockPositions().isEmpty()) {
            return;
        }

        RegionData regions = RegionData.get(level);
        long missionSeed = (regions != null) ? regions.calculateMissionSeed(regionX, regionZ) : 0L;

        // 1. Resolve deterministic dry-land anchor
        BlockPos anchor = MissionSiteAnchorResolver.resolveDryLandAnchor(level, regionX, regionZ, subX, subZ, missionSeed);
        progress.setMissionSiteAnchor(anchor);

        UUID instanceId = progress.missionInstanceId();
        MissionSiteSnapshot snapshot = MissionSiteSnapshotManager.getOrCreate(instanceId, regionX, regionZ, subX, subZ);

        // 2. Build temporary watchtower structure with stepped foundations
        java.util.List<BlockPos> outpostBlocks = TemporaryStructureBuilder.buildForwardOutpost(level, anchor, snapshot);
        progress.trackedBlockPositions().clear();
        progress.trackedBlockPositions().addAll(outpostBlocks);

        int totalBlocks = outpostBlocks.size();
        int targetDestroyed = (int) Math.ceil(totalBlocks * 0.60);
        progress.setTargetProgress(targetDestroyed);
        progress.setCurrentProgress(0);

        // 3. Spawn garrison defenders: 1 Marksman elevated, 3 Warriors ground perimeter
        Mob sniper = ModEntities.PILLAGER_MARKSMAN.get().create(level);
        if (sniper != null) {
            sniper.moveTo(anchor.getX() + 0.5D, anchor.getY() + 4.5D, anchor.getZ() + 0.5D, 0.0F, 0.0F);
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(sniper, level, level.getCurrentDifficultyAt(anchor), MobSpawnType.EVENT, null);
            if (level.addFreshEntity(sniper)) {
                setupMob(sniper, instanceId, regionX, regionZ, subX, subZ, progress);
            }
        }

        int[][] guardOffsets = {{-4, -2}, {4, -2}, {0, 4}};
        for (int[] off : guardOffsets) {
            Mob guard = ModEntities.PILLAGER_WARRIOR.get().create(level);
            if (guard != null) {
                int gx = anchor.getX() + off[0];
                int gz = anchor.getZ() + off[1];
                int gy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz);
                guard.moveTo(gx + 0.5D, gy, gz + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(guard, level, level.getCurrentDifficultyAt(new BlockPos(gx, gy, gz)), MobSpawnType.EVENT, null);
                if (level.addFreshEntity(guard)) {
                    setupMob(guard, instanceId, regionX, regionZ, subX, subZ, progress);
                }
            }
        }

        if (regions != null) {
            regions.setDirty();
        }
        ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
    }

    private void setupMob(Mob mob, UUID instanceId, int rx, int rz, int sx, int sz, ActiveCampaignMissionManager.ActiveSubRegionProgress progress) {
        mob.addTag("warfront_mission");
        mob.getPersistentData().putBoolean("isMissionMob", true);
        mob.getPersistentData().putUUID("missionInstanceId", instanceId);
        mob.getPersistentData().putInt("originRegionX", rx);
        mob.getPersistentData().putInt("originRegionZ", rz);
        mob.getPersistentData().putInt("originSubX", sx);
        mob.getPersistentData().putInt("originSubZ", sz);
        mob.getPersistentData().putInt("faction", progress.targetFaction().id());

        MissionEntityTracker.registerMissionMob(mob, instanceId, rx, rz, sx, sz, progress.targetFaction());
        progress.trackedEntityUuids().add(mob.getUUID());
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
            level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0F, 0.9F);
            level.sendParticles(ParticleTypes.CRIT, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 3, 0.2D, 0.2D, 0.2D, 0.1D);

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
