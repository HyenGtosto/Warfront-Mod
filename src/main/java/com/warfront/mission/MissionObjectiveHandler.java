package com.warfront.mission;

import com.warfront.region.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Interface defining polymorphic in-world lifecycle, progression, and event execution logic
 * for specific mission types and objective archetypes.
 */
public interface MissionObjectiveHandler {

    /**
     * Called when a player is physically present within an active mission subregion.
     * Handles in-world entity generation, temporary site construction, and periodic objective pacing.
     */
    void onPlayerInSubregion(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            ServerPlayer player
    );

    /**
     * Called when a Warfront enemy entity is killed in or around the active subregion.
     *
     * @return true if the kill contributed to mission progress
     */
    boolean onEntityKilled(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            Faction mobFaction,
            String mobRoleName,
            Mob mob
    );

    /**
     * Called when a block is broken within or adjacent to the active mission subregion.
     *
     * @return true if the broken block contributed to mission progress
     */
    boolean onBlockBroken(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            BlockPos pos,
            BlockState state,
            ServerPlayer player
    );

    /**
     * Called when the active mission campaign ends, is cancelled, or completes, to perform
     * temporary site restoration, prop removal, and timer cleanup.
     */
    void onCleanup(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    );
}
