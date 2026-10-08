package com.warfront.mission.site;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Encapsulates the before-and-after block state modifications for a temporary mission site.
 *
 * Provides safe rollback/restoration when a mission completes, expires, or is cancelled,
 * preventing world scarring and resource duplication exploits.
 */
public final class MissionSiteSnapshot {

    private final UUID missionInstanceId;
    private final int regionX;
    private final int regionZ;
    private final int subX;
    private final int subZ;

    /** Map of target position -> original BlockState prior to site construction. */
    private final Map<BlockPos, BlockState> originalStates = new HashMap<>();

    public MissionSiteSnapshot(UUID missionInstanceId, int regionX, int regionZ, int subX, int subZ) {
        this.missionInstanceId = missionInstanceId;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.subX = subX;
        this.subZ = subZ;
    }

    public UUID missionInstanceId() {
        return missionInstanceId;
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    public int subX() {
        return subX;
    }

    public int subZ() {
        return subZ;
    }

    /**
     * Records an original block state before modifying it, if not already captured.
     */
    public void recordOriginalState(BlockPos pos, BlockState state) {
        originalStates.putIfAbsent(pos.immutable(), state);
    }

    /**
     * Sets a block in the level while automatically capturing its original state into this snapshot.
     */
    public boolean setBlock(ServerLevel level, BlockPos pos, BlockState newState, int flags) {
        BlockPos immutablePos = pos.immutable();
        if (!originalStates.containsKey(immutablePos)) {
            originalStates.put(immutablePos, level.getBlockState(immutablePos));
        }
        return level.setBlock(immutablePos, newState, flags);
    }

    public record BlockRestoreEntry(BlockPos pos, BlockState targetState) {}

    /**
     * Returns all captured block states sorted descending by Y level for gradual top-to-bottom restoration.
     */
    public java.util.List<BlockRestoreEntry> getSortedRestoreEntries() {
        return originalStates.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().getY(), a.getKey().getY()))
                .map(e -> new BlockRestoreEntry(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Restores all modified blocks back to their captured original states.
     * Restores in reverse vertical order (top to bottom) to prevent gravity block issues.
     */
    public void restore(ServerLevel level) {
        if (level == null || originalStates.isEmpty()) {
            return;
        }

        // Sort descending by Y so upper blocks/props are reverted before foundations
        originalStates.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().getY(), a.getKey().getY()))
                .forEach(entry -> {
                    BlockPos pos = entry.getKey();
                    BlockState original = entry.getValue();
                    level.setBlock(pos, original != null ? original : Blocks.AIR.defaultBlockState(), 3);
                });

        originalStates.clear();
    }

    public int size() {
        return originalStates.size();
    }
}
