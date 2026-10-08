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
     * Registers a newly spawned mission entity with its authoritative mission instance metadata.
     */
    public static void registerMissionEntity(
            Entity entity,
            UUID missionInstanceId,
            int regionX,
            int regionZ,
            int subX,
            int subZ,
            Faction faction
    ) {
        if (entity == null || missionInstanceId == null) {
            return;
        }
        if (entity instanceof Mob mob) {
            mob.setNoAi(false);
        }
        TRACKED_MISSION_MOBS.put(entity.getUUID(), new TrackedMissionMob(
                entity.getUUID(),
                missionInstanceId,
                regionX,
                regionZ,
                subX,
                subZ,
                faction,
                entity.level().getGameTime()
        ));
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
        registerMissionEntity(mob, missionInstanceId, regionX, regionZ, subX, subZ, faction);
    }

    /**
     * Counts how many mobs belonging to a specific mission instance are currently alive in the level.
     */
    public static int getLivingMissionMobCount(UUID missionInstanceId, ServerLevel level) {
        if (missionInstanceId == null || level == null || TRACKED_MISSION_MOBS.isEmpty()) {
            return 0;
        }
        int living = 0;
        for (TrackedMissionMob item : TRACKED_MISSION_MOBS.values()) {
            if (missionInstanceId.equals(item.missionInstanceId())) {
                Entity entity = level.getEntity(item.entityUuid());
                if (entity != null && entity.isAlive()) {
                    living++;
                }
            }
        }
        return living;
    }

    /**
     * Server tick evaluation hook for mission-specific entity lifecycle.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null || TRACKED_MISSION_MOBS.isEmpty()) {
            return;
        }

        long gameTime = level.getGameTime();
        boolean checkActiveLeash = (gameTime % EVAL_INTERVAL_TICKS == 0);
        boolean checkTerminalDispersal = (gameTime % 10 == 0); // 10 ticks = 0.5s per entity dispersal

        if (!checkActiveLeash && !checkTerminalDispersal) {
            return;
        }

        List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        // Group tracked entities by missionInstanceId
        Map<UUID, List<TrackedMissionMob>> mobsByMission = new HashMap<>();
        List<UUID> deadOrInvalid = new ArrayList<>();

        for (TrackedMissionMob tracked : TRACKED_MISSION_MOBS.values()) {
            Entity entity = level.getEntity(tracked.entityUuid());
            if (entity == null || !entity.isAlive()) {
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
                if (!checkActiveLeash) continue;
                // CASE A: Mission IS ACTIVE -> 64-block leash
                for (TrackedMissionMob item : group) {
                    Entity entity = level.getEntity(item.entityUuid());
                    if (entity == null || !entity.isAlive()) {
                        TRACKED_MISSION_MOBS.remove(item.entityUuid());
                        continue;
                    }

                    double minDistSq = getMinDistanceSqToPlayers(entity, players, level);
                    if (minDistSq > ACTIVE_MISSION_DESPAWN_RADIUS_SQ) {
                        // Despawn distant active mission mob so new wave can spawn closer to combat
                        entity.discard();
                        TRACKED_MISSION_MOBS.remove(item.entityUuid());
                    } else if (entity instanceof Mob mob) {
                        // Keep mob combat-ready
                        mob.setNoAi(false);
                    }
                }
            } else {
                if (!checkTerminalDispersal) continue;
                // CASE B: Mission IS TERMINAL (Cleared, Abandoned, Cancelled, Expired)
                List<Entity> nearbyLeftoverEntities = new ArrayList<>();

                for (TrackedMissionMob item : group) {
                    Entity entity = level.getEntity(item.entityUuid());
                    if (entity == null || !entity.isAlive()) {
                        TRACKED_MISSION_MOBS.remove(item.entityUuid());
                        continue;
                    }

                    double minDistSq = getMinDistanceSqToPlayers(entity, players, level);
                    if (minDistSq > ACTIVE_MISSION_DESPAWN_RADIUS_SQ) {
                        // Despawn distant leftover mob immediately
                        entity.discard();
                        TRACKED_MISSION_MOBS.remove(item.entityUuid());
                    } else {
                        nearbyLeftoverEntities.add(entity);
                    }
                }

                // Staggered Dispersal: Despawn at most 1 nearby leftover entity per evaluation interval with POOF particles
                if (!nearbyLeftoverEntities.isEmpty()) {
                    Entity chosenToDisperse = nearbyLeftoverEntities.get(0);
                    level.sendParticles(
                            ParticleTypes.POOF,
                            chosenToDisperse.getX(),
                            chosenToDisperse.getY() + Math.max(0.5D, chosenToDisperse.getBbHeight() / 2.0D),
                            chosenToDisperse.getZ(),
                            12,
                            0.35D, 0.45D, 0.35D, 0.05D
                    );
                    level.playSound(
                            null,
                            chosenToDisperse.blockPosition(),
                            net.minecraft.sounds.SoundEvents.CHICKEN_EGG,
                            net.minecraft.sounds.SoundSource.HOSTILE,
                            0.8F,
                            1.2F
                    );
                    chosenToDisperse.discard();
                    TRACKED_MISSION_MOBS.remove(chosenToDisperse.getUUID());
                }
            }
        }
    }

    private static double getMinDistanceSqToPlayers(Entity entity, List<ServerPlayer> players, ServerLevel level) {
        double minDistSq = Double.MAX_VALUE;
        for (ServerPlayer player : players) {
            if (player.level() != level) continue;
            double dSq = entity.distanceToSqr(player);
            if (dSq < minDistSq) {
                minDistSq = dSq;
            }
        }
        return minDistSq;
    }
}
