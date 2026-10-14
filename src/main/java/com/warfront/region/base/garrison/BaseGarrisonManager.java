package com.warfront.region.base.garrison;

import com.warfront.Warfront;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.base.PersistentBaseProtectionManager;
import com.warfront.spawn.MissionEntityTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Authoritative manager governing persistent base garrisons.
 * Coordinates:
 * - Base placement initialization & slot tracking.
 * - 3-minute passive respawns outside of active missions.
 * - Mission start missing-only instant respawns with living mob healing (zero double-spawns).
 * - Complete conquest cleanup & dispersal (zero ghost respawns in Humanity territory).
 */
public final class BaseGarrisonManager {

    public static final long RESPAWN_DELAY_TICKS = 3600L; // 3 minutes = 180 seconds = 3600 ticks
    private static final int TICK_INTERVAL = 40; // Evaluate every 2 seconds

    private BaseGarrisonManager() {
    }

    /**
     * Initializes and spawns the full garrison for a newly placed base.
     */
    public static void ensureGarrisonSpawned(ServerLevel level, int rx, int rz, BaseType baseType, Faction faction, BlockPos anchor) {
        if (level == null || baseType == BaseType.NONE || faction == Faction.HUMANITY || anchor == null) {
            return;
        }

        BaseGarrisonData data = BaseGarrisonData.get(level);
        if (data.hasGarrison(rx, rz)) {
            return;
        }

        BaseGarrisonDefinition def = BaseGarrisonRegistry.getDefinition(faction, baseType);
        if (def == null) {
            return;
        }

        BaseGarrisonState state = new BaseGarrisonState(rx, rz, baseType, faction, anchor);
        int slotCount = def.getSlotCount();
        data.putGarrison(state);

        for (int i = 0; i < slotCount; i++) {
            String roleId = def.getSlotRoleId(i);
            Mob mob = def.spawnSlot(level, anchor, i);
            if (mob != null) {
                tagGarrisonMob(mob, rx, rz, i, roleId, faction);
                level.addFreshEntity(mob);
                state.addSlot(new GarrisonSlot(i, roleId, mob.getUUID(), -1L));
            } else {
                state.addSlot(new GarrisonSlot(i, roleId, null, level.getGameTime() + RESPAWN_DELAY_TICKS));
            }
        }

        data.setDirty();
        Warfront.LOGGER.info("[Warfront] Initialized and spawned {} persistent garrison slots for {} ({}) in Region ({}, {}) at {}",
                state.getSlots().size(), baseType, faction.displayName().getString(), rx, rz, anchor);
    }

