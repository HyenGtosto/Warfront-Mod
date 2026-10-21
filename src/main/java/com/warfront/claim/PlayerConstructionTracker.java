package com.warfront.claim;

import com.warfront.block.WarfrontBlocks;
import com.warfront.region.RegionData;
import com.warfront.spawn.ExplorationSpawnManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated player construction tracker for player-placed Warfront structures and fortifications.
 *
 * <p>Architectural note: As per design invariants, this class serves as an isolated foundation
 * for future player building / fortification detection and is deliberately NOT hooked into
 * active mission generation yet.</p>
 */
public final class PlayerConstructionTracker {

    private static final PlayerConstructionTracker INSTANCE = new PlayerConstructionTracker();

    /**
     * Set of Warfront blocks that qualify as strategic player construction.
     */
    private static final Set<Block> TRACKED_BLOCKS = Set.of(
            WarfrontBlocks.CLAIM_CORE.get(),
            WarfrontBlocks.COMMAND_TERMINAL.get(),
            WarfrontBlocks.MORTAR.get(),
            WarfrontBlocks.AMMO_RACK.get(),
            WarfrontBlocks.SALVAGED_MORTAR_CORE.get(),
            WarfrontBlocks.HEAVY_PIVOT_BEARING.get()
    );

    /**
     * Map of SubRegionKey -> List of tracked block positions.
     * Key is derived via {@link RegionData#subRegionKey(int, int, int, int)}.
     */
    private final Map<Long, List<BlockPos>> trackedPositions = new ConcurrentHashMap<>();

    private PlayerConstructionTracker() {
    }

    public static PlayerConstructionTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Checks if a placed block is a tracked Warfront strategic block and records its position.
     */
    public void onBlockPlaced(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || !TRACKED_BLOCKS.contains(state.getBlock())) {
            return;
        }

        long subKey = resolveSubRegionKey(pos);
        trackedPositions.computeIfAbsent(subKey, k -> new ArrayList<>()).add(pos.immutable());
    }

    /**
     * Removes a destroyed block from tracking if present.
     */
    public void onBlockBroken(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || !TRACKED_BLOCKS.contains(state.getBlock())) {
            return;
        }

        long subKey = resolveSubRegionKey(pos);
        List<BlockPos> list = trackedPositions.get(subKey);
        if (list != null) {
            list.remove(pos);
            if (list.isEmpty()) {
                trackedPositions.remove(subKey);
            }
        }
    }

    /**
     * Returns an unmodifiable list of player construction positions within the specified subregion.
     */
    public List<BlockPos> getPlayerConstructionPositions(int rx, int rz, int sx, int sz) {
        long subKey = RegionData.subRegionKey(rx, rz, sx, sz);
        List<BlockPos> list = trackedPositions.get(subKey);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    /**
     * Returns the count of tracked player construction instances in the subregion.
     */
    public int getPlayerConstructionCount(int rx, int rz, int sx, int sz) {
        long subKey = RegionData.subRegionKey(rx, rz, sx, sz);
        List<BlockPos> list = trackedPositions.get(subKey);
        return list != null ? list.size() : 0;
    }

    /**
     * Checks whether the subregion has any tracked player construction instances.
     */
    public boolean hasPlayerConstruction(int rx, int rz, int sx, int sz) {
        return getPlayerConstructionCount(rx, rz, sx, sz) > 0;
    }

    /**
     * Clears all in-memory tracking data.
     */
    public void clear() {
        trackedPositions.clear();
    }

    private static long resolveSubRegionKey(BlockPos pos) {
        int rx = Math.floorDiv(pos.getX(), RegionData.REGION_SIZE_BLOCKS);
        int rz = Math.floorDiv(pos.getZ(), RegionData.REGION_SIZE_BLOCKS);

        int localX = Math.floorMod(pos.getX(), RegionData.REGION_SIZE_BLOCKS);
        int localZ = Math.floorMod(pos.getZ(), RegionData.REGION_SIZE_BLOCKS);

        int sx = localX / ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int sz = localZ / ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;

        return RegionData.subRegionKey(rx, rz, sx, sz);
    }
}
