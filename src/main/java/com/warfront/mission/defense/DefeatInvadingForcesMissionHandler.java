package com.warfront.mission.defense;

import com.warfront.ai.goal.AdvanceToLocationGoal;
import com.warfront.entity.ModEntities;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.mission.MissionObjectiveHandler;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.spawn.EnemyEncounterSpawner;
import com.warfront.spawn.ExplorationSpawnManager;
import com.warfront.spawn.MissionEntityTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Objective handler for {@link com.warfront.mission.MissionType#DEFEAT_INVADING_FORCES}.
 *
 * Repels hostile assault waves advancing inward from the contested border facing
 * the attacking source region.
 *
 * STRICT INVARIANT: ZERO STRUCTURE GENERATION. No blocks placed or templates pasted.
 */
public final class DefeatInvadingForcesMissionHandler implements MissionObjectiveHandler {

    private static final DefeatInvadingForcesMissionHandler INSTANCE = new DefeatInvadingForcesMissionHandler();

    public static class InvasionState {
        int spawnedTotal = 0;
        int currentWave = 0;
        final int totalWaves;
        long lastWaveTick = 0;
        final List<UUID> activeWaveMobs = new ArrayList<>();

        InvasionState(int targetProgress) {
            this.totalWaves = targetProgress >= 15 ? 3 : 2;
        }
    }

    private final ConcurrentHashMap<UUID, InvasionState> states = new ConcurrentHashMap<>();

    private DefeatInvadingForcesMissionHandler() {
    }

    public static DefeatInvadingForcesMissionHandler getInstance() {
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

        UUID instanceId = progress.missionInstanceId();
        InvasionState state = states.computeIfAbsent(instanceId, id -> {
            InvasionState s = new InvasionState(progress.targetProgress());
            if (!progress.trackedEntityUuids().isEmpty()) {
                s.activeWaveMobs.addAll(progress.trackedEntityUuids());
                s.spawnedTotal = progress.trackedEntityUuids().size() + progress.currentProgress();
                s.currentWave = 1;
            }
            return s;
        });

        // Purge dead or invalid UUIDs from active wave
        state.activeWaveMobs.removeIf(uuid -> {
            Entity entity = level.getEntity(uuid);
            return entity == null || !entity.isAlive();
        });

        // Check if all required hostiles have been spawned and eliminated
        if (state.spawnedTotal >= progress.targetProgress() && state.activeWaveMobs.isEmpty()) {
            if (progress.currentProgress() >= progress.targetProgress()) {
                ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
                states.remove(instanceId);
                return;
            }
        }

        // Check if next wave should spawn
        boolean shouldSpawnWave = false;
        long gameTime = level.getGameTime();

        if (state.currentWave == 0) {
            shouldSpawnWave = true;
        } else if (state.spawnedTotal < progress.targetProgress() && state.activeWaveMobs.size() <= 2) {
            if (gameTime - state.lastWaveTick >= 100) { // 5-second pacing cooldown between waves
                shouldSpawnWave = true;
            }
        }

        if (!shouldSpawnWave) {
            return;
        }

        // Subregion boundaries
        int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;
        int centerX = (subMinX + subMaxX) / 2;
        int centerZ = (subMinZ + subMaxZ) / 2;

        // Determine border direction facing attacking source region
        RegionData regions = RegionData.get(level);
        RegionData.SiegeCampaign siege = (regions != null) ? regions.getSiege(regionX, regionZ) : null;
        int dirX = 0;
        int dirZ = 0;

        if (siege != null && siege.sources() != null && !siege.sources().isEmpty()) {
            RegionData.SourcePos src = siege.sources().get(0);
            int dx = src.x() - regionX;
            int dz = src.z() - regionZ;
            if (Math.abs(dx) >= Math.abs(dz)) {
                dirX = dx < 0 ? -1 : 1;
            } else {
                dirZ = dz < 0 ? -1 : 1;
            }
        } else {
            // Fallback: spawn toward the outer perimeter of the region
            dirX = (subX == 0) ? -1 : 1;
        }

        int remainingToSpawn = progress.targetProgress() - state.spawnedTotal;
        int waveSize = Math.min(remainingToSpawn, Math.max(4, (progress.targetProgress() + 1) / state.totalWaves));
        int newlySpawned = 0;

        for (int i = 0; i < waveSize; i++) {
            int spawnX;
            int spawnZ;

            if (dirX != 0) {
                spawnX = (dirX < 0) ? subMinX + 3 : subMaxX - 3;
                spawnZ = subMinZ + 8 + level.getRandom().nextInt(Math.max(1, ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 16));
            } else {
                spawnZ = (dirZ < 0) ? subMinZ + 3 : subMaxZ - 3;
                spawnX = subMinX + 8 + level.getRandom().nextInt(Math.max(1, ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 16));
            }

            int spawnY = EnemyEncounterSpawner.findDryLandSurfaceY(level, spawnX, spawnZ);
            if (spawnY == Integer.MIN_VALUE) {
                // Search nearby dry land
                for (int attempt = 0; attempt < 8; attempt++) {
                    int nx = spawnX + level.getRandom().nextInt(7) - 3;
                    int nz = spawnZ + level.getRandom().nextInt(7) - 3;
                    spawnY = EnemyEncounterSpawner.findDryLandSurfaceY(level, nx, nz);
                    if (spawnY != Integer.MIN_VALUE) {
                        spawnX = nx;
                        spawnZ = nz;
                        break;
                    }
                }
            }
            if (spawnY == Integer.MIN_VALUE) {
                spawnY = player.getBlockY();
            }

            // Spawn entity based on faction
            Mob mob;
            float roll = level.getRandom().nextFloat();
            if (roll < 0.20f && state.currentWave >= 1) {
                mob = ModEntities.PILLAGER_ELITE.get().create(level);
            } else if (roll < 0.55f) {
                mob = ModEntities.PILLAGER_MARKSMAN.get().create(level);
            } else {
                mob = ModEntities.PILLAGER_WARRIOR.get().create(level);
            }

            if (mob == null) continue;

            mob.moveTo(spawnX + 0.5D, spawnY, spawnZ + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(new BlockPos(spawnX, spawnY, spawnZ)), MobSpawnType.EVENT, null);

            if (level.addFreshEntity(mob)) {
                mob.addTag("warfront_mission");
                mob.addTag("warfront_defense_invader");
                mob.getPersistentData().putBoolean("isMissionMob", true);
                mob.getPersistentData().putUUID("missionInstanceId", instanceId);
                mob.getPersistentData().putInt("originRegionX", regionX);
                mob.getPersistentData().putInt("originRegionZ", regionZ);
                mob.getPersistentData().putInt("originSubX", subX);
                mob.getPersistentData().putInt("originSubZ", subZ);
                mob.getPersistentData().putInt("faction", progress.targetFaction().id());
                mob.setPersistenceRequired();
                mob.getPersistentData().putBoolean("preventActiveDespawn", true);

                mob.goalSelector.addGoal(1, new AdvanceToLocationGoal(mob, centerX, centerZ, 1.15D));

                MissionEntityTracker.registerMissionMob(mob, instanceId, regionX, regionZ, subX, subZ, progress.targetFaction());
                progress.trackedEntityUuids().add(mob.getUUID());
                state.activeWaveMobs.add(mob.getUUID());
                newlySpawned++;
            }
        }

        if (newlySpawned > 0) {
            state.spawnedTotal += newlySpawned;
            state.currentWave++;
            state.lastWaveTick = gameTime;

            if (regions != null) {
                regions.setDirty();
            }

            player.displayClientMessage(
                    Component.literal(String.format("§c🚨 Hostile assault wave %d/%d invading from the border!",
                            state.currentWave, state.totalWaves)),
                    true
            );
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.5F, 1.0F);

            ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
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

        UUID mobUuid = (mob != null) ? mob.getUUID() : null;
        UUID instanceId = progress.missionInstanceId();
        InvasionState state = states.get(instanceId);

        boolean tracked = false;
        if (mobUuid != null) {
            if (progress.trackedEntityUuids().remove(mobUuid)) {
                tracked = true;
            }
            if (state != null) {
                state.activeWaveMobs.remove(mobUuid);
            }
        }

        if (tracked || (state != null && state.spawnedTotal > progress.currentProgress())) {
            progress.incrementProgress();

            RegionData regions = RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
            }

            if (progress.currentProgress() >= progress.targetProgress()) {
                ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
                states.remove(instanceId);
            } else {
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
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
        return false;
    }

    @Override
    public void onCleanup(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        if (progress != null) {
            states.remove(progress.missionInstanceId());
        }
    }
}
