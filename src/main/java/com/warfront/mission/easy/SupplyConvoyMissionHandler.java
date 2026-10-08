package com.warfront.mission.easy;

import com.warfront.ai.goal.ConvoyEscortGoal;
import com.warfront.entity.ModEntities;
import com.warfront.entity.SupplyWagonCartEntity;
import com.warfront.entity.SupplyWagonExtensionEntity;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.mission.MissionObjectiveHandler;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.spawn.EnemyEncounterSpawner;
import com.warfront.spawn.ExplorationSpawnManager;
import com.warfront.spawn.MissionEntityTracker;
import com.warfront.spawn.SubregionPatrolManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Objective handler for {@link com.warfront.mission.MissionType#SUPPLY_CONVOY}.
 *
 * Spawns a moving supply convoy:
 *   - Front Wagon: {@link SupplyWagonCartEntity} pulled by 2 harnessed horses, continuously
 *     navigating through 3 subregion border waypoints (with 10-second pauses upon arrival).
 *   - Rear Wagon: {@link SupplyWagonExtensionEntity} trailer carrying randomized loot in a 27-slot chest.
 *   - Escort Squads: 6 armed guards (2 Warriors and 4 Marksmen, 3 per side) marching alongside
 *     with dynamic leashes (8 blocks for marksmen, 24 blocks for warriors) and anti-glitch full-retreat AI.
 *
 * Mission requires destroying BOTH wagons (target progress: 2).
 */
public final class SupplyConvoyMissionHandler implements MissionObjectiveHandler {

    private static final SupplyConvoyMissionHandler INSTANCE = new SupplyConvoyMissionHandler();

    private SupplyConvoyMissionHandler() {
    }

    public static SupplyConvoyMissionHandler getInstance() {
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

        // If convoy is already staged, do not stage again
        if (!progress.trackedEntityUuids().isEmpty() || !progress.trackedBlockPositions().isEmpty()) {
            return;
        }

        UUID instanceId = progress.missionInstanceId();

        // 1. Pick starting point on a subregion border
        BlockPos startPos = SubregionPatrolManager.pickRandomBorderPoint(level, regionX, regionZ, subX, subZ);
        if (startPos == null) {
            int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
            int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
            int sy = EnemyEncounterSpawner.findDryLandSurfaceY(level, subMinX + 16, subMinZ + 16);
            if (sy == Integer.MIN_VALUE) sy = level.getSeaLevel() + 2;
            startPos = new BlockPos(subMinX + 16, sy, subMinZ + 16);
        }

        // Calculate initial facing towards subregion center
        int subCenterX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS / 2;
        int subCenterZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS / 2;
        float startYaw = (float) (Mth.atan2(subCenterZ - startPos.getZ(), subCenterX - startPos.getX()) * Mth.RAD_TO_DEG) - 90.0F;

        // 2. Spawn Front Cart Wagon
        SupplyWagonCartEntity cart = ModEntities.SUPPLY_WAGON_CART.get().create(level);
        if (cart == null) {
            return;
        }

        cart.moveTo(startPos.getX() + 0.5D, startPos.getY(), startPos.getZ() + 0.5D, startYaw, 0.0F);
        cart.setYRot(startYaw);
        cart.setYHeadRot(startYaw);
        cart.yBodyRot = startYaw;
        cart.setConvoySubregion(regionX, regionZ, subX, subZ);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(cart, level, level.getCurrentDifficultyAt(startPos), MobSpawnType.EVENT, null);
        if (!level.addFreshEntity(cart)) {
            return;
        }
        setupMob(cart, instanceId, regionX, regionZ, subX, subZ, progress);

        float yawRad = startYaw * Mth.DEG_TO_RAD;
        double fwdX = -Mth.sin(yawRad);
        double fwdZ = Mth.cos(yawRad);
        double rightX = Mth.cos(yawRad);
        double rightZ = Mth.sin(yawRad);

        // 3. Spawn 2 Harness Horses in front (no leashes, goals cleared to prevent violent spinning)
        Horse leftHorse = EntityType.HORSE.create(level);
        if (leftHorse != null) {
            double lx = cart.getX() + fwdX * 3.6D + rightX * -0.95D;
            double lz = cart.getZ() + fwdZ * 3.6D + rightZ * -0.95D;
            int ly = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.round(lx), (int) Math.round(lz));
            leftHorse.moveTo(lx, ly, lz, startYaw, 0.0F);
            leftHorse.setTamed(true);
            leftHorse.equipSaddle(new ItemStack(Items.SADDLE), null);
            leftHorse.goalSelector.getAvailableGoals().clear();
            leftHorse.targetSelector.getAvailableGoals().clear();
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(leftHorse, level, level.getCurrentDifficultyAt(new BlockPos((int) lx, ly, (int) lz)), MobSpawnType.EVENT, null);
            if (level.addFreshEntity(leftHorse)) {
                setupMob(leftHorse, instanceId, regionX, regionZ, subX, subZ, progress);
            }
        }

        Horse rightHorse = EntityType.HORSE.create(level);
        if (rightHorse != null) {
            double rx = cart.getX() + fwdX * 3.6D + rightX * 0.95D;
            double rz = cart.getZ() + fwdZ * 3.6D + rightZ * 0.95D;
            int ry = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.round(rx), (int) Math.round(rz));
            rightHorse.moveTo(rx, ry, rz, startYaw, 0.0F);
            rightHorse.setTamed(true);
            rightHorse.equipSaddle(new ItemStack(Items.SADDLE), null);
            rightHorse.goalSelector.getAvailableGoals().clear();
            rightHorse.targetSelector.getAvailableGoals().clear();
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(rightHorse, level, level.getCurrentDifficultyAt(new BlockPos((int) rx, ry, (int) rz)), MobSpawnType.EVENT, null);
            if (level.addFreshEntity(rightHorse)) {
                setupMob(rightHorse, instanceId, regionX, regionZ, subX, subZ, progress);
            }
        }

        cart.setHorses(leftHorse != null ? leftHorse.getUUID() : null, rightHorse != null ? rightHorse.getUUID() : null);

        // 4. Spawn Rear Extension Wagon (Trailer - 4.4 blocks behind cart)
        double extX = cart.getX() - fwdX * 4.4D;
        double extZ = cart.getZ() - fwdZ * 4.4D;
        int extY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.round(extX), (int) Math.round(extZ));
        SupplyWagonExtensionEntity extension = ModEntities.SUPPLY_WAGON_EXTENSION.get().create(level);
        if (extension != null) {
            extension.moveTo(extX, extY, extZ, startYaw, 0.0F);
            extension.setYRot(startYaw);
            extension.setYHeadRot(startYaw);
            extension.yBodyRot = startYaw;
            extension.setCartWagonUuid(cart.getUUID());
            extension.initRandomLoot();
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(extension, level, level.getCurrentDifficultyAt(new BlockPos((int) extX, extY, (int) extZ)), MobSpawnType.EVENT, null);
            if (level.addFreshEntity(extension)) {
                cart.setExtensionWagon(extension.getUUID());
                setupMob(extension, instanceId, regionX, regionZ, subX, subZ, progress);
            }
        }

        // 5. Spawn 2 Escort Squads (6 mobs each, 12 total):
        // Squad 1: 2 Warriors, 4 Marksmen guarding Front Cart Wagon
        spawnEscortSquad(level, cart, startYaw, fwdX, fwdZ, rightX, rightZ, instanceId, regionX, regionZ, subX, subZ, progress);

        // Squad 2: 2 Warriors, 4 Marksmen guarding Rear Extension Wagon
        if (extension != null) {
            spawnEscortSquad(level, extension, startYaw, fwdX, fwdZ, rightX, rightZ, instanceId, regionX, regionZ, subX, subZ, progress);
        }

        // Set target progress to 2 (Both wagons must be destroyed to claim victory)
        progress.setTargetProgress(2);
        RegionData regions = RegionData.get(level);
        if (regions != null) {
            regions.setDirty();
        }
        ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
    }

    private void spawnEscortSquad(
            ServerLevel level,
            Mob assignedWagon,
            float startYaw,
            double fwdX, double fwdZ,
            double rightX, double rightZ,
            UUID instanceId,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        if (assignedWagon == null) return;

        double[][] escortSlots = new double[][]{
                // Left flank: 1 Warrior (front) + 2 Marksmen (middle & rear)
                {-2.6D,  1.5D, 1.0D, 24.0D},
                {-2.6D,  0.0D, 0.0D,  8.0D},
                {-2.6D, -1.5D, 0.0D,  8.0D},

                // Right flank: 1 Warrior (front) + 2 Marksmen (middle & rear)
                { 2.6D,  1.5D, 1.0D, 24.0D},
                { 2.6D,  0.0D, 0.0D,  8.0D},
                { 2.6D, -1.5D, 0.0D,  8.0D}
        };

        for (double[] slot : escortSlots) {
            double sideOffset = slot[0];
            double forwardOffset = slot[1];
            boolean isWarrior = slot[2] == 1.0D;
            double maxLeash = slot[3];

            Mob escort = isWarrior
                    ? ModEntities.PILLAGER_WARRIOR.get().create(level)
                    : ModEntities.PILLAGER_MARKSMAN.get().create(level);

            if (escort != null) {
                double ex = assignedWagon.getX() + fwdX * forwardOffset + rightX * sideOffset;
                double ez = assignedWagon.getZ() + fwdZ * forwardOffset + rightZ * sideOffset;
                int ey = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.round(ex), (int) Math.round(ez));
                escort.moveTo(ex, ey, ez, startYaw, 0.0F);
                escort.setYRot(startYaw);
                escort.setYHeadRot(startYaw);

                escort.goalSelector.addGoal(1, new ConvoyEscortGoal(escort, assignedWagon, sideOffset, forwardOffset, maxLeash));

                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(escort, level, level.getCurrentDifficultyAt(new BlockPos((int) ex, ey, (int) ez)), MobSpawnType.EVENT, null);
                if (level.addFreshEntity(escort)) {
                    setupMob(escort, instanceId, regionX, regionZ, subX, subZ, progress);
                }
            }
        }
    }

    public void onConvoyEscaped(ServerLevel level, SupplyWagonCartEntity cart) {
        if (level == null || cart == null) return;
        int rx = cart.getOriginRegionX();
        int rz = cart.getOriginRegionZ();
        int sx = cart.getOriginSubX();
        int sz = cart.getOriginSubZ();
        ActiveCampaignMissionManager.ActiveSubRegionProgress progress =
                ActiveCampaignMissionManager.getActiveProgress(rx, rz, sx, sz);
        if (progress != null && !progress.isCompleted()) {
            ActiveCampaignMissionManager.failMission(level, rx, rz, sx, sz, progress, "Supply convoy escaped across the border!");
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(cart) < 16384.0D) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Mission Failed] The Supply Convoy escaped across the border!"));
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

        if (mob != null && progress.trackedEntityUuids().remove(mob.getUUID())) {
            // Check if one of the 2 convoy wagons was destroyed
            if (mob instanceof SupplyWagonCartEntity || mob instanceof SupplyWagonExtensionEntity) {
                progress.incrementProgress();
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);

                // Both wagons must be destroyed (2 / 2) to complete the mission
                if (progress.currentProgress() >= progress.targetProgress()) {
                    ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
                    return true;
                }
            }

            RegionData regions = RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
            }
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
        if (progress == null || progress.isCompleted()) {
            return false;
        }

        if (progress.trackedBlockPositions().remove(pos)) {
            level.playSound(null, pos, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.2F, 0.8F);
            level.sendParticles(ParticleTypes.EXPLOSION, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 3, 0.3D, 0.3D, 0.3D, 0.05D);

            progress.incrementProgress();
            if (progress.currentProgress() >= progress.targetProgress()) {
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
        // Tracked convoy wagons, horses, and escorts are automatically handled
        // by MissionEntityTracker (staggered terminal despawn with POOF particles)
    }
}
