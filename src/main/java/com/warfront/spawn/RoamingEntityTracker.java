package com.warfront.spawn;

import com.warfront.config.WarfrontConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks all Warfront-owned roaming encounter entities and manages their AI
 * activation state based on player proximity.
 *
 * Identity:
 *   Warfront-owned mobs are identified by the NBT tag {@value #WARFRONT_TAG}
 *   written to each mob's persistent data at spawn time by EnemyEncounterSpawner.
 *   Naturally-spawned zombies/pillagers never carry this tag.
 *
 * AI control:
 *   Uses Mob.setNoAi(boolean) — the standard Minecraft mechanism that prevents
 *   the entity's goal selector and navigation from ticking. The entity otherwise
 *   remains fully present: health, position, and inventory are unaffected.
 *
 * Hysteresis:
 *   - A mob with AI INACTIVE transitions to ACTIVE when any player is within
 *     {@code ROAMING_AI_ACTIVATION_RADIUS} blocks.
 *   - A mob with AI ACTIVE transitions to INACTIVE only when ALL players are
 *     beyond {@code ROAMING_AI_DEACTIVATION_RADIUS} blocks.
 *   The two radii are different (48 vs 64 by default) to prevent oscillation
 *   at the boundary.
 *
 * Evaluation cadence:
 *   Distance checks run every {@value #EVAL_INTERVAL_TICKS} ticks (2 seconds)
 *   via ServerTickEvent.Post. This is cheap: only tracked Warfront mobs are
 *   considered, and only online players are iterated.
 *
 * Lifecycle:
 *   Dead or unloaded entities are pruned from the tracking set each evaluation.
 *   The tracking set is transient — it resets on server restart, which is
 *   acceptable because all mobs start with AI active and the first evaluation
 *   cycle will correctly set their state.
 */
public final class RoamingEntityTracker {

    /** NBT key written to every Warfront-spawned roaming mob's persistent data. */
    public static final String WARFRONT_TAG = "warfront_roaming";

    /**
     * How often (in server ticks) the distance evaluation runs.
     * 40 ticks = 2 seconds. Low enough for responsive AI transitions,
     * high enough that iterating tracked entities has negligible cost.
     */
    private static final int EVAL_INTERVAL_TICKS = 40;

    /**
     * Tracked entity state: UUID → currently AI-active?
     * Boolean value: true = AI currently enabled (noAi = false).
     */
    private static final Map<UUID, Boolean> TRACKED = new HashMap<>();

    private RoamingEntityTracker() {
    }

    // -----------------------------------------------------------------------
    // Registration (called by EnemyEncounterSpawner)
    // -----------------------------------------------------------------------

    /**
     * Registers a newly spawned Warfront roaming mob for tracking with origin metadata.
     *
     * Must be called after the entity has been added to the world.
     *
     * @param mob     the successfully spawned, world-added Warfront mob
     * @param regionX origin region X
     * @param regionZ origin region Z
     * @param subX    origin subregion X
     * @param subZ    origin subregion Z
     * @param faction origin faction
     */
    public static void register(Mob mob, int regionX, int regionZ, int subX, int subZ, com.warfront.region.Faction faction) {
        CompoundTag data = mob.getPersistentData();
        data.putBoolean(WARFRONT_TAG, true);
        data.putInt("originRegionX", regionX);
        data.putInt("originRegionZ", regionZ);
        data.putInt("originSubX", subX);
        data.putInt("originSubZ", subZ);
        data.putInt("faction", faction.id());

        // Ensure AI starts active
        mob.setNoAi(false);

        TRACKED.put(mob.getUUID(), Boolean.TRUE);
    }

    /**
     * Registers a newly spawned Warfront roaming mob for tracking.
     *
     * @param mob the successfully spawned, world-added Warfront mob
     */
    public static void register(Mob mob) {
        register(mob, 0, 0, 0, 0, com.warfront.region.Faction.UNCLAIMED);
    }

    // -----------------------------------------------------------------------
    // Server tick evaluation
    // -----------------------------------------------------------------------

    /**
     * Periodic distance evaluation — called every server tick, acts every
     * EVAL_INTERVAL_TICKS ticks.
     *
     * For each tracked mob:
     *   - Prune if dead, removed, or unloaded.
     *   - Collect online player positions once.
     *   - Compute minimum player distance to mob.
     *   - Apply hysteresis to decide whether to activate or deactivate AI.
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

        // Snapshot of online player positions — computed once per evaluation
        List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            // No players online — leave all mobs in their current AI state
            return;
        }

        int activationRadius  = WarfrontConfig.ROAMING_AI_ACTIVATION_RADIUS.get();
        int deactivationRadius = WarfrontConfig.ROAMING_AI_DEACTIVATION_RADIUS.get();
        // Ensure deactivation is always strictly greater than activation to preserve hysteresis
        if (deactivationRadius <= activationRadius) {
            deactivationRadius = activationRadius + 1;
        }

        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, Boolean> entry : TRACKED.entrySet()) {
            UUID uuid = entry.getKey();
            boolean currentlyActive = entry.getValue();

            // Resolve entity from world
            Entity entity = level.getEntity(uuid);
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                toRemove.add(uuid);
                continue;
            }

            // Safety: only affect Warfront-tagged mobs
            CompoundTag data = mob.getPersistentData();
            if (!data.getBoolean(WARFRONT_TAG)) {
                toRemove.add(uuid);
                continue;
            }

            // Find minimum distance to any online player
            double minDistSq = Double.MAX_VALUE;
            for (ServerPlayer player : players) {
                // Only consider players in the same level
                if (player.level() != level) continue;
                double dSq = mob.distanceToSqr(player);
                if (dSq < minDistSq) {
                    minDistSq = dSq;
                }
            }

            // Apply hysteresis
            if (!currentlyActive) {
                // INACTIVE → check activation threshold
                double activationSq = (double) activationRadius * activationRadius;
                if (minDistSq <= activationSq) {
                    mob.setNoAi(false);
                    entry.setValue(Boolean.TRUE);
                }
            } else {
                // ACTIVE → check deactivation threshold
                double deactivationSq = (double) deactivationRadius * deactivationRadius;
                if (minDistSq > deactivationSq) {
                    mob.setNoAi(true);
                    entry.setValue(Boolean.FALSE);
                }
            }
        }

        // Remove dead/invalid entries
        for (UUID uuid : toRemove) {
            TRACKED.remove(uuid);
        }
    }
}
