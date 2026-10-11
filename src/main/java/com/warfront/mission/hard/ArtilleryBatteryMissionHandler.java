package com.warfront.mission.hard;

import com.warfront.block.WarfrontBlocks;
import com.warfront.entity.ModEntities;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hard-tier mission handler for {@link com.warfront.mission.MissionType#ARTILLERY_BATTERY}.
 *
 * Spawns the authentic Pillager Heavy Mortar Emplacement structure template containing 3
 * functional automated mortars that acquire and shell sky-exposed players across the region.
 *
 * Guards: 1 Warrior and 1 Marksman spawned near each mortar.
 * Victory Condition: Destruction of all 3 mortar blocks in the emplacement.
 */
public final class ArtilleryBatteryMissionHandler implements MissionObjectiveHandler {

    private static final ArtilleryBatteryMissionHandler INSTANCE = new ArtilleryBatteryMissionHandler();

    private final ConcurrentHashMap<UUID, BatteryMissionData> batteryDataMap = new ConcurrentHashMap<>();

    public static class BatteryMissionData {
        final BlockPos anchor;
        final List<BlockPos> remainingMortars;
        final int totalMortars;

        BatteryMissionData(BlockPos anchor, List<BlockPos> mortarPositions) {
            this.anchor = anchor;
            this.remainingMortars = new ArrayList<>(mortarPositions);
            this.totalMortars = mortarPositions.size();
        }
    }

    private ArtilleryBatteryMissionHandler() {
    }

    public static ArtilleryBatteryMissionHandler getInstance() {
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

        UUID instanceId = progress.missionInstanceId();
        BatteryMissionData data = batteryDataMap.get(instanceId);

        // 1. Initial Site Construction & Defender Generation
        if (data == null) {
            RegionData regions = RegionData.get(level);
            long missionSeed = (regions != null) ? regions.calculateMissionSeed(regionX, regionZ) : 0L;

            BlockPos anchor = MissionSiteAnchorResolver.resolveDryLandAnchor(level, regionX, regionZ, subX, subZ, missionSeed);
            progress.setMissionSiteAnchor(anchor);

            MissionSiteSnapshot snapshot = MissionSiteSnapshotManager.getOrCreate(instanceId, regionX, regionZ, subX, subZ);
            TemporaryStructureBuilder.ArtilleryBatteryResult result = TemporaryStructureBuilder.buildArtilleryBattery(level, anchor, snapshot);

            data = new BatteryMissionData(anchor, result.mortarPositions());
            batteryDataMap.put(instanceId, data);

            progress.trackedBlockPositions().clear();
            progress.trackedBlockPositions().addAll(result.structureBlocks());
            progress.setTargetProgress(data.totalMortars); // Target is to destroy all mortars
            progress.setCurrentProgress(0);

            // Spawn guards: 1 Warrior and 1 Marksman near each mortar
            for (BlockPos mortarPos : result.mortarPositions()) {
                Mob warrior = ModEntities.PILLAGER_WARRIOR.get().create(level);
                if (warrior != null) {
                    warrior.moveTo(mortarPos.getX() + 1.5D, mortarPos.getY(), mortarPos.getZ() + 0.5D, 0.0F, 0.0F);
                    net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(warrior, level, level.getCurrentDifficultyAt(mortarPos), MobSpawnType.EVENT, null);
                    if (level.addFreshEntity(warrior)) {
                        setupMob(warrior, instanceId, regionX, regionZ, subX, subZ, progress);
                    }
                }

                Mob sniper = ModEntities.PILLAGER_MARKSMAN.get().create(level);
                if (sniper != null) {
                    sniper.moveTo(mortarPos.getX() - 1.5D, mortarPos.getY(), mortarPos.getZ() + 0.5D, 0.0F, 0.0F);
                    net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(sniper, level, level.getCurrentDifficultyAt(mortarPos), MobSpawnType.EVENT, null);
                    if (level.addFreshEntity(sniper)) {
                        setupMob(sniper, instanceId, regionX, regionZ, subX, subZ, progress);
                    }
                }
            }

            if (regions != null) {
                regions.setDirty();
            }
            ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
            return;
        }

        // 2. Periodic mortar block integrity check (detects explosive destruction)
        Iterator<BlockPos> iterator = data.remainingMortars.iterator();
        boolean changed = false;
        while (iterator.hasNext()) {
            BlockPos mPos = iterator.next();
            if (!level.getBlockState(mPos).is(WarfrontBlocks.MORTAR.get())) {
                iterator.remove();
                progress.setCurrentProgress(data.totalMortars - data.remainingMortars.size());
                changed = true;
            }
        }

        if (data.remainingMortars.isEmpty() || progress.currentProgress() >= progress.targetProgress()) {
            triggerVictory(level, regionX, regionZ, subX, subZ, progress, data, player);
        } else if (changed) {
            RegionData regions = RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
            }
            ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
        }
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

        BatteryMissionData data = batteryDataMap.get(progress.missionInstanceId());
        if (data == null) {
            return false;
        }

        // Check if a mortar block was destroyed
        if (data.remainingMortars.remove(pos) || (state.is(WarfrontBlocks.MORTAR.get()) && data.remainingMortars.remove(pos))) {
            progress.setCurrentProgress(data.totalMortars - data.remainingMortars.size());

            RegionData regions = RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
            }

            if (data.remainingMortars.isEmpty() || progress.currentProgress() >= progress.targetProgress()) {
                triggerVictory(level, regionX, regionZ, subX, subZ, progress, data, player);
            } else {
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
            }
            return true;
        }

        return false;
    }

    private void triggerVictory(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            BatteryMissionData data,
            ServerPlayer player
    ) {
        if (player != null) {
            player.sendSystemMessage(Component.literal("§a§l[WARFRONT] 🏆 Artillery Battery Silenced! All Mortars Destroyed."));
        } else {
            ActiveCampaignMissionManager.notifyAttackingPlayers(level, regionX, regionZ,
                    "§a§l[WARFRONT] 🏆 Artillery Battery Silenced! All Mortars Destroyed.");
        }

        ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
    }

    @Override
    public void onCleanup(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        batteryDataMap.remove(progress.missionInstanceId());

        if (!progress.isCompleted()) {
            MissionSiteSnapshotManager.restoreAndRemove(level, progress.missionInstanceId());
        }
    }
}
