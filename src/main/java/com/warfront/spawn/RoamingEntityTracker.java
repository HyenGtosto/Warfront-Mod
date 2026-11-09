package com.warfront.spawn;

import com.warfront.config.WarfrontConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks and manages AI activation states for wandering out-of-war exploration mobs.
 *
 * Distinct from active mission wave enemies:
 * - Wandering mobs are persistent across exploration and are never despawned by mission events.
 * - AI activation follows standard hysteresis based on ROAMING_AI_ACTIVATION_RADIUS and ROAMING_AI_DEACTIVATION_RADIUS.
 */
public final class RoamingEntityTracker {

    /** NBT key written to every Warfront-spawned roaming mob's persistent data. */
    public static final String WARFRONT_TAG = "warfront_roaming";

    /**
     * How often (in server ticks) the distance evaluation runs.
     * 40 ticks = 2 seconds.
     */
    private static final int EVAL_INTERVAL_TICKS = 40;

    /**
     * Tracked entity state: UUID → currently AI-active?
     * Boolean value: true = AI currently enabled (noAi = false).
     */
    private static final Map<UUID, Boolean> TRACKED = new HashMap<>();

    /** Tracks consecutive evaluations where an entity was null due to chunk unloading. */
    private static final Map<UUID, Integer> UNLOADED_MISS_TICKS = new HashMap<>();

    private RoamingEntityTracker() {
    }

    /**
     * Registers a newly spawned wandering exploration mob for tracking.
     */
    public static void registerWandering(Mob mob, int regionX, int regionZ, int subX, int subZ, com.warfront.region.Faction faction) {
        if (mob == null) return;
        mob.setNoAi(false);
        TRACKED.put(mob.getUUID(), Boolean.TRUE);
    }

    public static void register(Mob mob, int regionX, int regionZ, int subX, int subZ, com.warfront.region.Faction faction) {
        registerWandering(mob, regionX, regionZ, subX, subZ, faction);
    }

    public static void register(Mob mob) {
        registerWandering(mob, 0, 0, 0, 0, com.warfront.region.Faction.UNCLAIMED);
    }

    /**
     * Periodic distance evaluation for wandering exploration mobs.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null || TRACKED.isEmpty()) {
            return;
        }

        long gameTime = level.getGameTime();
        if (gameTime % EVAL_INTERVAL_TICKS != 0) {
            return;
        }

        List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        int activationRadius  = WarfrontConfig.ROAMING_AI_ACTIVATION_RADIUS.get();
        int deactivationRadius = WarfrontConfig.ROAMING_AI_DEACTIVATION_RADIUS.get();
        if (deactivationRadius <= activationRadius) {
            deactivationRadius = activationRadius + 1;
        }

        int despawnRadius = WarfrontConfig.ROAMING_DESPAWN_RADIUS.get();
        double despawnRadiusSq = (double) despawnRadius * despawnRadius;

        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, Boolean> entry : TRACKED.entrySet()) {
            UUID uuid = entry.getKey();
            boolean currentlyActive = entry.getValue();

            Entity entity = level.getEntity(uuid);
            if (entity == null) {
                // If chunk is unloaded, entity is temporarily null in memory; don't remove immediately.
                int missed = UNLOADED_MISS_TICKS.merge(uuid, 1, Integer::sum);
                if (missed > 15) { // 15 evaluations * 2s = 30 seconds buffer for unloaded chunks
                    toRemove.add(uuid);
                    UNLOADED_MISS_TICKS.remove(uuid);
                }
                continue;
            }
            UNLOADED_MISS_TICKS.remove(uuid);

            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                toRemove.add(uuid);
                continue;
            }

            CompoundTag data = mob.getPersistentData();
            if (!data.getBoolean(WARFRONT_TAG) || data.getBoolean("isMissionMob")) {
                // If it's not a wandering mob (e.g. mission mob handled by MissionEntityTracker), exclude here
                toRemove.add(uuid);
                continue;
            }

            // Sanitizer: restore physics & AI on any Warfront mob previously flagged with NoAI: true
            if (mob.isNoAi()) {
                mob.setNoAi(false);
            }

            double minDistSq = Double.MAX_VALUE;
            for (ServerPlayer player : players) {
                if (player.level() != level) continue;
                double dx = mob.getX() - player.getX();
                double dz = mob.getZ() - player.getZ();
                double dSq = dx * dx + dz * dz; // 2D horizontal distance
                if (dSq < minDistSq) {
                    minDistSq = dSq;
                }
            }

            // Despawn wandering mobs that wandered too far beyond the relaxed despawn radius
            if (minDistSq > despawnRadiusSq && !mob.hasCustomName()) {
                if (level.getDifficulty() != Difficulty.PEACEFUL && data.getBoolean("isPatrolMob") && data.hasUUID("squadId")) {
                    UUID squadId = data.getUUID("squadId");
                    int rx = data.getInt("originRegionX");
                    int rz = data.getInt("originRegionZ");
                    int sx = data.getInt("originSubX");
                    int sz = data.getInt("originSubZ");
                    SubregionPatrolManager.notifySquadDistanceDespawned(squadId, rx, rz, sx, sz);
                }
                mob.discard();
                toRemove.add(uuid);
                continue;
            }

            // Vanilla and Lithium safely throttle distant entity ticking; never use destructive setNoAi(true)
            if (!currentlyActive) {
                entry.setValue(Boolean.TRUE);
            }
        }

        for (UUID uuid : toRemove) {
            TRACKED.remove(uuid);
            UNLOADED_MISS_TICKS.remove(uuid);
        }
    }
}
