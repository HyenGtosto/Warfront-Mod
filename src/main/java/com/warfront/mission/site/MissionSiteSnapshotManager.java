package com.warfront.mission.site;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry and lifecycle controller for active temporary mission site snapshots.
 * Supports gradual, cascading top-to-bottom block removal with POOF particle effects.
 */
public final class MissionSiteSnapshotManager {

    private static final Map<UUID, MissionSiteSnapshot> ACTIVE_SNAPSHOTS = new ConcurrentHashMap<>();
    private static final List<ActiveDemolitionTask> ACTIVE_TASKS = new CopyOnWriteArrayList<>();

    public static final class ActiveDemolitionTask {
        private final ServerLevel level;
        private final List<MissionSiteSnapshot.BlockRestoreEntry> entries;
        private final BlockPos centerPos;
        private int currentIndex = 0;

        public ActiveDemolitionTask(ServerLevel level, List<MissionSiteSnapshot.BlockRestoreEntry> entries, BlockPos centerPos) {
            this.level = level;
            this.entries = entries;
            this.centerPos = centerPos;
        }

        public boolean tick() {
            if (level == null || currentIndex >= entries.size()) {
                return true;
            }

            boolean hasPlayerNearby = false;
            for (ServerPlayer player : level.players()) {
                if (player.level() != level) continue;
                if (centerPos == null || player.distanceToSqr(centerPos.getX() + 0.5D, centerPos.getY() + 0.5D, centerPos.getZ() + 0.5D) <= 64.0D * 64.0D) {
                    hasPlayerNearby = true;
                    break;
                }
            }

            // Distant structures finish quickly; nearby structures dissolve gradually with POOF particles (2 blocks/tick)
            int batchSize = hasPlayerNearby ? 2 : 25;

            for (int i = 0; i < batchSize && currentIndex < entries.size(); i++) {
                MissionSiteSnapshot.BlockRestoreEntry entry = entries.get(currentIndex++);
                BlockPos pos = entry.pos();
                BlockState targetState = entry.targetState() != null ? entry.targetState() : Blocks.AIR.defaultBlockState();

                if (hasPlayerNearby) {
                    level.sendParticles(
                            ParticleTypes.POOF,
                            pos.getX() + 0.5D,
                            pos.getY() + 0.5D,
                            pos.getZ() + 0.5D,
                            6,
                            0.25D, 0.25D, 0.25D, 0.04D
                    );
                    if (i == 0 || (currentIndex % 6 == 0)) {
                        level.playSound(null, pos, SoundEvents.CHICKEN_EGG, SoundSource.BLOCKS, 0.6F, 1.2F);
                    }
                }
                level.setBlock(pos, targetState, 3);
            }

            return currentIndex >= entries.size();
        }

        public void finishImmediately() {
            while (currentIndex < entries.size()) {
                MissionSiteSnapshot.BlockRestoreEntry entry = entries.get(currentIndex++);
                BlockPos pos = entry.pos();
                BlockState targetState = entry.targetState() != null ? entry.targetState() : Blocks.AIR.defaultBlockState();
                level.setBlock(pos, targetState, 3);
            }
        }
    }

    private MissionSiteSnapshotManager() {
    }

    /**
     * Registers a new snapshot for an active mission instance.
     */
    public static void register(MissionSiteSnapshot snapshot) {
        if (snapshot != null && snapshot.missionInstanceId() != null) {
            ACTIVE_SNAPSHOTS.put(snapshot.missionInstanceId(), snapshot);
        }
    }

    /**
     * Gets or creates a snapshot for the specified mission instance.
     */
    public static MissionSiteSnapshot getOrCreate(UUID missionInstanceId, int regionX, int regionZ, int subX, int subZ) {
        if (missionInstanceId == null) {
            return new MissionSiteSnapshot(UUID.randomUUID(), regionX, regionZ, subX, subZ);
        }
        return ACTIVE_SNAPSHOTS.computeIfAbsent(missionInstanceId, id -> new MissionSiteSnapshot(id, regionX, regionZ, subX, subZ));
    }

    /**
     * Retrieves an active snapshot if present.
     */
    public static MissionSiteSnapshot get(UUID missionInstanceId) {
        return (missionInstanceId != null) ? ACTIVE_SNAPSHOTS.get(missionInstanceId) : null;
    }

    /**
     * Restores and unregisters the snapshot for a completed or cancelled mission instance,
     * queuing it for gradual top-to-bottom removal with POOF particles.
     */
    public static void restoreAndRemove(ServerLevel level, UUID missionInstanceId) {
        if (missionInstanceId == null) return;
        MissionSiteSnapshot snapshot = ACTIVE_SNAPSHOTS.remove(missionInstanceId);
        if (snapshot != null && level != null) {
            List<MissionSiteSnapshot.BlockRestoreEntry> entries = snapshot.getSortedRestoreEntries();
            if (!entries.isEmpty()) {
                BlockPos centerPos = entries.get(0).pos();
                ACTIVE_TASKS.add(new ActiveDemolitionTask(level, entries, centerPos));
            }
        }
    }

    /**
     * Queues an arbitrary set of block positions (e.g. remaining outpost structure blocks)
     * for gradual top-to-bottom demolition with POOF particles.
     */
    public static void queueGradualDemolition(ServerLevel level, Collection<BlockPos> positions, BlockPos centerPos) {
        if (level == null || positions == null || positions.isEmpty()) return;
        List<MissionSiteSnapshot.BlockRestoreEntry> entries = positions.stream()
                .sorted((a, b) -> Integer.compare(b.getY(), a.getY()))
                .map(pos -> new MissionSiteSnapshot.BlockRestoreEntry(pos, Blocks.AIR.defaultBlockState()))
                .toList();
        BlockPos center = centerPos != null ? centerPos : entries.get(0).pos();
        ACTIVE_TASKS.add(new ActiveDemolitionTask(level, entries, center));
    }

    /**
     * Server tick evaluation hook for gradual block demolition and restoration.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE_TASKS.isEmpty()) return;
        ACTIVE_TASKS.removeIf(ActiveDemolitionTask::tick);
    }

    /**
     * Clears all snapshots and finishes running demolition tasks (e.g. on server stop).
     */
    public static void clearAll() {
        for (ActiveDemolitionTask task : ACTIVE_TASKS) {
            task.finishImmediately();
        }
        ACTIVE_TASKS.clear();
        ACTIVE_SNAPSHOTS.clear();
    }
}
