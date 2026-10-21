package com.warfront.mission.base;

import com.warfront.ai.goal.MissionPostGuardGoal;
import com.warfront.block.SalvageMachineryBlock;
import com.warfront.entity.ModEntities;
import com.warfront.entity.PillagerCommanderEntity;
import com.warfront.entity.PillagerMarksmanEntity;
import com.warfront.entity.PillagerWarriorEntity;
import com.warfront.entity.ai.goal.OutpostMarksmanPatrolGoal;
import com.warfront.loot.OutpostLootManager;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.mission.MissionObjectiveHandler;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.base.BasePlacementManager;
import com.warfront.region.base.PersistentBaseProtectionManager;
import com.warfront.region.generator.ProceduralRegionGenerator;
import com.warfront.spawn.MissionEntityTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Objective handler for {@link com.warfront.mission.MissionType#OUTPOST_DESTROY_BUILDING}.
 *
 * Tracks the physical outpost fortification generated in the subregion.
 * The mission requires demolishing 50% of the outpost structure blocks (via mining, fire, or explosions).
 * On completion, remaining outpost blocks and garrison mobs gradually disperse with POOF effects.
 */
public final class OutpostDestructionMissionHandler implements MissionObjectiveHandler {

    private static final OutpostDestructionMissionHandler INSTANCE = new OutpostDestructionMissionHandler();

    private OutpostDestructionMissionHandler() {
    }

    public static OutpostDestructionMissionHandler getInstance() {
        return INSTANCE;
    }

    @Override
    public void onPlayerInSubregion(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            ServerPlayer player
    ) {
        if (progress == null || progress.isCompleted()) {
            return;
        }

        if (progress.phaseIndex() == 0) {
            initOutpost(level, regionX, regionZ, subX, subZ, progress);
        }

        // Fire & environmental destruction sweep:
        // Detects blocks destroyed by fire, burn out, lava, or external explosions
        if (progress.phaseIndex() > 0 && !progress.trackedBlockPositions().isEmpty()) {
            java.util.List<BlockPos> destroyedPositions = new java.util.ArrayList<>();
            for (BlockPos pos : progress.trackedBlockPositions()) {
                if (!level.hasChunkAt(pos)) {
                    continue; // Skip unloaded chunks to prevent VOID_AIR false positives
                }
                BlockState bs = level.getBlockState(pos);
                if (bs.isAir() || bs.is(Blocks.FIRE) || bs.is(Blocks.SOUL_FIRE)) {
                    destroyedPositions.add(pos);
                }
            }

            if (!destroyedPositions.isEmpty()) {
                progress.trackedBlockPositions().removeAll(destroyedPositions);
                progress.addProgress(destroyedPositions.size());

                RegionData regions = RegionData.get(level);
                if (regions != null) {
                    regions.setDirty();
                }

                if (progress.currentProgress() >= progress.targetProgress() || progress.trackedBlockPositions().isEmpty()) {
                    disarmCommandDesks(level, progress.missionSiteAnchor());
                    ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
                    return;
                } else {
                    ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
                }
            }
        }
    }

    private void initOutpost(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        // 1. Ensure the base structure is physically generated in the world without stacking
        RegionData regions = RegionData.get(level);
        if (regions != null && !regions.isBasePlaced(regionX, regionZ)) {
            BasePlacementManager.tryPlaceBase(level, regionX, regionZ, false);
        }

        // 2. Resolve anchor position of the existing outpost
        RegionData.RegionState state = (regions != null) ? regions.getSavedRegionState(regionX, regionZ) : null;
        BlockPos anchor = (state != null) ? state.baseAnchor() : null;

        if (anchor == null) {
            anchor = ProceduralRegionGenerator.getInstance()
                    .findPhysicalBaseAnchor(level, level.getSeed(), regionX, regionZ, BaseType.OUTPOST)
                    .orElse(null);
        }

        if (anchor == null) {
            int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * 64;
            int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * 64;
            int cx = subMinX + 32;
            int cz = subMinZ + 32;
            int cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
            anchor = new BlockPos(cx, cy, cz);
        }

        progress.setMissionSiteAnchor(anchor);
        progress.setTemporaryStructuresBuilt(true);
        UUID instanceId = progress.missionInstanceId();

        // 3. Scan the outpost bounding box and register all structure blocks
        // Outpost size is 29x25x29 centered on anchor.
        progress.trackedBlockPositions().clear();

        RegionData.RegionState regState = (state != null) ? state : new RegionData.RegionState(
                progress.targetFaction(), 100.0F, 100.0F, BaseType.OUTPOST, 0L, anchor, true);

        // Instantly repair any missing/damaged structure blocks before combat
        com.warfront.region.base.PersistentBaseProtectionManager.instantRepairBase(level, regionX, regionZ, anchor, regState);

        // Populate curated mission loot inside outpost chests
        OutpostLootManager.populateOutpostChests(level, anchor);

        java.util.Map<BlockPos, BlockState> blueprint = com.warfront.region.base.PersistentBaseProtectionManager
                .getOrComputePristineBlocks(level, regionX, regionZ, regState);

        int foundationMinY = anchor.getY() - 1;
        int aboveGroundCount = 0;

        if (blueprint != null && !blueprint.isEmpty()) {
            for (BlockPos pos : blueprint.keySet()) {
                if (pos.getY() >= foundationMinY && !level.getBlockState(pos).isAir()) {
                    progress.trackedBlockPositions().add(pos.immutable());
                    if (pos.getY() >= anchor.getY()) {
                        aboveGroundCount++;
                    }
                }
            }
        } else {
            int halfX = 14;
            int halfZ = 14;
            int minY = foundationMinY;
            int maxY = Math.min(level.getMaxBuildHeight(), anchor.getY() + 25);

            for (int x = anchor.getX() - halfX; x <= anchor.getX() + halfX; x++) {
                for (int z = anchor.getZ() - halfZ; z <= anchor.getZ() + halfZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState bs = level.getBlockState(pos);

                        if (!bs.isAir() && !bs.is(Blocks.BEDROCK)
                                && !bs.is(Blocks.GRASS_BLOCK) && !bs.is(Blocks.DIRT)
                                && !bs.is(Blocks.STONE) && !bs.is(Blocks.DEEPSLATE)
                                && bs.getFluidState().isEmpty()) {
                            progress.trackedBlockPositions().add(pos.immutable());
                            if (pos.getY() >= anchor.getY()) {
                                aboveGroundCount++;
                            }
                        }
                    }
                }
            }
        }

        // Ensure all outpost loot chests are registered in tracked blocks for post-war salvage
        for (BlockPos chestPos : OutpostLootManager.getAllOutpostChestPositions(anchor)) {
            progress.trackedBlockPositions().add(chestPos.immutable());
        }

        if (aboveGroundCount == 0) {
            aboveGroundCount = Math.max(30, progress.trackedBlockPositions().size());
        }

        int target = (int) Math.max(1, Math.ceil(aboveGroundCount * 0.50D));
        progress.setTargetProgress(target);
        progress.setCurrentProgress(0);
        progress.setPhaseIndex(1);

        // 4. Prepare outpost garrison defenders (missing-only instant respawn, living mobs healed & enlisted)
        com.warfront.region.base.garrison.BaseGarrisonManager.prepareAndEnlistMissionGarrison(
                level, regionX, regionZ, subX, subZ, anchor, instanceId, progress);

        if (regions != null) {
            regions.setDirty();
        }
        ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
    }

    private void disarmCommandDesks(ServerLevel level, BlockPos anchor) {
        if (level == null || anchor == null) return;
        BlockPos origin = OutpostLootManager.getStructureOrigin(anchor);
        for (int dx = 12; dx <= 13; dx++) {
            for (int dz = 13; dz <= 15; dz++) {
                BlockPos deskPos = origin.offset(dx, 5, dz);
                SalvageMachineryBlock.disarm(level, deskPos);
            }
        }
    }

    @Override
    public boolean onEntityKilled(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            Faction mobFaction,
            String mobRoleName,
            Mob mob
    ) {
        if (progress == null || progress.isCompleted()) {
            return false;
        }
        if (mob != null) {
            progress.trackedEntityUuids().remove(mob.getUUID());
        }
        return false;
    }

    @Override
    public boolean onBlockBroken(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            BlockPos pos,
            BlockState state,
            ServerPlayer player
    ) {
        if (progress == null || progress.isCompleted()) {
            return false;
        }

        if (progress.trackedBlockPositions().remove(pos)) {
            level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0F, 0.9F);
            level.sendParticles(ParticleTypes.CRIT, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 3, 0.2D, 0.2D, 0.2D, 0.1D);

            progress.incrementProgress();
            RegionData regions = RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
            }

            if (progress.currentProgress() >= progress.targetProgress() || progress.trackedBlockPositions().isEmpty()) {
                disarmCommandDesks(level, progress.missionSiteAnchor());
                ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
            } else {
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
            }
            return true;
        }
        return false;
    }

    @Override
    public void onCleanup(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        BlockPos anchor = progress.missionSiteAnchor();

        if (progress.isCompleted()) {
            if (anchor != null) {
                disarmCommandDesks(level, anchor);
                // Retain all remaining unopened chests in tracked blocks for PendingSalvageManager
                for (BlockPos chestPos : OutpostLootManager.getAllOutpostChestPositions(anchor)) {
                    if (level.hasChunkAt(chestPos) && level.getBlockState(chestPos).is(Blocks.CHEST)) {
                        progress.trackedBlockPositions().add(chestPos.immutable());
                    }
                }
            }

            // Immediately disperse all surviving enlisted defenders with POOF
            for (UUID uuid : progress.trackedEntityUuids()) {
                net.minecraft.world.entity.Entity e = level.getEntity(uuid);
                if (e instanceof Mob mob && mob.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, mob.getX(), mob.getY() + 0.5D, mob.getZ(), 12, 0.3D, 0.5D, 0.3D, 0.05D);
                    level.playSound(null, mob.blockPosition(), SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 0.7F, 1.2F);
                    mob.discard();
                }
                MissionEntityTracker.unregisterMissionMob(uuid);
            }

            // Also clear living garrison slots in BaseGarrisonData for this outpost
            com.warfront.region.base.garrison.BaseGarrisonData gData = com.warfront.region.base.garrison.BaseGarrisonData.get(level);
            if (gData != null) {
                com.warfront.region.base.garrison.BaseGarrisonState gState = gData.getGarrison(regionX, regionZ);
                if (gState != null) {
                    for (com.warfront.region.base.garrison.GarrisonSlot slot : gState.getSlots()) {
                        UUID gUuid = slot.getEntityUuid();
                        if (gUuid != null) {
                            net.minecraft.world.entity.Entity gEntity = level.getEntity(gUuid);
                            if (gEntity instanceof Mob gMob && gMob.isAlive()) {
                                level.sendParticles(ParticleTypes.POOF, gMob.getX(), gMob.getY() + 0.5D, gMob.getZ(), 12, 0.3D, 0.5D, 0.3D, 0.05D);
                                gMob.discard();
                            }
                            slot.setEntityUuid(null);
                        }
                    }
                    gData.setDirty();
                }
            }
        } else {
            // Mission ended without win -> clear loot from all chests with zero drops and reset tracked blocks
            progress.trackedBlockPositions().clear();
            if (anchor != null) {
                OutpostLootManager.clearOutpostChests(level, anchor);
            }
        }
    }
}
