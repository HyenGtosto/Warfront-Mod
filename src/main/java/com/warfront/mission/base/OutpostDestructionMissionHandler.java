package com.warfront.mission.base;

import com.warfront.entity.ModEntities;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.mission.MissionObjectiveHandler;
import com.warfront.mission.site.MissionSiteSnapshotManager;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.base.BasePlacementManager;
import com.warfront.region.generator.ProceduralRegionGenerator;
import com.warfront.spawn.MissionEntityTracker;
import net.minecraft.core.BlockPos;
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
        UUID instanceId = progress.missionInstanceId();

        // 3. Scan the outpost bounding box and register all structure blocks
        // Outpost typical size is 27x23x27 centered on anchor.
        // CAUTION: Foundation is strictly excluded from the decay/destruction goal (y >= anchor.getY())
        progress.trackedBlockPositions().clear();

        RegionData.RegionState regState = (state != null) ? state : new RegionData.RegionState(
                progress.targetFaction(), 100.0F, 100.0F, BaseType.OUTPOST, 0L, anchor, true);
        java.util.Map<BlockPos, BlockState> blueprint = com.warfront.region.base.PersistentBaseProtectionManager
                .getOrComputePristineBlocks(level, regionX, regionZ, regState);

        if (blueprint != null && !blueprint.isEmpty()) {
            for (BlockPos pos : blueprint.keySet()) {
                if (pos.getY() >= anchor.getY() && !level.getBlockState(pos).isAir()) {
                    progress.trackedBlockPositions().add(pos.immutable());
                }
            }
        } else {
            int halfX = 14;
            int halfZ = 14;
            int minY = anchor.getY(); // Strictly at or above ground level; foundation columns are excluded
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
                        }
                    }
                }
            }
        }

        int totalBlocks = progress.trackedBlockPositions().size();
        if (totalBlocks == 0) {
            // Fallback scan: track non-air above ground level
            for (int x = anchor.getX() - 10; x <= anchor.getX() + 10; x++) {
                for (int z = anchor.getZ() - 10; z <= anchor.getZ() + 10; z++) {
                    for (int y = anchor.getY(); y <= anchor.getY() + 15; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            progress.trackedBlockPositions().add(pos.immutable());
                        }
                    }
                }
            }
            totalBlocks = progress.trackedBlockPositions().size();
            if (totalBlocks == 0) totalBlocks = 60;
        }

        int target = (int) Math.max(1, Math.ceil(totalBlocks * 0.50D));
        progress.setTargetProgress(target);
        progress.setCurrentProgress(0);
        progress.setPhaseIndex(1);

        // 4. Spawn outpost garrison defenders
        spawnGarrison(level, anchor, instanceId, regionX, regionZ, subX, subZ, progress);

        if (regions != null) {
            regions.setDirty();
        }
        ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
    }

    private void spawnGarrison(
            ServerLevel level,
            BlockPos anchor,
            UUID instanceId,
            int rx, int rz, int sx, int sz,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        Faction faction = progress.targetFaction();

        if (faction == Faction.ZOMBIE_HORDE) {
            // Zombie garrison: 4 Zombies around perimeter
            int[][] offsets = {{-5, -5}, {5, -5}, {-5, 5}, {5, 5}};
            for (int[] off : offsets) {
                Mob zombie = EntityType.ZOMBIE.create(level);
                if (zombie != null) {
                    int gx = anchor.getX() + off[0];
                    int gz = anchor.getZ() + off[1];
                    int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz);
                    zombie.moveTo(gx + 0.5D, gy, gz + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                    net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(zombie, level, level.getCurrentDifficultyAt(new BlockPos(gx, gy, gz)), MobSpawnType.EVENT, null);
                    if (level.addFreshEntity(zombie)) {
                        setupMob(zombie, instanceId, rx, rz, sx, sz, progress);
                    }
                }
            }
        } else {
            // Pillager garrison: 1 Elevated Marksman + 4 Ground Perimeter Warriors
            Mob sniper = ModEntities.PILLAGER_MARKSMAN.get().create(level);
            if (sniper != null) {
                sniper.moveTo(anchor.getX() + 0.5D, anchor.getY() + 14.0D, anchor.getZ() + 0.5D, 0.0F, 0.0F);
                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(sniper, level, level.getCurrentDifficultyAt(anchor), MobSpawnType.EVENT, null);
                if (level.addFreshEntity(sniper)) {
                    setupMob(sniper, instanceId, rx, rz, sx, sz, progress);
                }
            }

            int[][] guardOffsets = {{-6, -6}, {6, -6}, {-6, 6}, {6, 6}};
            for (int[] off : guardOffsets) {
                Mob guard = ModEntities.PILLAGER_WARRIOR.get().create(level);
                if (guard != null) {
                    int gx = anchor.getX() + off[0];
                    int gz = anchor.getZ() + off[1];
                    int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz);
                    guard.moveTo(gx + 0.5D, gy, gz + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                    net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(guard, level, level.getCurrentDifficultyAt(new BlockPos(gx, gy, gz)), MobSpawnType.EVENT, null);
                    if (level.addFreshEntity(guard)) {
                        setupMob(guard, instanceId, rx, rz, sx, sz, progress);
                    }
                }
            }
        }
    }

    private void setupMob(Mob mob, UUID instanceId, int rx, int rz, int sx, int sz, ActiveCampaignMissionManager.ActiveSubRegionProgress progress) {
        mob.addTag("warfront_mission");
        mob.getPersistentData().putBoolean("isMissionMob", true);
        mob.getPersistentData().putUUID("missionInstanceId", instanceId);
        mob.getPersistentData().putInt("originRegionX", rx);
        mob.getPersistentData().putInt("originRegionZ", rz);
        mob.getPersistentData().putInt("originSubX", sx);
        mob.getPersistentData().putInt("originSubZ", sz);
        mob.getPersistentData().putInt("faction", progress.targetFaction().id());

        MissionEntityTracker.registerMissionMob(mob, instanceId, rx, rz, sx, sz, progress.targetFaction());
        progress.trackedEntityUuids().add(mob.getUUID());
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
        // Persistent base structures are NOT demolished on mission cleanup!
        // The remaining damaged fortress stays in the world and repairs itself over time when out of war.
        progress.trackedBlockPositions().clear();

        // Remaining defenders are automatically handled by MissionEntityTracker with gradual POOF dispersal
    }
}
