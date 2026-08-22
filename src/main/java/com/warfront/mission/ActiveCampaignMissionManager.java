package com.warfront.mission;

import com.warfront.Warfront;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative mission execution manager.
 *
 * Tracks active campaign progress, processes enemy kills, and triggers subregion
 * capture upon mission completion.
 */
public final class ActiveCampaignMissionManager {

    /**
     * Active campaign missions: RegionKey → (SubRegionBit → ActiveSubRegionProgress)
     */
    private static final Map<Long, Map<Integer, ActiveSubRegionProgress>> ACTIVE_CAMPAIGN_MISSIONS = new ConcurrentHashMap<>();

    private ActiveCampaignMissionManager() {
    }

    public static class ActiveSubRegionProgress {
        private final int regionX;
        private final int regionZ;
        private final int subX;
        private final int subZ;
        private final Faction targetFaction;
        private final String targetRoleName;
        private final int requiredKills;
        private int currentKills;
        private boolean completed;

        public ActiveSubRegionProgress(int regionX, int regionZ, int subX, int subZ, Faction targetFaction, String targetRoleName, int requiredKills) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.subX = subX;
            this.subZ = subZ;
            this.targetFaction = targetFaction;
            this.targetRoleName = targetRoleName;
            this.requiredKills = requiredKills;
            this.currentKills = 0;
            this.completed = false;
        }

        public int subX() { return subX; }
        public int subZ() { return subZ; }
        public Faction targetFaction() { return targetFaction; }
        public String targetRoleName() { return targetRoleName; }
        public int requiredKills() { return requiredKills; }
        public int currentKills() { return currentKills; }
        public boolean isCompleted() { return completed; }
    }

    /**
     * Initializes active server-side campaign missions when an attack is launched.
     */
    public static void startCampaign(
            ServerLevel level,
            int regionX, int regionZ,
            Faction targetFaction,
            BaseType baseType,
            float resistance, float stability,
            int activeSubRegionsMask) {

        long regionKey = ChunkPos.asLong(regionX, regionZ);
        SubRegionMission[] generatedMissions = MissionProfile.generateForRegion(regionX, regionZ, targetFaction, baseType, resistance, stability);

        Map<Integer, ActiveSubRegionProgress> subMissions = ACTIVE_CAMPAIGN_MISSIONS.computeIfAbsent(regionKey, k -> new HashMap<>());

        for (int i = 0; i < 4; i++) {
            int subX = i % 2;
            int subZ = i / 2;
            int bit = subZ * 2 + subX;

            if ((activeSubRegionsMask & (1 << bit)) != 0) {
                SubRegionMission gen = generatedMissions[i];
                subMissions.put(bit, new ActiveSubRegionProgress(regionX, regionZ, subX, subZ, targetFaction, gen.targetRoleName(), gen.killTarget()));
            }
        }

        Warfront.LOGGER.info("Active server campaign initialized for Region ({}, {}) with {} active subregion missions.",
                regionX, regionZ, subMissions.size());
    }

    /**
     * Clears active server-side campaign progress for a region (e.g. on cancellation, completion, or expiration).
     */
    public static void clearCampaign(ServerLevel level, int regionX, int regionZ) {
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        ACTIVE_CAMPAIGN_MISSIONS.remove(regionKey);
    }

    /**
     * Processes a Warfront enemy entity kill and advances mission progress if applicable.
     *
     * @param level         the server level
     * @param originRegionX the mob's origin region X
     * @param originRegionZ the mob's origin region Z
     * @param originSubX    the mob's origin subregion X
     * @param originSubZ    the mob's origin subregion Z
     * @param mobFaction    the mob's faction
     * @param mobRoleName   the mob's role name
     */
    public static void onEntityKilled(
            ServerLevel level,
            int originRegionX, int originRegionZ,
            int originSubX, int originSubZ,
            Faction mobFaction,
            String mobRoleName) {

        long regionKey = ChunkPos.asLong(originRegionX, originRegionZ);
        Map<Integer, ActiveSubRegionProgress> subMissions = ACTIVE_CAMPAIGN_MISSIONS.get(regionKey);

        if (subMissions == null || subMissions.isEmpty()) {
            return;
        }

        int bit = originSubZ * 2 + originSubX;
        ActiveSubRegionProgress progress = subMissions.get(bit);

        if (progress == null || progress.isCompleted()) {
            return;
        }

        // Verify entity faction matches mission target faction
        if (mobFaction != progress.targetFaction()) {
            return;
        }

        progress.currentKills++;
        Warfront.LOGGER.debug("Mission kill progress for Region ({}, {}) Sub ({}, {}): {}/{}",
                originRegionX, originRegionZ, originSubX, originSubZ, progress.currentKills, progress.requiredKills);

        if (progress.currentKills >= progress.requiredKills) {
            progress.completed = true;
            RegionData regions = RegionData.get(level);

            // Subregion Objective Completed -> Capture ONLY this subregion to HUMANITY
            regions.claimSubRegion(level, originRegionX, originRegionZ, originSubX, originSubZ, Faction.HUMANITY, 100.0F);

            String logMsg = String.format("§aMission Completed! Sub-region (%d, %d) in Region (%d, %d) captured.",
                    originSubX, originSubZ, originRegionX, originRegionZ);
            regions.addLog(level, logMsg);
            Warfront.LOGGER.info("Subregion mission completed: Region ({}, {}) Sub ({}, {}). Subregion captured to HUMANITY.",
                    originRegionX, originRegionZ, originSubX, originSubZ);

            // Check if ALL selected subregion missions in this campaign are completed
            boolean allCompleted = true;
            for (ActiveSubRegionProgress p : subMissions.values()) {
                if (!p.isCompleted()) {
                    allCompleted = false;
                    break;
                }
            }

            if (allCompleted) {
                // Campaign Success! Clear campaign state
                regions.setRegionSiege(originRegionX, originRegionZ, false);
                regions.getActiveSieges().remove(regionKey);
                clearCampaign(level, originRegionX, originRegionZ);

                regions.addLog(level, String.format("§aCampaign Victory! All objectives completed for Region (%d, %d).", originRegionX, originRegionZ));
                regions.broadcastTitle(level, Component.literal("§a§lCAMPAIGN VICTORY!"), Component.literal(String.format("§7Region (%d, %d) Campaign Completed", originRegionX, originRegionZ)));
            }

            com.warfront.network.RequestRegionMapPayload.notifyActiveMapTerminals(level);
        }
    }
}
