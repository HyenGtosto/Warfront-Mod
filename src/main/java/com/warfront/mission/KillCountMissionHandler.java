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
        if (progress == null || progress.isCompleted()) {
            return;
        }

        int remainingKills = progress.requiredKills() - progress.currentKills();
        if (remainingKills <= 0) {
            return;
        }

        // Enforce active living mob cap & squad pacing
        int livingCount = com.warfront.spawn.MissionEntityTracker.getLivingMissionMobCount(progress.missionInstanceId(), level);
        if (livingCount > 2) {
            return; // Player is still actively fighting current wave; wait until <= 2 enemies remain
        }
        if (livingCount >= MAX_LIVING_MISSION_ENEMIES) {
            return; // Hard cap reached
        }

        long gameTime = level.getGameTime();
        int bit = subZ * 2 + subX;
        long subKey = (ChunkPos.asLong(regionX, regionZ) << 2) | (bit & 3);

        Long lastSpawn = SUBREGION_MISSION_SPAWN_TIMERS.get(subKey);
        if (lastSpawn != null && (gameTime - lastSpawn) < REINFORCEMENT_COOLDOWN_TICKS) {
            return; // On cooldown
        }

        // Subregion block boundaries
        int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;

        int playerBlockX = player.getBlockX();
        int playerBlockZ = player.getBlockZ();

        // Find candidate spawn origin around the player (distance 16–28 blocks) on dry land
        int originX = -1;
        int originZ = -1;

        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = level.getRandom().nextDouble() * 2 * Math.PI;
            double dist = 16.0 + level.getRandom().nextDouble() * 12.0; // 16 to 28 blocks
            int cx = (int) (playerBlockX + Math.cos(angle) * dist);
            int cz = (int) (playerBlockZ + Math.sin(angle) * dist);

            // Clamp strictly within the active subregion bounds
            cx = Math.clamp(cx, subMinX, subMaxX);
            cz = Math.clamp(cz, subMinZ, subMaxZ);

            int spawnY = EnemyEncounterSpawner.findDryLandSurfaceY(level, cx, cz);
            if (spawnY != Integer.MIN_VALUE) {
                originX = cx;
                originZ = cz;
                break;
            }
        }

        if (originX == -1 || originZ == -1) {
            int px = Math.clamp(playerBlockX, subMinX, subMaxX);
            int pz = Math.clamp(playerBlockZ, subMinZ, subMaxZ);
            int py = EnemyEncounterSpawner.findDryLandSurfaceY(level, px, pz);
            if (py != Integer.MIN_VALUE) {
                originX = px;
                originZ = pz;
            } else {
                return; // Entire area is underwater or obstructed; skip wave to avoid spawning in water
            }
        }

        RegionData regions = RegionData.get(level);
        float resistance = regions.calculateEffectiveResistance(regionX, regionZ);

        int maxToSpawn = Math.min(6, Math.max(2, remainingKills - livingCount));
        int spawned = EnemyEncounterSpawner.spawnMissionEncounter(
                level,
                regionX, regionZ,
                subX, subZ,
                progress.targetFaction(),
                resistance,
                originX, originZ,
                progress.missionInstanceId(),
                progress.targetRoleName(),
                maxToSpawn
        );

        if (spawned > 0) {
            SUBREGION_MISSION_SPAWN_TIMERS.put(subKey, gameTime);
            Warfront.LOGGER.debug("Mission wave spawned ({} enemies) for Region ({}, {}) Sub ({}, {})",
                    spawned, regionX, regionZ, subX, subZ);
        }
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
            progress.setCompleted(true);
            onCleanup(level, regionX, regionZ, subX, subZ, progress);

            // Subregion Objective Completed -> Capture ONLY this subregion to HUMANITY
            if (regions != null) {
                regions.claimSubRegion(level, regionX, regionZ, subX, subZ, Faction.HUMANITY, 100.0F);

                String logMsg = String.format("§aMission Completed! Sub-region (%d, %d) in Region (%d, %d) secured.",
                        subX, subZ, regionX, regionZ);
                regions.addLog(level, logMsg);
                regions.setDirty();
            }
            Warfront.LOGGER.info("Subregion mission completed: Region ({}, {}) Sub ({}, {}). Captured to HUMANITY.",
                    regionX, regionZ, subX, subZ);

            com.warfront.network.RequestRegionMapPayload.notifyActiveMapTerminals(level);
        }

        // Broadcast instant HUD progress update to all players
        RegionData.SiegeCampaign siege = regions != null ? regions.getSiege(regionX, regionZ) : null;
        long remainingTicks = 0L;
        if (siege != null) {
            long elapsed = level.getGameTime() - siege.startTick();
            remainingTicks = Math.max(0L, siege.durationTicks() - elapsed);
        }
        boolean isDefense = (siege != null && siege.attacker() != Faction.HUMANITY);

        ActiveMissionHudPayload hudUpdate = new ActiveMissionHudPayload(
                !progress.isCompleted(),
                regionX, regionZ, subX, subZ,
                progress.displayName(),
                progress.objectiveDescription(),
                progress.currentProgress(),
                progress.targetProgress(),
                progress.formatProgressDisplay(),
                remainingTicks,
                progress.targetFaction().id(),
                isDefense
        );

        for (ServerPlayer player : level.players()) {
            player.connection.send(hudUpdate);
        }

        return true;
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
