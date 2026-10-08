package com.warfront.mission;

import com.warfront.Warfront;
import com.warfront.network.ActiveMissionHudPayload;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.spawn.EnemyEncounterSpawner;
import com.warfront.spawn.ExplorationSpawnManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-contained war mission handler for {@link MissionType#KILL_COUNT}.
 *
 * Handles in-world active mission enemy generation, kill quota tracking,
 * and victory resolution upon satisfying the mission target count.
 */
public final class KillCountMissionHandler implements WarMissionHandler {

    private static final KillCountMissionHandler INSTANCE = new KillCountMissionHandler();

    /** Cooldown (in ticks) between enemy reinforcement waves inside the active subregion (35 seconds = 700 ticks). */
    private static final long REINFORCEMENT_COOLDOWN_TICKS = 700L;

    /** Maximum concurrent living mission enemies allowed before reinforcement waves are blocked. */
    public static final int MAX_LIVING_MISSION_ENEMIES = 8;

    /** Tracks last spawn game time per subregion key: (regionPos << 2) | bit */
    private static final Map<Long, Long> SUBREGION_MISSION_SPAWN_TIMERS = new ConcurrentHashMap<>();

    private KillCountMissionHandler() {
    }

    public static KillCountMissionHandler getInstance() {
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
        // Enemies are provided by SubregionPatrolManager (boosted to 4 squads, 20s cooldown).
        // No standalone wave spawning to eliminate mob-tag mismatch and duplicated entities.
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

        progress.incrementKills();
        RegionData regions = RegionData.get(level);
        if (regions != null) {
            regions.setDirty();
        }
        Warfront.LOGGER.debug("Kill count progress for Region ({}, {}) Sub ({}, {}): {}/{}",
                regionX, regionZ, subX, subZ, progress.currentKills(), progress.requiredKills());

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
        int bit = subZ * 2 + subX;
        long subKey = (ChunkPos.asLong(regionX, regionZ) << 2) | (bit & 3);
        SUBREGION_MISSION_SPAWN_TIMERS.remove(subKey);
    }
}
