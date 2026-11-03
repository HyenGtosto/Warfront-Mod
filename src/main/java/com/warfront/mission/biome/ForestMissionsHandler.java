package com.warfront.mission.biome;

import com.warfront.block.WarfrontBlocks;
import com.warfront.entity.ModEntities;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.mission.MissionObjectiveHandler;
import com.warfront.mission.MissionType;
import com.warfront.mission.site.MissionSiteAnchorResolver;
import com.warfront.mission.site.MissionSiteSnapshot;
import com.warfront.mission.site.MissionSiteSnapshotManager;
import com.warfront.mission.site.TemporaryStructureBuilder;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.spawn.MissionEntityTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Objective handler for Dense Woodlands (Forest & Taiga) biome missions:
 * - FOREST_LOGGING_DEPOT (Easy)
 * - FOREST_HUNTING_STASH (Easy)
 * - FOREST_CANOPY_BLINDS (Hard)
 * - FOREST_COMMAND_CAMP (Hard)
 */
public final class ForestMissionsHandler implements MissionObjectiveHandler {

    private static final ForestMissionsHandler INSTANCE = new ForestMissionsHandler();

    private ForestMissionsHandler() {}

    public static ForestMissionsHandler getInstance() {
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
        if (progress == null || progress.isCompleted()) return;

        UUID instanceId = progress.missionInstanceId();
        if (progress.temporaryStructuresBuilt()) return;

        long siteSeed = level.getSeed() ^ ((long) regionX * 187236128L) ^ ((long) regionZ * 492817231L) ^ ((long) subX * 8191L) ^ (subZ * 381721L);
        BlockPos anchor = MissionSiteAnchorResolver.resolveDryLandAnchor(level, regionX, regionZ, subX, subZ, siteSeed);
        if (anchor == null) return;

        progress.setMissionSiteAnchor(anchor);
        MissionSiteSnapshot snapshot = MissionSiteSnapshotManager.getOrCreate(instanceId, regionX, regionZ, subX, subZ);
        TemporaryStructureBuilder.GenericStructureResult result;

        MissionType type = progress.missionType();
        if (type == MissionType.FOREST_LOGGING_DEPOT) {
            result = TemporaryStructureBuilder.buildForestLoggingDepot(level, anchor, snapshot);
            spawnSquad(level, instanceId, regionX, regionZ, subX, subZ, progress, anchor, 2, 5, 0, 0, 0);
            progress.setTargetProgress(result.targetPositions().size());
        } else if (type == MissionType.FOREST_HUNTING_STASH) {
            result = TemporaryStructureBuilder.buildForestHuntingStash(level, anchor, snapshot);
            spawnSquad(level, instanceId, regionX, regionZ, subX, subZ, progress, anchor, 3, 3, 0, 0, 0);
            progress.setTargetProgress(result.targetPositions().size());
        } else if (type == MissionType.FOREST_CANOPY_BLINDS) {
            result = TemporaryStructureBuilder.buildForestCanopyBlinds(level, anchor, snapshot);
            spawnSquad(level, instanceId, regionX, regionZ, subX, subZ, progress, anchor, 3, 3, 0, 0, 1);
            progress.setTargetProgress(1);
        } else { // FOREST_COMMAND_CAMP
            result = TemporaryStructureBuilder.buildForestCommandCamp(level, anchor, snapshot);
            spawnSquad(level, instanceId, regionX, regionZ, subX, subZ, progress, anchor, 2, 4, 1, 1, 1);
            progress.setTargetProgress(1);
        }

        progress.trackedBlockPositions().addAll(result.targetPositions());
        progress.setTemporaryStructuresBuilt(true);
        RegionData regions = RegionData.get(level);
        if (regions != null) regions.setDirty();
        ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
    }

    private void spawnSquad(ServerLevel level, UUID instanceId, int rx, int rz, int sx, int sz,
                            ActiveCampaignMissionManager.ActiveSubRegionProgress progress, BlockPos anchor,
                            int marksmen, int warriors, int ironclads, int commanders, int elites) {
        for (int i = 0; i < marksmen; i++) spawnMob(level, ModEntities.PILLAGER_MARKSMAN.get().create(level), instanceId, rx, rz, sx, sz, progress, anchor);
        for (int i = 0; i < warriors; i++) spawnMob(level, ModEntities.PILLAGER_WARRIOR.get().create(level), instanceId, rx, rz, sx, sz, progress, anchor);
        for (int i = 0; i < ironclads; i++) spawnMob(level, ModEntities.PILLAGER_IRONCLAD.get().create(level), instanceId, rx, rz, sx, sz, progress, anchor);
        for (int i = 0; i < commanders; i++) spawnMob(level, ModEntities.PILLAGER_COMMANDER.get().create(level), instanceId, rx, rz, sx, sz, progress, anchor);
        for (int i = 0; i < elites; i++) spawnMob(level, ModEntities.PILLAGER_ELITE.get().create(level), instanceId, rx, rz, sx, sz, progress, anchor);
    }

