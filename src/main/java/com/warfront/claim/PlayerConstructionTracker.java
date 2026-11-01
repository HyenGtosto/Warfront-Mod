package com.warfront.claim;

import com.warfront.block.WarfrontBlocks;
import com.warfront.block.entity.FriendlyMortarBlockEntity;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.spawn.ExplorationSpawnManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated player construction tracker for player-placed Warfront structures and fortifications.
 *
 * Tracks positions and building types across macro regions and subregions to calculate
 * fortification bonuses (Resistance & Stability) and strategic fortification presence.
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
            WarfrontBlocks.HEAVY_PIVOT_BEARING.get(),
            WarfrontBlocks.FRIENDLY_MORTAR.get(),
            WarfrontBlocks.FRIENDLY_BALLISTA.get()
    );

    /**
     * Map of SubRegionKey -> List of tracked block positions.
     */
    private final Map<Long, List<BlockPos>> trackedPositions = new ConcurrentHashMap<>();

    /**
     * Map of BlockPos -> Block type for rapid $O(1)$ structure type lookups.
     */
    private final Map<BlockPos, Block> blockTypeMap = new ConcurrentHashMap<>();

    private PlayerConstructionTracker() {
    }

    public static PlayerConstructionTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Checks if a placed block is a tracked Warfront strategic block and records its position and type.
     */
    public void onBlockPlaced(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || !TRACKED_BLOCKS.contains(state.getBlock())) {
            return;
        }

        BlockPos immutablePos = pos.immutable();
        long subKey = resolveSubRegionKey(immutablePos);
        List<BlockPos> list = trackedPositions.computeIfAbsent(subKey, k -> new ArrayList<>());
        if (!list.contains(immutablePos)) {
            list.add(immutablePos);
        }
        blockTypeMap.put(immutablePos, state.getBlock());
    }

    /**
     * Removes a destroyed block from tracking if present.
     */
    public void onBlockBroken(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || !TRACKED_BLOCKS.contains(state.getBlock())) {
            return;
        }

        BlockPos immutablePos = pos.immutable();
        long subKey = resolveSubRegionKey(immutablePos);
        List<BlockPos> list = trackedPositions.get(subKey);
        if (list != null) {
            list.remove(immutablePos);
            if (list.isEmpty()) {
                trackedPositions.remove(subKey);
            }
        }
        blockTypeMap.remove(immutablePos);
    }

    /**
     * Returns the count of operational friendly mortars in a macro region (across all 4 subregions).
     */
    public int getOperationalFriendlyMortarCount(ServerLevel level, int rx, int rz) {
        if (level == null) return 0;
        int count = 0;
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                List<BlockPos> list = getPlayerConstructionPositions(rx, rz, sx, sz);
                for (BlockPos pos : list) {
                    Block block = blockTypeMap.get(pos);
                    if (block == WarfrontBlocks.FRIENDLY_MORTAR.get()) {
                        if (level.isLoaded(pos)) {
                            BlockEntity be = level.getBlockEntity(pos);
                            if (be instanceof FriendlyMortarBlockEntity mortar && mortar.isOperational()) {
                                count++;
                            }
                        } else {
                            // If chunk not currently loaded, count by block existence
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    /**
     * Returns the count of a specific building type placed within a macro region with self-healing validation.
     */
    public int getBuildingCountInRegion(ServerLevel level, int rx, int rz, Block targetBlock) {
        int count = 0;
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                count += getBuildingCountInSubRegion(level, rx, rz, sx, sz, targetBlock);
            }
        }
        return count;
    }

    /**
     * Legacy overload for caller compatibility without level check.
     */
    public int getBuildingCountInRegion(int rx, int rz, Block targetBlock) {
        return getBuildingCountInRegion(null, rx, rz, targetBlock);
    }

    /**
     * Returns the count of a specific building type placed within a specific subregion with self-healing validation.
     */
    public int getBuildingCountInSubRegion(ServerLevel level, int rx, int rz, int sx, int sz, Block targetBlock) {
        long subKey = RegionData.subRegionKey(rx, rz, sx, sz);
        List<BlockPos> list = trackedPositions.get(subKey);
        if (list == null || list.isEmpty()) {
            return 0;
        }

        int count = 0;
        List<BlockPos> snapshot = new ArrayList<>(list);
        for (BlockPos pos : snapshot) {
            if (blockTypeMap.get(pos) == targetBlock) {
                if (level != null && level.hasChunkAt(pos)) {
                    if (!level.getBlockState(pos).is(targetBlock)) {
                        // Stale entry cleanup (e.g. broken out-of-band)
                        list.remove(pos);
                        blockTypeMap.remove(pos);
                        continue;
                    }
                }
                count++;
            }
        }
        if (list.isEmpty()) {
            trackedPositions.remove(subKey);
        }
        return count;
    }

    /**
     * Handles regional fall / conquest transitions for all tracked player constructions in a macro region.
     */
    public void onRegionOwnershipChanged(ServerLevel level, int rx, int rz, Faction oldFaction, UUID oldPlayerFactionId, Faction newFaction, UUID newPlayerFactionId) {
        if (level == null) return;

        boolean factionChanged = (oldFaction != newFaction);
        boolean playerFactionChanged = !Objects.equals(oldPlayerFactionId, newPlayerFactionId);
        if (!factionChanged && !playerFactionChanged) {
            return;
        }

        List<BlockPos> positions = new ArrayList<>();
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                positions.addAll(getPlayerConstructionPositions(rx, rz, sx, sz));
            }
        }
        if (positions.isEmpty()) {
            return;
        }

        if (newFaction.isAI()) {
            // AI Takeover: Void inventories and destroy warfront structures completely
            for (BlockPos pos : positions) {
                if (!level.hasChunkAt(pos)) {
                    level.getChunk(pos);
                }
                BlockState state = level.getBlockState(pos);
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof Container container) {
                    container.clearContent();
                }
                if (be instanceof FriendlyMortarBlockEntity mortar) {
                    mortar.voidChamber();
                }
                if (be instanceof com.warfront.block.entity.FriendlyBallistaBlockEntity ballista) {
                    ballista.voidChamber();
                }
                level.destroyBlock(pos, false);
                if (!level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                onBlockBroken(level, pos, state);
            }
        } else if (newFaction == Faction.UNCLAIMED || (newFaction == Faction.HUMANITY && playerFactionChanged)) {
            // Wilderness or foreign player faction conquest: Deactivate structures so they can be looted
            for (BlockPos pos : positions) {
                if (level.hasChunkAt(pos)) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof FriendlyMortarBlockEntity mortar) {
                        mortar.setDeactivated(true);
                        mortar.setChanged();
                        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
                    }
                    if (be instanceof com.warfront.block.entity.FriendlyBallistaBlockEntity ballista) {
                        ballista.setDeactivated(true);
                        ballista.setChanged();
                        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
                    }
                }
            }
        } else if (newFaction == Faction.HUMANITY && newPlayerFactionId != null) {
            // Reclaimed by owning player faction: Reactivate matching mortars
            for (BlockPos pos : positions) {
                if (level.hasChunkAt(pos)) {
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof FriendlyMortarBlockEntity mortar) {
                        if (mortar.getOwnerFactionId() != null && mortar.getOwnerFactionId().equals(newPlayerFactionId)) {
                            mortar.setDeactivated(false);
                            mortar.setChanged();
                            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
                        }
                    }
                    if (be instanceof com.warfront.block.entity.FriendlyBallistaBlockEntity ballista) {
                        if (ballista.getOwnerFactionId() != null && ballista.getOwnerFactionId().equals(newPlayerFactionId)) {
                            ballista.setDeactivated(false);
                            ballista.setChanged();
                            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
                        }
                    }
                }
            }
        }
    }

    /**
     * Serializes tracked structures to NBT for world save persistence.
     */
    public CompoundTag save(CompoundTag tag) {
        ListTag listTag = new ListTag();
        for (Map.Entry<BlockPos, Block> entry : blockTypeMap.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            BlockPos pos = entry.getKey();
            entryTag.putInt("x", pos.getX());
            entryTag.putInt("y", pos.getY());
            entryTag.putInt("z", pos.getZ());
            entryTag.putString("block", BuiltInRegistries.BLOCK.getKey(entry.getValue()).toString());
            listTag.add(entryTag);
        }
        tag.put("tracked_constructions", listTag);
        return tag;
    }

    /**
     * Loads tracked structures from NBT on world load.
     */
    public void load(CompoundTag tag) {
        clear();
        ListTag listTag = tag.getList("tracked_constructions", Tag.TAG_COMPOUND);
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag entryTag = listTag.getCompound(i);
            BlockPos pos = new BlockPos(entryTag.getInt("x"), entryTag.getInt("y"), entryTag.getInt("z"));
            ResourceLocation id = ResourceLocation.tryParse(entryTag.getString("block"));
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                Block block = BuiltInRegistries.BLOCK.get(id);
                long subKey = resolveSubRegionKey(pos);
                List<BlockPos> list = trackedPositions.computeIfAbsent(subKey, k -> new ArrayList<>());
                if (!list.contains(pos)) {
                    list.add(pos);
                }
                blockTypeMap.put(pos, block);
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
        blockTypeMap.clear();
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
