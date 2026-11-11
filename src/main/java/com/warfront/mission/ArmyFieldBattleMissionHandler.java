package com.warfront.mission;

import com.warfront.Warfront;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import java.util.UUID;

/**
 * Unified field battle mission handler for Cluster Mobile Field Armies.
 *
 * Tracks hostile mob kills across the entire region footprint (not constrained to a single subregion quadrant).
 * When the required quota is met, resolves a decisive victory capturing the entire region and destroying the army.
 */
public final class ArmyFieldBattleMissionHandler implements WarMissionHandler {

    private static final ArmyFieldBattleMissionHandler INSTANCE = new ArmyFieldBattleMissionHandler();

    private ArmyFieldBattleMissionHandler() {
    }

    public static ArmyFieldBattleMissionHandler getInstance() {
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
        // Entity spawning mechanics will be integrated in the upcoming Phase 2 entity encounter subphase
    }

    @Override
    public boolean onEntityKilled(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            Faction mobFaction,
            String mobRoleName
    ) {
        return onEntityKilled(level, regionX, regionZ, subX, subZ, progress, mobFaction, mobRoleName, null);
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

        if (mobFaction != progress.targetFaction()) {
            return false;
        }

        // Must be physically inside the 128x128 region of the field battle
        if (mob != null) {
            int blockX = mob.getBlockX();
            int blockZ = mob.getBlockZ();
            int mobRegX = Math.floorDiv(blockX, RegionData.REGION_SIZE_BLOCKS);
            int mobRegZ = Math.floorDiv(blockZ, RegionData.REGION_SIZE_BLOCKS);

            if (mobRegX != regionX || mobRegZ != regionZ) {
                return false;
            }
        }

        progress.incrementKills();
        RegionData regions = RegionData.get(level);
        if (regions != null) {
            regions.setDirty();
        }

        Warfront.LOGGER.debug("Army Field Battle progress for Region ({}, {}): {}/{}",
                regionX, regionZ, progress.currentKills(), progress.requiredKills());

        if (progress.currentKills() >= progress.requiredKills()) {
            ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
        } else {
            ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
        }

        return true;
    }

    @Override
    public boolean onBlockBroken(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state,
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
        // Disengage tracked mission mobs
        if (progress != null) {
            for (UUID uuid : progress.trackedEntityUuids()) {
                net.minecraft.world.entity.Entity e = level.getEntity(uuid);
                if (e != null && e.isAlive()) {
                    com.warfront.spawn.MissionEntityTracker.registerMissionEntity(
                            e, progress.missionInstanceId(), regionX, regionZ, subX, subZ, progress.targetFaction());
                }
            }
            progress.trackedEntityUuids().clear();
        }
    }
}
