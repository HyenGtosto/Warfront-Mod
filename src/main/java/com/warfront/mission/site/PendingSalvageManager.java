package com.warfront.mission.site;

import com.warfront.Warfront;
import com.warfront.block.SalvageMachineryBlock;
import com.warfront.block.WarfrontBlocks;
import com.warfront.mission.ActiveCampaignMissionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages post-war temporary structure persistence and salvage-triggered decay (Scenario 3).
 *
 * When a player faction captures a region, temporary mission structures that contain
 * unharvested salvage blocks remain standing until the player collects all possible loot.
 * Once all salvage blocks are mined/collected, the remaining fortifications decay with POOF particles.
 */
public final class PendingSalvageManager {

    public record PendingSalvageSite(
            UUID missionInstanceId,
            int regionX,
            int regionZ,
            int subX,
            int subZ,
            BlockPos anchor,
            Set<BlockPos> salvageBlocks,
            List<BlockPos> trackedStructureBlocks
    ) {
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("instance_id", missionInstanceId);
            tag.putInt("rx", regionX);
            tag.putInt("rz", regionZ);
            tag.putInt("sx", subX);
            tag.putInt("sz", subZ);
            if (anchor != null) {
                tag.putInt("anchor_x", anchor.getX());
                tag.putInt("anchor_y", anchor.getY());
                tag.putInt("anchor_z", anchor.getZ());
            }

            ListTag salvageList = new ListTag();
            for (BlockPos pos : salvageBlocks) {
                CompoundTag bTag = new CompoundTag();
                bTag.putInt("x", pos.getX());
                bTag.putInt("y", pos.getY());
                bTag.putInt("z", pos.getZ());
                salvageList.add(bTag);
            }
            tag.put("salvage_blocks", salvageList);

            ListTag allBlocksList = new ListTag();
            for (BlockPos pos : trackedStructureBlocks) {
                CompoundTag bTag = new CompoundTag();
                bTag.putInt("x", pos.getX());
                bTag.putInt("y", pos.getY());
                bTag.putInt("z", pos.getZ());
                allBlocksList.add(bTag);
            }
            tag.put("tracked_blocks", allBlocksList);

            return tag;
        }

