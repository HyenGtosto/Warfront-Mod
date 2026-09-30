package com.warfront.mission;

import com.warfront.region.Faction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Interface defining in-war mission lifecycle and execution logic.
 */
public interface WarMissionHandler {

    /**
     * Called when a player is physically present within an active mission subregion.
     * Handles in-war enemy generation and wave reinforcements.
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
            String mobRoleName
    );

    /**
     * Called when the active mission campaign ends, is cancelled, or completes, to perform cleanup.
     */
    void onCleanup(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    );
}
