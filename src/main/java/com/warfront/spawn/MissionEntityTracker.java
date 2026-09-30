package com.warfront.spawn;

import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.region.Faction;
import net.minecraft.core.particles.ParticleTypes;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative lifecycle manager for mission-specific wave enemies.
 *
 * Distinct from roaming/wandering exploration mobs:
 * - Governed strictly by their specific mission instance (missionInstanceId).
 * - When active: Stays active within 64 blocks of a player; despawns if distance > 64 blocks.
 * - When terminal (cleared, abandoned, cancelled, expired):
 *     - If distance > 64 blocks: despawns immediately.
 *     - If distance <= 64 blocks: despawns one-by-one at a staggered rate (~1.5s interval) with POOF particles.
 */
public final class MissionEntityTracker {

    /** How often (in server ticks) mission mob lifecycle evaluation runs (30 ticks = 1.5 seconds). */
    private static final int EVAL_INTERVAL_TICKS = 30;

    /** Leash radius in blocks beyond which active mission mobs despawn. */
    public static final double ACTIVE_MISSION_DESPAWN_RADIUS_SQ = 64.0D * 64.0D;

    public record TrackedMissionMob(
            UUID entityUuid,
            UUID missionInstanceId,
            int regionX,
            int regionZ,
            int subX,
            int subZ,
            Faction faction,
            long registeredTick
    ) {}

    private static final Map<UUID, TrackedMissionMob> TRACKED_MISSION_MOBS = new ConcurrentHashMap<>();

    private MissionEntityTracker() {
    }

    /**
     * Registers a newly spawned mission mob with its authoritative mission instance metadata.
     */
    public static void registerMissionMob(
            Mob mob,
            UUID missionInstanceId,
            int regionX,
            int regionZ,
            int subX,
            int subZ,
            Faction faction
    ) {
        if (mob == null || missionInstanceId == null) {
            return;
        }
        mob.setNoAi(false);
        TRACKED_MISSION_MOBS.put(mob.getUUID(), new TrackedMissionMob(
                mob.getUUID(),
                missionInstanceId,
                regionX,
                regionZ,
                subX,
                subZ,
                faction,
                mob.level().getGameTime()
        ));
    }

    /**
     * Server tick evaluation hook for mission-specific mob lifecycle.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null || TRACKED_MISSION_MOBS.isEmpty()) {
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

        // Group tracked mobs by missionInstanceId
        Map<UUID, List<TrackedMissionMob>> mobsByMission = new HashMap<>();
        List<UUID> deadOrInvalid = new ArrayList<>();

        for (TrackedMissionMob tracked : TRACKED_MISSION_MOBS.values()) {
            Entity entity = level.getEntity(tracked.entityUuid());
            if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                deadOrInvalid.add(tracked.entityUuid());
                continue;
            }
            mobsByMission.computeIfAbsent(tracked.missionInstanceId(), k -> new ArrayList<>()).add(tracked);
        }

        for (UUID invalidUuid : deadOrInvalid) {
            TRACKED_MISSION_MOBS.remove(invalidUuid);
        }

        // Process lifecycle per mission instance
        for (Map.Entry<UUID, List<TrackedMissionMob>> entry : mobsByMission.entrySet()) {
            UUID missionId = entry.getKey();
            List<TrackedMissionMob> group = entry.getValue();
            if (group.isEmpty()) continue;

            TrackedMissionMob sample = group.get(0);
            boolean isInstanceActive = ActiveCampaignMissionManager.isMissionInstanceActive(
                    missionId, sample.regionX(), sample.regionZ(), sample.subX(), sample.subZ());

            if (isInstanceActive) {
                // CASE A: Mission IS ACTIVE -> 64-block leash
                for (TrackedMissionMob item : group) {
                    Entity entity = level.getEntity(item.entityUuid());
                    if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                        TRACKED_MISSION_MOBS.remove(item.entityUuid());
                        continue;
                    }

                    double minDistSq = getMinDistanceSqToPlayers(mob, players, level);
                    if (minDistSq > ACTIVE_MISSION_DESPAWN_RADIUS_SQ) {
                        // Despawn distant active mission mob so new wave can spawn closer to combat
                        mob.discard();
                        TRACKED_MISSION_MOBS.remove(item.entityUuid());
                    } else {
                        // Keep mob combat-ready
                        mob.setNoAi(false);
                    }
                }
            } else {
                // CASE B: Mission IS TERMINAL (Cleared, Abandoned, Cancelled, Expired)
                List<Mob> nearbyLeftoverMobs = new ArrayList<>();

                for (TrackedMissionMob item : group) {
                    Entity entity = level.getEntity(item.entityUuid());
                    if (!(entity instanceof Mob mob) || !mob.isAlive()) {
                        TRACKED_MISSION_MOBS.remove(item.entityUuid());
                        continue;
                    }

                    double minDistSq = getMinDistanceSqToPlayers(mob, players, level);
                    if (minDistSq > ACTIVE_MISSION_DESPAWN_RADIUS_SQ) {
                        // Despawn distant leftover mob immediately
                        mob.discard();
                        TRACKED_MISSION_MOBS.remove(item.entityUuid());
                    } else {
                        nearbyLeftoverMobs.add(mob);
                    }
                }

                // Staggered Dispersal: Despawn at most 1 nearby leftover mob per evaluation interval
                if (!nearbyLeftoverMobs.isEmpty()) {
                    Mob chosenToDisperse = nearbyLeftoverMobs.get(0);
                    level.sendParticles(
                            ParticleTypes.POOF,
                            chosenToDisperse.getX(),
                            chosenToDisperse.getY() + 1.0D,
                            chosenToDisperse.getZ(),
                            10,
                            0.3D, 0.5D, 0.3D, 0.05D
                    );
                    chosenToDisperse.discard();
                    TRACKED_MISSION_MOBS.remove(chosenToDisperse.getUUID());
                }
            }
        }
    }

    private static double getMinDistanceSqToPlayers(Mob mob, List<ServerPlayer> players, ServerLevel level) {
        double minDistSq = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            if (player.level() != level) continue;
            double dSq = mob.distanceToSqr(player);
            if (dSq < minDistSq) {
                minDistSq = dSq;
            }
        }
        return minDistSq;
    }
}
