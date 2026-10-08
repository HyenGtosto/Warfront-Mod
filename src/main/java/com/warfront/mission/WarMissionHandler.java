package com.warfront.mission;

import com.warfront.region.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Interface defining in-war mission lifecycle and execution logic.
 * Extends {@link MissionObjectiveHandler} for unified polymorphic execution.
 */
public interface WarMissionHandler extends MissionObjectiveHandler {

    /**
     * Called when a player is physically present within an active mission subregion.
     * Handles in-war enemy generation and wave reinforcements.
     */
    @Override
    void onPlayerInSubregion(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            ServerPlayer player
    );

    /**
     * Legacy signature without Mob parameter.
     */
    boolean onEntityKilled(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            Faction mobFaction,
            String mobRoleName
    );

    @Override
    default boolean onEntityKilled(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            Faction mobFaction,
            String mobRoleName,
            Mob mob
    ) {
        return onEntityKilled(level, regionX, regionZ, subX, subZ, progress, mobFaction, mobRoleName);
    }

    @Override
    default boolean onBlockBroken(
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

    /**
     * Called when the active mission campaign ends, is cancelled, or completes, to perform cleanup.
     */
    @Override
    void onCleanup(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    );
}

