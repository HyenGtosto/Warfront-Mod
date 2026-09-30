package com.warfront.mission;

import com.warfront.Warfront;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
        private final UUID missionInstanceId;
        private final int regionX;
        private final int regionZ;
        private final int subX;
        private final int subZ;
        private final Faction targetFaction;
        private final String targetRoleName;
        private final String displayName;
        private final MissionType missionType;
        private final int requiredKills;
        private int currentKills;
        private boolean completed;

        public ActiveSubRegionProgress(
                int regionX, int regionZ,
                int subX, int subZ,
                Faction targetFaction,
                String targetRoleName,
                String displayName,
                MissionType missionType,
                int requiredKills
        ) {
            this(UUID.randomUUID(), regionX, regionZ, subX, subZ, targetFaction, targetRoleName, displayName, missionType, requiredKills);
        }

        public ActiveSubRegionProgress(
                UUID missionInstanceId,
                int regionX, int regionZ,
                int subX, int subZ,
                Faction targetFaction,
                String targetRoleName,
                String displayName,
                MissionType missionType,
                int requiredKills
        ) {
            this.missionInstanceId = missionInstanceId != null ? missionInstanceId : UUID.randomUUID();
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.subX = subX;
            this.subZ = subZ;
            this.targetFaction = targetFaction;
            this.targetRoleName = targetRoleName;
            this.displayName = displayName;
            this.missionType = missionType;
            this.requiredKills = requiredKills;
            this.currentKills = 0;
            this.completed = false;
        }

        public UUID missionInstanceId() { return missionInstanceId; }
        public int regionX() { return regionX; }
        public int regionZ() { return regionZ; }
        public int subX() { return subX; }
        public int subZ() { return subZ; }
        public Faction targetFaction() { return targetFaction; }
        public String targetRoleName() { return targetRoleName; }
        public String displayName() { return displayName; }
        public MissionType missionType() { return missionType; }
        public int requiredKills() { return requiredKills; }
        public int currentKills() { return currentKills; }
        public boolean isCompleted() { return completed; }

        public void incrementKills() { this.currentKills++; }
        public void setCompleted(boolean completed) { this.completed = completed; }
    }

    /**
     * Checks if a subregion has an active, uncompleted mission in the campaign.
     */
    public static boolean hasActiveMission(int regionX, int regionZ, int subX, int subZ) {
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        Map<Integer, ActiveSubRegionProgress> subMissions = ACTIVE_CAMPAIGN_MISSIONS.get(regionKey);
        if (subMissions == null || subMissions.isEmpty()) {
            return false;
        }
        int bit = subZ * 2 + subX;
        ActiveSubRegionProgress progress = subMissions.get(bit);
        return progress != null && !progress.isCompleted();
    }

    /**
     * Authoritative query: Checks if a specific mission instance is currently active and uncompleted.
     */
    public static boolean isMissionInstanceActive(UUID missionInstanceId, int regionX, int regionZ, int subX, int subZ) {
        if (missionInstanceId == null) {
            return false;
        }
        ActiveSubRegionProgress progress = getActiveProgress(regionX, regionZ, subX, subZ);
        return progress != null && missionInstanceId.equals(progress.missionInstanceId()) && !progress.isCompleted();
    }

    /**
     * Retrieves the active subregion progress if present.
     */
    public static ActiveSubRegionProgress getActiveProgress(int regionX, int regionZ, int subX, int subZ) {
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        Map<Integer, ActiveSubRegionProgress> subMissions = ACTIVE_CAMPAIGN_MISSIONS.get(regionKey);
        if (subMissions == null || subMissions.isEmpty()) {
            return null;
        }
        int bit = subZ * 2 + subX;
        return subMissions.get(bit);
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
                ActiveSubRegionProgress existing = subMissions.get(bit);
                if (existing != null && existing.isCompleted()) {
                    continue;
                }
                SubRegionMission gen = generatedMissions[i];
                subMissions.put(bit, new ActiveSubRegionProgress(
                        regionX, regionZ,
                        subX, subZ,
                        targetFaction,
                        gen.targetRoleName(),
                        gen.displayName(),
                        gen.type(),
                        gen.killTarget()
                ));
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
        Map<Integer, ActiveSubRegionProgress> removed = ACTIVE_CAMPAIGN_MISSIONS.remove(regionKey);
        if (removed != null && level != null) {
            for (ActiveSubRegionProgress progress : removed.values()) {
                KillCountMissionHandler.getInstance().onCleanup(level, regionX, regionZ, progress.subX(), progress.subZ(), progress);
            }
        }
    }

    /**
     * Returns a 4-bit mask of subregions that currently have active, uncompleted missions in the campaign.
     */
    public static int getActiveSubRegionsMask(int regionX, int regionZ) {
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        Map<Integer, ActiveSubRegionProgress> subMissions = ACTIVE_CAMPAIGN_MISSIONS.get(regionKey);
        if (subMissions == null || subMissions.isEmpty()) {
            return 0;
        }
        int mask = 0;
        for (Map.Entry<Integer, ActiveSubRegionProgress> entry : subMissions.entrySet()) {
            if (!entry.getValue().isCompleted()) {
                mask |= (1 << entry.getKey());
            }
        }
        return mask;
    }

    /**
     * Triggered when a player is present in an active mission subregion to manage reinforcement spawning.
     */
    public static void onPlayerInSubregion(ServerLevel level, int regionX, int regionZ, int subX, int subZ, ServerPlayer player) {
        ActiveSubRegionProgress progress = getActiveProgress(regionX, regionZ, subX, subZ);
        if (progress != null && !progress.isCompleted()) {
            KillCountMissionHandler.getInstance().onPlayerInSubregion(level, regionX, regionZ, subX, subZ, progress, player);
        }
    }

    /**
     * Processes a Warfront enemy entity kill and advances mission progress if applicable.
     */
    public static void onEntityKilled(
            ServerLevel level,
            int originRegionX, int originRegionZ,
            int originSubX, int originSubZ,
            Faction mobFaction,
            String mobRoleName) {

        ActiveSubRegionProgress progress = getActiveProgress(originRegionX, originRegionZ, originSubX, originSubZ);
        if (progress != null && !progress.isCompleted()) {
            KillCountMissionHandler.getInstance().onEntityKilled(
                    level,
                    originRegionX, originRegionZ,
                    originSubX, originSubZ,
                    progress,
                    mobFaction,
                    mobRoleName
            );
        }
    }
}