    private void spawnMob(ServerLevel level, Mob mob, UUID instanceId, int rx, int rz, int sx, int sz,
                          ActiveCampaignMissionManager.ActiveSubRegionProgress progress, BlockPos anchor) {
        if (mob == null) return;
        mob.moveTo(anchor.getX() + 0.5D + (level.random.nextDouble() - 0.5D) * 4.0D,
                anchor.getY() + 1.0D,
                anchor.getZ() + 0.5D + (level.random.nextDouble() - 0.5D) * 4.0D,
                level.random.nextFloat() * 360.0F, 0.0F);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(anchor), MobSpawnType.EVENT, null);
        if (level.addFreshEntity(mob)) {
            mob.addTag("warfront_mission");
            mob.getPersistentData().putBoolean("isMissionMob", true);
            mob.getPersistentData().putUUID("missionInstanceId", instanceId);
            MissionEntityTracker.registerMissionMob(mob, instanceId, rx, rz, sx, sz, progress.targetFaction());
            progress.trackedEntityUuids().add(mob.getUUID());
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
        if (progress == null || progress.isCompleted()) return false;
        if (mob != null && progress.trackedEntityUuids().remove(mob.getUUID())) {
            if (progress.missionType() == MissionType.FOREST_COMMAND_CAMP) {
                if (mob.getType() == ModEntities.PILLAGER_COMMANDER.get() || progress.trackedEntityUuids().isEmpty()) {
                    dropRewards(level, progress.missionSiteAnchor(), progress.missionType());
                    ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
                    return true;
                }
            }
            RegionData regions = RegionData.get(level);
            if (regions != null) regions.setDirty();
            return true;
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
        if (progress == null || progress.isCompleted()) return false;

        if (progress.trackedBlockPositions().remove(pos)) {
            level.playSound(null, pos, SoundEvents.ANVIL_DESTROY, SoundSource.BLOCKS, 1.2F, 1.1F);
            level.sendParticles(ParticleTypes.EXPLOSION, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 3, 0.2D, 0.2D, 0.2D, 0.05D);

            progress.incrementProgress();
            RegionData regions = RegionData.get(level);
            if (regions != null) regions.setDirty();

            if (progress.currentProgress() >= progress.targetProgress() || progress.trackedBlockPositions().isEmpty()) {
                dropRewards(level, progress.missionSiteAnchor(), progress.missionType());
                ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
            } else {
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
            }
            return true;
        }
        return false;
    }

    private void dropRewards(ServerLevel level, BlockPos anchor, MissionType type) {
        if (anchor == null) return;
        double x = anchor.getX() + 0.5D;
        double y = anchor.getY() + 1.2D;
        double z = anchor.getZ() + 0.5D;

        if (type == MissionType.FOREST_LOGGING_DEPOT) {
            level.addFreshEntity(new ItemEntity(level, x, y, z, new ItemStack(WarfrontBlocks.CURED_SIEGE_TIMBER.get(), 4)));
            level.addFreshEntity(new ItemEntity(level, x, y, z, new ItemStack(Items.IRON_AXE)));
            level.addFreshEntity(new ItemEntity(level, x, y, z, new ItemStack(Items.EMERALD, 4)));
        } else if (type == MissionType.FOREST_HUNTING_STASH) {
            level.addFreshEntity(new ItemEntity(level, x, y, z, new ItemStack(WarfrontBlocks.GIANT_BOLT_BUNDLE.get(), 2)));
            level.addFreshEntity(new ItemEntity(level, x, y, z, new ItemStack(Items.CROSSBOW)));
            level.addFreshEntity(new ItemEntity(level, x, y, z, new ItemStack(Items.ARROW, 24)));
        } else if (type == MissionType.FOREST_CANOPY_BLINDS) {
            level.addFreshEntity(new ItemEntity(level, x, y, z, new ItemStack(WarfrontBlocks.CURED_SIEGE_TIMBER.get(), 8)));
            level.addFreshEntity(new ItemEntity(level, x, y, z, new ItemStack(WarfrontBlocks.GIANT_BOLT_BUNDLE.get(), 4)));
            level.addFreshEntity(new ItemEntity(level, x, y, z, new ItemStack(WarfrontBlocks.REINFORCED_REDOUBT_PLATE.get())));
        } else if (type == MissionType.FOREST_COMMAND_CAMP) {
            level.addFreshEntity(new ItemEntity(level, x, y, z, new ItemStack(WarfrontBlocks.COMMANDER_BATTLE_STANDARD.get())));
            level.addFreshEntity(new ItemEntity(level, x, y, z, new ItemStack(WarfrontBlocks.CURED_SIEGE_TIMBER.get(), 6)));
        }
    }

    @Override
    public void onCleanup(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        for (UUID uuid : progress.trackedEntityUuids()) {
            Entity ent = level.getEntity(uuid);
            if (ent != null && ent.isAlive()) ent.discard();
            MissionEntityTracker.unregisterMissionMob(uuid);
        }
        progress.trackedEntityUuids().clear();
    }
}