        public static PendingSalvageSite fromTag(CompoundTag tag) {
            if (tag == null) return null;
            UUID id = tag.contains("instance_id") ? tag.getUUID("instance_id") : UUID.randomUUID();
            int rx = tag.getInt("rx");
            int rz = tag.getInt("rz");
            int sx = tag.getInt("sx");
            int sz = tag.getInt("sz");

            BlockPos anc = null;
            if (tag.contains("anchor_x")) {
                anc = new BlockPos(tag.getInt("anchor_x"), tag.getInt("anchor_y"), tag.getInt("anchor_z"));
            }

            Set<BlockPos> salvage = Collections.newSetFromMap(new ConcurrentHashMap<>());
            if (tag.contains("salvage_blocks", Tag.TAG_LIST)) {
                ListTag sList = tag.getList("salvage_blocks", Tag.TAG_COMPOUND);
                for (int i = 0; i < sList.size(); i++) {
                    CompoundTag bTag = sList.getCompound(i);
                    salvage.add(new BlockPos(bTag.getInt("x"), bTag.getInt("y"), bTag.getInt("z")));
                }
            }

            List<BlockPos> tracked = new ArrayList<>();
            if (tag.contains("tracked_blocks", Tag.TAG_LIST)) {
                ListTag tList = tag.getList("tracked_blocks", Tag.TAG_COMPOUND);
                for (int i = 0; i < tList.size(); i++) {
                    CompoundTag bTag = tList.getCompound(i);
                    tracked.add(new BlockPos(bTag.getInt("x"), bTag.getInt("y"), bTag.getInt("z")));
                }
            }

            return new PendingSalvageSite(id, rx, rz, sx, sz, anc, salvage, tracked);
        }
    }

    private static final Map<UUID, PendingSalvageSite> PENDING_SITES = new ConcurrentHashMap<>();

    private PendingSalvageManager() {
    }

    public static Map<UUID, PendingSalvageSite> getAllPendingSites() {
        return Collections.unmodifiableMap(PENDING_SITES);
    }

    /**
     * Checks if there is an active pending salvage site waiting for loot collection in the given region.
     */
    public static boolean hasPendingSiteForRegion(int regionX, int regionZ) {
        for (PendingSalvageSite site : PENDING_SITES.values()) {
            if (site.regionX() == regionX && site.regionZ() == regionZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inspects placed blocks in a temporary structure to find remaining salvage/loot blocks.
     * Automatically disarms any locked SalvageMachineryBlocks so they are harvestable post-victory.
     */
    public static Set<BlockPos> findRemainingSalvageBlocks(ServerLevel level, ActiveCampaignMissionManager.ActiveSubRegionProgress progress) {
        Set<BlockPos> salvage = Collections.newSetFromMap(new ConcurrentHashMap<>());
        if (level == null || progress == null) return salvage;

        for (BlockPos pos : progress.trackedBlockPositions()) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);

            if (state.getBlock() instanceof SalvageMachineryBlock) {
                SalvageMachineryBlock.disarm(level, pos);
                salvage.add(pos.immutable());
            } else if (state.is(WarfrontBlocks.MORTAR.get()) || state.is(WarfrontBlocks.AMMO_RACK.get())
                    || state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) {
                salvage.add(pos.immutable());
            }
        }

        return salvage;
    }

    /**
     * Registers a temporary structure for Scenario 3 (War Won).
     * If all salvage blocks have already been harvested (or none existed), the structure decays immediately.
     * Otherwise, the structure remains standing until all remaining salvage blocks are mined.
     */
    public static void registerPostWarStructure(ServerLevel level, ActiveCampaignMissionManager.ActiveSubRegionProgress progress) {
        if (level == null || progress == null) return;

        Set<BlockPos> remainingSalvage = findRemainingSalvageBlocks(level, progress);
        if (remainingSalvage.isEmpty()) {
            // No salvage blocks to collect -> Decays immediately
            MissionSiteSnapshotManager.restoreAndRemove(level, progress.missionInstanceId(), progress.trackedBlockPositions(), progress.missionSiteAnchor());
            Warfront.LOGGER.info("Post-war structure at ({}, {}) Sub ({}, {}) had no remaining salvage blocks; decaying immediately.",
                    progress.regionX(), progress.regionZ(), progress.subX(), progress.subZ());
        } else {
            // Has unharvested salvage blocks -> Remains in world until looted
            PendingSalvageSite site = new PendingSalvageSite(
                    progress.missionInstanceId(),
                    progress.regionX(),
                    progress.regionZ(),
                    progress.subX(),
                    progress.subZ(),
                    progress.missionSiteAnchor(),
                    remainingSalvage,
                    new ArrayList<>(progress.trackedBlockPositions())
            );
            PENDING_SITES.put(progress.missionInstanceId(), site);
            Warfront.LOGGER.info("Post-war structure at ({}, {}) Sub ({}, {}) registered for salvage tracking with {} unharvested blocks.",
                    progress.regionX(), progress.regionZ(), progress.subX(), progress.subZ(), remainingSalvage.size());
        }
    }

    /**
     * Intercepts broken blocks to advance salvage collection and trigger decay when all loot is collected.
     */
    public static void onBlockBroken(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (level == null || pos == null || PENDING_SITES.isEmpty()) return;

        List<UUID> completedSites = new ArrayList<>();

        for (PendingSalvageSite site : PENDING_SITES.values()) {
            if (site.salvageBlocks().remove(pos)) {
                if (site.salvageBlocks().isEmpty()) {
                    completedSites.add(site.missionInstanceId());
                } else if (player != null) {
                    player.displayClientMessage(
                            Component.literal("§a[Warfront] §6Salvaged military hardware! §e" + site.salvageBlocks().size() + " salvage block(s) remaining."),
                            true
                    );
                }
            }
        }

        for (UUID siteId : completedSites) {
            triggerSiteDecay(level, siteId, player);
        }
    }

    /**
     * Periodic verification (runs on server tick) to catch indirect or explosion destruction of salvage blocks.
     */
    public static void tick(ServerLevel level) {
        if (level == null || PENDING_SITES.isEmpty() || level.getGameTime() % 20 != 0) return;

        List<UUID> completedSites = new ArrayList<>();

        for (PendingSalvageSite site : PENDING_SITES.values()) {
            site.salvageBlocks().removeIf(pos -> {
                if (!level.hasChunkAt(pos)) return false;
                BlockState state = level.getBlockState(pos);
                return !(state.getBlock() instanceof SalvageMachineryBlock)
                        && !state.is(WarfrontBlocks.MORTAR.get())
                        && !state.is(WarfrontBlocks.AMMO_RACK.get())
                        && !state.is(Blocks.CHEST)
                        && !state.is(Blocks.TRAPPED_CHEST);
            });

            if (site.salvageBlocks().isEmpty()) {
                completedSites.add(site.missionInstanceId());
            }
        }

        for (UUID siteId : completedSites) {
            triggerSiteDecay(level, siteId, null);
        }
    }

    private static void triggerSiteDecay(ServerLevel level, UUID siteId, ServerPlayer player) {
        PendingSalvageSite site = PENDING_SITES.remove(siteId);
        if (site == null) return;

        MissionSiteSnapshotManager.restoreAndRemove(level, site.missionInstanceId(), site.trackedStructureBlocks(), site.anchor());

        String msg = String.format("§a§l[WARFRONT] §eAll loot collected in Sub-region (%d, %d)! Temporary fortifications decaying...",
                site.subX(), site.subZ());
        if (player != null) {
            player.sendSystemMessage(Component.literal(msg));
        }

        Warfront.LOGGER.info("All salvage loot collected for site {}; triggered gradual demolition.", siteId);
    }

    /**
     * Serializes all pending salvage sites for world save persistence.
     */
    public static ListTag save() {
        ListTag list = new ListTag();
        for (PendingSalvageSite site : PENDING_SITES.values()) {
            list.add(site.toTag());
        }
        return list;
    }

    /**
     * Loads pending salvage sites on world load.
     */
    public static void load(ListTag tag) {
        PENDING_SITES.clear();
        if (tag == null) return;
        for (int i = 0; i < tag.size(); i++) {
            PendingSalvageSite site = PendingSalvageSite.fromTag(tag.getCompound(i));
            if (site != null) {
                PENDING_SITES.put(site.missionInstanceId(), site);
            }
        }
        Warfront.LOGGER.info("Loaded {} pending post-war salvage site(s).", PENDING_SITES.size());
    }
}
