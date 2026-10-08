package com.warfront.mission;

import com.warfront.Warfront;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.network.ActiveMissionHudPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative mission execution manager.
 *
 * Tracks active campaign progress, manages polymorphic objective state,
 * and interfaces with RegionData for persistent world storage.
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
        private final String objectiveDescription;
        private final MissionType missionType;
        private final ObjectiveType objectiveType;
        private int targetProgress;
        private int currentProgress;
        private BlockPos missionSiteAnchor;
        private int phaseIndex;
        private boolean completed;
        private final List<UUID> trackedEntityUuids;
        private final List<BlockPos> trackedBlockPositions;

        public ActiveSubRegionProgress(
                int regionX, int regionZ,
                int subX, int subZ,
                Faction targetFaction,
                String targetRoleName,
                String displayName,
                String objectiveDescription,
                MissionType missionType,
                ObjectiveType objectiveType,
                int targetProgress
        ) {
            this(UUID.randomUUID(), regionX, regionZ, subX, subZ, targetFaction, targetRoleName,
                    displayName, objectiveDescription, missionType, objectiveType,
                    targetProgress, 0, null, 0, false, new ArrayList<>(), new ArrayList<>());
        }

        public ActiveSubRegionProgress(
                UUID missionInstanceId,
                int regionX, int regionZ,
                int subX, int subZ,
                Faction targetFaction,
                String targetRoleName,
                String displayName,
                String objectiveDescription,
                MissionType missionType,
                ObjectiveType objectiveType,
                int targetProgress,
                int currentProgress,
                BlockPos missionSiteAnchor,
                int phaseIndex,
                boolean completed,
                List<UUID> trackedEntityUuids,
                List<BlockPos> trackedBlockPositions
        ) {
            this.missionInstanceId = missionInstanceId != null ? missionInstanceId : UUID.randomUUID();
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.subX = subX;
            this.subZ = subZ;
            this.targetFaction = targetFaction != null ? targetFaction : Faction.PILLAGER_CONQUERORS;
            this.targetRoleName = targetRoleName != null ? targetRoleName : "BASIC";
            this.displayName = displayName != null ? displayName : "Mission";
            this.objectiveDescription = objectiveDescription != null ? objectiveDescription : "";
            this.missionType = missionType != null ? missionType : MissionType.FORWARD_PATROL;
            this.objectiveType = objectiveType != null ? objectiveType : (missionType != null ? missionType.defaultObjectiveType() : ObjectiveType.ELIMINATE_TARGETS);
            this.targetProgress = targetProgress;
            this.currentProgress = currentProgress;
            this.missionSiteAnchor = missionSiteAnchor;
            this.phaseIndex = phaseIndex;
            this.completed = completed;
            this.trackedEntityUuids = trackedEntityUuids != null ? new ArrayList<>(trackedEntityUuids) : new ArrayList<>();
            this.trackedBlockPositions = trackedBlockPositions != null ? new ArrayList<>(trackedBlockPositions) : new ArrayList<>();
        }

        public UUID missionInstanceId() { return missionInstanceId; }
        public int regionX() { return regionX; }
        public int regionZ() { return regionZ; }
        public int subX() { return subX; }
        public int subZ() { return subZ; }
        public Faction targetFaction() { return targetFaction; }
        public String targetRoleName() { return targetRoleName; }
        public String displayName() { return displayName; }
        public String objectiveDescription() { return objectiveDescription; }
        public MissionType missionType() { return missionType; }
        public ObjectiveType objectiveType() { return objectiveType; }
        public int targetProgress() { return targetProgress; }
        public int currentProgress() { return currentProgress; }
        public BlockPos missionSiteAnchor() { return missionSiteAnchor; }
        public int phaseIndex() { return phaseIndex; }
        public boolean isCompleted() { return completed; }
        public List<UUID> trackedEntityUuids() { return trackedEntityUuids; }
        public List<BlockPos> trackedBlockPositions() { return trackedBlockPositions; }

        public void setMissionSiteAnchor(BlockPos anchor) { this.missionSiteAnchor = anchor; }
        public void setPhaseIndex(int phaseIndex) { this.phaseIndex = phaseIndex; }
        public void setTargetProgress(int targetProgress) { this.targetProgress = targetProgress; }
        public void setCompleted(boolean completed) { this.completed = completed; }
        public void setCurrentProgress(int currentProgress) { this.currentProgress = currentProgress; }
        public void incrementProgress() { this.currentProgress++; }
        public void addProgress(int amount) { this.currentProgress += amount; }

        // Backward compatibility getters & setters
        public int requiredKills() { return targetProgress; }
        public int currentKills() { return currentProgress; }
        public void incrementKills() { incrementProgress(); }

        public String formatProgressDisplay() {
            if (missionType == MissionType.FORWARD_OUTPOST || missionType == MissionType.OUTPOST_DESTROY_BUILDING) {
                int totalBlocks = trackedBlockPositions.size() + currentProgress;
                int pct = totalBlocks > 0 ? Math.min(100, (currentProgress * 100) / totalBlocks) : 0;
                return pct + "% / 50%";
            }
            return currentProgress + " / " + targetProgress;
        }

        /**
         * Serializes active subregion progress to NBT for world save persistence.
         */
        public CompoundTag toCompoundTag() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("instance_id", missionInstanceId);
            tag.putInt("rx", regionX);
            tag.putInt("rz", regionZ);
            tag.putInt("sx", subX);
            tag.putInt("sz", subZ);
            tag.putInt("faction", targetFaction.id());
            tag.putString("role", targetRoleName);
            tag.putString("display_name", displayName);
            tag.putString("obj_desc", objectiveDescription);
            tag.putString("mission_type", missionType.name());
            tag.putString("obj_type", objectiveType.name());
            tag.putInt("target_progress", targetProgress);
            tag.putInt("current_progress", currentProgress);
            tag.putInt("phase", phaseIndex);
            tag.putBoolean("completed", completed);

            if (missionSiteAnchor != null) {
                tag.putInt("anchor_x", missionSiteAnchor.getX());
                tag.putInt("anchor_y", missionSiteAnchor.getY());
                tag.putInt("anchor_z", missionSiteAnchor.getZ());
            }

            ListTag entitiesTag = new ListTag();
            for (UUID uuid : trackedEntityUuids) {
                CompoundTag uTag = new CompoundTag();
                uTag.putUUID("uuid", uuid);
                entitiesTag.add(uTag);
            }
            tag.put("tracked_entities", entitiesTag);

            ListTag blocksTag = new ListTag();
            for (BlockPos pos : trackedBlockPositions) {
                CompoundTag bTag = new CompoundTag();
                bTag.putInt("x", pos.getX());
                bTag.putInt("y", pos.getY());
                bTag.putInt("z", pos.getZ());
                blocksTag.add(bTag);
            }
            tag.put("tracked_blocks", blocksTag);

            return tag;
        }

        /**
         * Rehydrates an active subregion progress from saved NBT.
         */
        public static ActiveSubRegionProgress fromCompoundTag(CompoundTag tag) {
            if (tag == null) return null;
            UUID id = tag.contains("instance_id") ? tag.getUUID("instance_id") : UUID.randomUUID();
            int rx = tag.getInt("rx");
            int rz = tag.getInt("rz");
            int sx = tag.getInt("sx");
            int sz = tag.getInt("sz");
            Faction faction = Faction.byId(tag.getInt("faction"));
            String role = tag.getString("role");
            String dispName = tag.getString("display_name");
            String objDesc = tag.getString("obj_desc");

            MissionType mType;
            try {
                mType = MissionType.valueOf(tag.getString("mission_type"));
            } catch (Exception e) {
                mType = MissionType.FORWARD_PATROL;
            }

            ObjectiveType oType;
            try {
                oType = ObjectiveType.valueOf(tag.getString("obj_type"));
            } catch (Exception e) {
                oType = mType.defaultObjectiveType();
            }

            int targetProg = tag.getInt("target_progress");
            int currProg = tag.getInt("current_progress");
            int phase = tag.getInt("phase");
            boolean comp = tag.getBoolean("completed");

            BlockPos anchor = null;
            if (tag.contains("anchor_x") && tag.contains("anchor_y") && tag.contains("anchor_z")) {
                anchor = new BlockPos(tag.getInt("anchor_x"), tag.getInt("anchor_y"), tag.getInt("anchor_z"));
            }

            List<UUID> entities = new ArrayList<>();
            if (tag.contains("tracked_entities", Tag.TAG_LIST)) {
                ListTag eList = tag.getList("tracked_entities", Tag.TAG_COMPOUND);
                for (int i = 0; i < eList.size(); i++) {
                    CompoundTag uTag = eList.getCompound(i);
                    if (uTag.contains("uuid")) {
                        entities.add(uTag.getUUID("uuid"));
                    }
                }
            }

            List<BlockPos> blocks = new ArrayList<>();
            if (tag.contains("tracked_blocks", Tag.TAG_LIST)) {
                ListTag bList = tag.getList("tracked_blocks", Tag.TAG_COMPOUND);
                for (int i = 0; i < bList.size(); i++) {
                    CompoundTag bTag = bList.getCompound(i);
                    blocks.add(new BlockPos(bTag.getInt("x"), bTag.getInt("y"), bTag.getInt("z")));
                }
            }

            return new ActiveSubRegionProgress(
                    id, rx, rz, sx, sz, faction, role, dispName, objDesc,
                    mType, oType, targetProg, currProg, anchor, phase, comp, entities, blocks
            );
        }
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
     * Checks if there are any active, uncompleted campaign missions anywhere in the region.
     */
    public static boolean hasAnyActiveMissionInRegion(int regionX, int regionZ) {
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        Map<Integer, ActiveSubRegionProgress> subMissions = ACTIVE_CAMPAIGN_MISSIONS.get(regionKey);
        if (subMissions == null || subMissions.isEmpty()) {
            return false;
        }
        for (ActiveSubRegionProgress progress : subMissions.values()) {
            if (progress != null && !progress.isCompleted()) {
                return true;
            }
        }
        return false;
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
     * Returns an unmodifiable view of all active campaigns for persistence.
     */
    public static Map<Long, Map<Integer, ActiveSubRegionProgress>> getAllActiveMissions() {
        return Collections.unmodifiableMap(ACTIVE_CAMPAIGN_MISSIONS);
    }

    /**
     * Rehydrates campaigns from persistent storage on world load.
     */
    public static void loadCampaigns(Map<Long, Map<Integer, ActiveSubRegionProgress>> loaded) {
        ACTIVE_CAMPAIGN_MISSIONS.clear();
        if (loaded != null) {
            ACTIVE_CAMPAIGN_MISSIONS.putAll(loaded);
        }
        Warfront.LOGGER.info("Loaded {} active campaign region(s) from persistent storage.", ACTIVE_CAMPAIGN_MISSIONS.size());
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
        RegionData regions = (level != null) ? RegionData.get(level) : null;
        long missionSeed = (regions != null) ? regions.calculateMissionSeed(regionX, regionZ) : 0L;
        RegionData.Region reg = (regions != null) ? regions.regionAt(regionX, regionZ) : null;
        BlockPos baseAnchor = (reg != null) ? reg.baseAnchor() : null;

        SubRegionMission[] generatedMissions = MissionProfile.generateForRegion(
                regionX, regionZ, targetFaction, baseType, resistance, stability, missionSeed, baseAnchor);

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
                        gen.description(),
                        gen.type(),
                        gen.objectiveType(),
                        gen.targetCount()
                ));
            }
        }

        if (regions != null) {
            regions.setDirty();
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
                progress.setCompleted(true);
                for (UUID uuid : progress.trackedEntityUuids()) {
                    net.minecraft.world.entity.Entity e = level.getEntity(uuid);
                    if (e != null && e.isAlive()) {
                        com.warfront.spawn.MissionEntityTracker.registerMissionEntity(
                                e, progress.missionInstanceId(), regionX, regionZ, progress.subX(), progress.subZ(), progress.targetFaction());
                    }
                }
                MissionHandlerRegistry.getHandler(progress.missionType())
                        .onCleanup(level, regionX, regionZ, progress.subX(), progress.subZ(), progress);
            }
            RegionData regions = RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
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
     * Triggered when a player is present in an active mission subregion to manage in-world mission execution.
     */
    public static void onPlayerInSubregion(ServerLevel level, int regionX, int regionZ, int subX, int subZ, ServerPlayer player) {
        ActiveSubRegionProgress progress = getActiveProgress(regionX, regionZ, subX, subZ);
        if (progress != null && !progress.isCompleted()) {
            MissionHandlerRegistry.getHandler(progress.missionType())
                    .onPlayerInSubregion(level, regionX, regionZ, subX, subZ, progress, player);
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
        onEntityKilled(level, originRegionX, originRegionZ, originSubX, originSubZ, mobFaction, mobRoleName, null);
    }

    /**
     * Authoritative mob kill processor passing the exact Mob entity.
     */
    public static void onEntityKilled(
            ServerLevel level,
            int originRegionX, int originRegionZ,
            int originSubX, int originSubZ,
            Faction mobFaction,
            String mobRoleName,
            Mob mob) {

        ActiveSubRegionProgress progress = getActiveProgress(originRegionX, originRegionZ, originSubX, originSubZ);
        if (progress != null && !progress.isCompleted()) {
            MissionHandlerRegistry.getHandler(progress.missionType())
                    .onEntityKilled(
                            level,
                            originRegionX, originRegionZ,
                            originSubX, originSubZ,
                            progress,
                            mobFaction,
                            mobRoleName,
                            mob
                    );
        }
    }

    /**
     * Processes a block break event within an active mission subregion.
     */
    public static void onBlockBroken(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            BlockPos pos,
            BlockState state,
            ServerPlayer player) {

        ActiveSubRegionProgress progress = getActiveProgress(regionX, regionZ, subX, subZ);
        if (progress != null && !progress.isCompleted()) {
            boolean handled = MissionHandlerRegistry.getHandler(progress.missionType())
                    .onBlockBroken(level, regionX, regionZ, subX, subZ, progress, pos, state, player);
            if (handled) return;
        }

        // Check if any other active mission in this region is tracking this block position
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        Map<Integer, ActiveSubRegionProgress> subMissions = ACTIVE_CAMPAIGN_MISSIONS.get(regionKey);
        if (subMissions != null) {
            for (ActiveSubRegionProgress other : subMissions.values()) {
                if (other != progress && !other.isCompleted() && other.trackedBlockPositions().contains(pos)) {
                    MissionHandlerRegistry.getHandler(other.missionType())
                            .onBlockBroken(level, regionX, regionZ, other.subX(), other.subZ(), other, pos, state, player);
                    break;
                }
            }
        }
    }

    /**
     * Unified completion handler: Secures the subregion for Humanity, performs cleanup,
     * logs to RegionData, triggers domino check, and broadcasts HUD/map updates.
     */
    public static void completeMission(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveSubRegionProgress progress) {

        if (progress == null || progress.isCompleted()) {
            return;
        }

        progress.setCompleted(true);
        for (UUID uuid : progress.trackedEntityUuids()) {
            net.minecraft.world.entity.Entity e = level.getEntity(uuid);
            if (e != null && e.isAlive()) {
                com.warfront.spawn.MissionEntityTracker.registerMissionEntity(
                        e, progress.missionInstanceId(), regionX, regionZ, subX, subZ, progress.targetFaction());
            }
        }
        MissionHandlerRegistry.getHandler(progress.missionType())
                .onCleanup(level, regionX, regionZ, subX, subZ, progress);

        RegionData regions = RegionData.get(level);
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
        broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
    }

    /**
     * Unified failure handler: Marks mission as failed, cleans up spawned entities/blocks,
     * logs failure message, clears active subregion bit in the campaign mask, and updates HUD/map.
     */
    public static void failMission(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveSubRegionProgress progress,
            String failureReason) {

        if (progress == null || progress.isCompleted()) {
            return;
        }

        progress.setCompleted(true);
        for (UUID uuid : progress.trackedEntityUuids()) {
            net.minecraft.world.entity.Entity e = level.getEntity(uuid);
            if (e != null && e.isAlive()) {
                com.warfront.spawn.MissionEntityTracker.registerMissionEntity(
                        e, progress.missionInstanceId(), regionX, regionZ, subX, subZ, progress.targetFaction());
            }
        }
        MissionHandlerRegistry.getHandler(progress.missionType())
                .onCleanup(level, regionX, regionZ, subX, subZ, progress);

        RegionData regions = RegionData.get(level);
        if (regions != null) {
            long regionKey = net.minecraft.world.level.ChunkPos.asLong(regionX, regionZ);
            RegionData.SiegeCampaign siege = regions.getActiveSieges().get(regionKey);
            if (siege != null) {
                int bit = subZ * 2 + subX;
                int newActiveMask = siege.activeSubRegionsMask() & ~(1 << bit);
                regions.getActiveSieges().put(regionKey, new RegionData.SiegeCampaign(
                        siege.attacker(),
                        siege.targetRegionX(), siege.targetRegionZ(),
                        siege.sources(), siege.attackValue(), siege.encircled(),
                        siege.startTick(), siege.durationTicks(),
                        newActiveMask,
                        siege.attackerClusterId()));
            }

            String logMsg = String.format("§cMission Failed! Sub-region (%d, %d) in Region (%d, %d): %s",
                    subX, subZ, regionX, regionZ, failureReason);
            regions.addLog(level, logMsg);
            regions.setDirty();
        }

        Warfront.LOGGER.info("Subregion mission failed: Region ({}, {}) Sub ({}, {}). Reason: {}",
                regionX, regionZ, subX, subZ, failureReason);

        com.warfront.network.RequestRegionMapPayload.notifyActiveMapTerminals(level);
        broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
    }

    /**
     * Broadcasts an authoritative HUD progress update packet to all players in the level.
     */
    public static void broadcastHudUpdate(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveSubRegionProgress progress) {

        if (level == null || progress == null) {
            return;
        }

        RegionData regions = RegionData.get(level);
        RegionData.SiegeCampaign siege = (regions != null) ? regions.getSiege(regionX, regionZ) : null;
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
    }
}