    /**
     * Periodic ticking loop evaluating passive respawns outside of missions.
     */
    public static void onServerTick(ServerLevel level) {
        if (level == null || level.getGameTime() % TICK_INTERVAL != 0) {
            return;
        }

        BaseGarrisonData data = BaseGarrisonData.get(level);
        RegionData regions = RegionData.get(level);
        // 1. Proactively ensure placed bases with loaded chunks have their persistent garrisons spawned
        if (regions != null) {
            for (RegionData.Region placed : regions.getAllPlacedBaseRegions()) {
                if (placed.owner().isAI() && placed.baseType() != BaseType.NONE) {
                    if (!data.hasGarrison(placed.x(), placed.z())) {
                        BlockPos anchor = placed.baseAnchor();
                        if (anchor != null && level.hasChunkAt(anchor)) {
                            ensureGarrisonSpawned(level, placed.x(), placed.z(), placed.baseType(), placed.owner(), anchor);
                        }
                    }
                }
            }
        }

        long now = level.getGameTime();

        for (BaseGarrisonState state : new ArrayList<>(data.getAllGarrisons())) {
            int rx = state.regionX();
            int rz = state.regionZ();

            // Conquest guard: if territory is now owned by Humanity, purge garrison immediately
            if (regions != null && regions.regionAt(rx, rz).owner() == Faction.HUMANITY) {
                cleanupGarrisonOnConquest(level, rx, rz);
                continue;
            }

            // Skip passive respawn evaluation while the base is actively at war / mission active
            if (PersistentBaseProtectionManager.isBaseAtWar(level, rx, rz, state.baseAnchor(), regions)) {
                continue;
            }

            BaseGarrisonDefinition def = BaseGarrisonRegistry.getDefinition(state.faction(), state.baseType());
            if (def == null) {
                continue;
            }

            // Determine if the base currently has zero living garrison entities
            int livingCount = 0;
            for (GarrisonSlot s : state.getSlots()) {
                if (s.getEntityUuid() != null) {
                    Entity e = level.getEntity(s.getEntityUuid());
                    if (e instanceof Mob m && m.isAlive()) {
                        livingCount++;
                    }
                }
            }
            boolean baseCompletelyEmpty = (livingCount == 0);

            for (GarrisonSlot slot : state.getSlots()) {
                UUID uuid = slot.getEntityUuid();
                BlockPos spawnPos = def.getSlotSpawnPos(state.baseAnchor(), slot.slotIndex());

                if (uuid != null) {
                    if (level.hasChunkAt(spawnPos)) {
                        Entity entity = level.getEntity(uuid);
                        if (entity == null || !entity.isAlive()) {
                            slot.setEntityUuid(null);
                            // If base was completely empty (e.g. recovering from bug), allow immediate replenishment
                            if (baseCompletelyEmpty) {
                                slot.setRespawnGameTime(now);
                            } else {
                                slot.setRespawnGameTime(now + RESPAWN_DELAY_TICKS);
                            }
                            data.setDirty();
                        }
                    }
                } else {
                    // Check if passive respawn timer has elapsed
                    long respawnTime = slot.getRespawnGameTime();
                    if (respawnTime <= 0) {
                        slot.setRespawnGameTime(now + RESPAWN_DELAY_TICKS);
                        data.setDirty();
                    } else if (now >= respawnTime) {
                        if (level.hasChunkAt(spawnPos)) {
                            Mob freshMob = def.spawnSlot(level, state.baseAnchor(), slot.slotIndex());
                            if (freshMob != null) {
                                tagGarrisonMob(freshMob, rx, rz, slot.slotIndex(), slot.roleId(), state.faction());
                                level.addFreshEntity(freshMob);
                                slot.setEntityUuid(freshMob.getUUID());
                                slot.setRespawnGameTime(-1L);
                                data.setDirty();
                                Warfront.LOGGER.info("[Warfront] Passive respawned garrison guard for slot {} ({}) in Region ({}, {})",
                                        slot.slotIndex(), slot.roleId(), rx, rz);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Prepares garrison mobs for an active mission:
     * - Surviving living mobs are fully healed and enlisted into mission tracking (ZERO DOUBLE SPAWN).
     * - Dead or missing mobs are instantly respawned, tagged, and enlisted.
     */
    public static void prepareAndEnlistMissionGarrison(
            ServerLevel level,
            int rx, int rz, int sx, int sz,
            BlockPos anchor,
            UUID instanceId,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        if (level == null || anchor == null || progress == null) {
            return;
        }

        BaseGarrisonData data = BaseGarrisonData.get(level);
        BaseGarrisonState state = data.getGarrison(rx, rz);

        if (state == null) {
            // Lazy initialization if base was placed before garrison tracking
            ensureGarrisonSpawned(level, rx, rz, BaseType.OUTPOST, progress.targetFaction(), anchor);
            state = data.getGarrison(rx, rz);
        }

        if (state == null) {
            return;
        }

        BaseGarrisonDefinition def = BaseGarrisonRegistry.getDefinition(state.faction(), state.baseType());
        if (def == null) {
            return;
        }

        int livingEnlisted = 0;
        int instantRespawned = 0;

        for (GarrisonSlot slot : state.getSlots()) {
            UUID uuid = slot.getEntityUuid();
            Entity existing = (uuid != null) ? level.getEntity(uuid) : null;

            if (existing instanceof Mob living && living.isAlive()) {
                // Living mob: heal to full HP and enlist without re-spawning
                living.setHealth(living.getMaxHealth());
                enlistMobIntoMission(living, instanceId, rx, rz, sx, sz, progress);
                slot.setRespawnGameTime(-1L);
                livingEnlisted++;
            } else {
                // Dead or missing mob: instant respawn on mission start
                Mob freshMob = def.spawnSlot(level, anchor, slot.slotIndex());
                if (freshMob != null) {
                    tagGarrisonMob(freshMob, rx, rz, slot.slotIndex(), slot.roleId(), state.faction());
                    level.addFreshEntity(freshMob);
                    enlistMobIntoMission(freshMob, instanceId, rx, rz, sx, sz, progress);
                    slot.setEntityUuid(freshMob.getUUID());
                    slot.setRespawnGameTime(-1L);
                    instantRespawned++;
                }
            }
        }

        data.setDirty();
        Warfront.LOGGER.info("[Warfront] Mission garrison prepared for Region ({}, {}): {} living mobs enlisted, {} missing mobs instantly respawned (Total: {}).",
                rx, rz, livingEnlisted, instantRespawned, state.getSlots().size());
    }

    /**
     * Handles mob death for tracked garrison entities.
     * Sets 3-minute passive respawn timer if killed outside of an active mission.
     */
    public static void handleMobDeath(Mob mob, ServerLevel level) {
        if (mob == null || level == null) {
            return;
        }

        CompoundTag nbt = mob.getPersistentData();
        if (!nbt.getBoolean("warfront_garrison") && !mob.getTags().contains("warfront_garrison")) {
            return;
        }

        int rx = nbt.getInt("garrisonRegionX");
        int rz = nbt.getInt("garrisonRegionZ");
        int slotIndex = nbt.getInt("garrisonSlotIndex");

        BaseGarrisonData data = BaseGarrisonData.get(level);
        BaseGarrisonState state = data.getGarrison(rx, rz);
        if (state == null) {
            return;
        }

        GarrisonSlot slot = state.getSlot(slotIndex);
        if (slot == null) {
            return;
        }

        RegionData regions = RegionData.get(level);
        boolean atWar = PersistentBaseProtectionManager.isBaseAtWar(level, rx, rz, state.baseAnchor(), regions);

        if (!atWar) {
            // Killed outside active mission: schedule 3-minute passive respawn
            slot.setEntityUuid(null);
            slot.setRespawnGameTime(level.getGameTime() + RESPAWN_DELAY_TICKS);
            data.setDirty();
            Warfront.LOGGER.info("[Warfront] Garrison guard slot {} ({}) died outside mission in Region ({}, {}). Scheduled respawn in 3 minutes.",
                    slotIndex, slot.roleId(), rx, rz);
        } else {
            // Killed during active mission: mark slot empty, wait for next mission or peace resolution
            slot.setEntityUuid(null);
            slot.setRespawnGameTime(-1L);
            data.setDirty();
        }
    }

    /**
     * Cleans up garrison data and disperses surviving mobs upon conquest by Humanity.
     */
    public static void cleanupGarrisonOnConquest(ServerLevel level, int rx, int rz) {
        if (level == null) {
            return;
        }

        BaseGarrisonData data = BaseGarrisonData.get(level);
        BaseGarrisonState state = data.getGarrison(rx, rz);
        if (state == null) {
            return;
        }

        // Disperse surviving garrison mobs with POOF cloud particles
        for (GarrisonSlot slot : state.getSlots()) {
            UUID uuid = slot.getEntityUuid();
            if (uuid != null) {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof Mob mob && mob.isAlive()) {
                    level.sendParticles(
                            ParticleTypes.POOF,
                            mob.getX(), mob.getY() + 0.5D, mob.getZ(),
                            15, 0.3D, 0.5D, 0.3D, 0.05D
                    );
                    mob.discard();
                }
            }
        }

        // Permanently purge garrison data for this region
        data.removeGarrison(rx, rz);
        data.setDirty();
        Warfront.LOGGER.info("[Warfront] Garrison cleared and purged for conquered Region ({}, {}).", rx, rz);
    }

    /**
     * Intercepts entity join events for previously saved garrison mobs.
     * Silently discards any garrison mob loading into conquered Humanity territory.
     *
     * @return true if the mob was discarded and event should be cancelled.
     */
    public static boolean onGarrisonMobJoin(Mob mob, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        CompoundTag data = mob.getPersistentData();
        int rx = data.getInt("garrisonRegionX");
        int rz = data.getInt("garrisonRegionZ");

        RegionData regions = RegionData.get(serverLevel);
        if (regions != null && regions.regionAt(rx, rz).owner() == Faction.HUMANITY) {
            mob.discard();
            return true;
        }

        BaseGarrisonData garrisonData = BaseGarrisonData.get(serverLevel);
        if (!garrisonData.hasGarrison(rx, rz)) {
            if (regions != null) {
                RegionData.Region reg = regions.regionAt(rx, rz);
                if (reg.owner() == Faction.HUMANITY || reg.baseType() == BaseType.NONE) {
                    mob.discard();
                    return true;
                }
            }
        }

        // Ensure marksmen that re-load from chunk save have their OutpostMarksmanPatrolGoal attached
        if (mob instanceof com.warfront.entity.PillagerMarksmanEntity sniper) {
            int slotIndex = data.getInt("garrisonSlotIndex");
            BaseGarrisonState state = garrisonData.getGarrison(rx, rz);
            if (state != null && slotIndex >= 0 && slotIndex <= 8) {
                boolean hasGoal = false;
                for (net.minecraft.world.entity.ai.goal.WrappedGoal wg : sniper.goalSelector.getAvailableGoals()) {
                    if (wg.getGoal() instanceof com.warfront.entity.ai.goal.OutpostMarksmanPatrolGoal) {
                        hasGoal = true;
                        break;
                    }
                }
                if (!hasGoal) {
                    sniper.goalSelector.addGoal(1, OutpostGarrisonDefinition.createMarksmanPatrolGoal(sniper, state.baseAnchor(), slotIndex));
                }
            }
        }

        return false;
    }

    private static void tagGarrisonMob(Mob mob, int rx, int rz, int slotIndex, String roleId, Faction faction) {
        mob.addTag("warfront_garrison");
        mob.setPersistenceRequired();

        CompoundTag data = mob.getPersistentData();
        data.putBoolean("warfront_garrison", true);
        data.putInt("garrisonRegionX", rx);
        data.putInt("garrisonRegionZ", rz);
        data.putInt("garrisonSlotIndex", slotIndex);
        data.putString("garrisonRoleId", roleId);
        data.putInt("faction", faction.id());
    }

    private static void enlistMobIntoMission(
            Mob mob,
            UUID instanceId,
            int rx, int rz, int sx, int sz,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        mob.addTag("warfront_mission");
        CompoundTag data = mob.getPersistentData();
        data.putBoolean("isMissionMob", true);
        data.putUUID("missionInstanceId", instanceId);
        data.putInt("originRegionX", rx);
        data.putInt("originRegionZ", rz);
        data.putInt("originSubX", sx);
        data.putInt("originSubZ", sz);
        data.putInt("faction", progress.targetFaction().id());

        MissionEntityTracker.registerMissionMob(mob, instanceId, rx, rz, sx, sz, progress.targetFaction());
        progress.trackedEntityUuids().add(mob.getUUID());
    }
}
