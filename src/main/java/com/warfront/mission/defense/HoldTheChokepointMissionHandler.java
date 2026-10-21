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
 * Objective handler for {@link com.warfront.mission.MissionType#HOLD_THE_CHOKEPOINT}.
 *
 * Improvised hold-ground defense where players defend a subregion perimeter using
 * natural terrain and their own player-built barricades.
 *
 * STRICT INVARIANT: ZERO STRUCTURE GENERATION. No blocks placed or templates pasted.
 */
public final class HoldTheChokepointMissionHandler implements MissionObjectiveHandler {

    private static final HoldTheChokepointMissionHandler INSTANCE = new HoldTheChokepointMissionHandler();

    public static class ChokepointData {
        final BlockPos perimeterCenter;
        final double perimeterRadiusSq = 18.0D * 18.0D;
        int defenseIntegrity = 100;
        int currentWave = 0;
        final int totalWaves = 3;
        final List<UUID> activeWaveMobs = new ArrayList<>();
        long lastDecayTick = 0;
        long lastWaveSpawnTick = 0;

        ChokepointData(BlockPos perimeterCenter) {
            this.perimeterCenter = perimeterCenter;
        }
    }

    private final ConcurrentHashMap<UUID, ChokepointData> dataMap = new ConcurrentHashMap<>();

    private HoldTheChokepointMissionHandler() {
    }

    public static HoldTheChokepointMissionHandler getInstance() {
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

        // Subregion boundaries & perimeter center
        int subMinX = regionX * RegionData.REGION_SIZE_BLOCKS + subX * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxX = subMinX + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;
        int subMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS + subZ * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMaxZ = subMinZ + ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS - 1;
        int centerX = (subMinX + subMaxX) / 2;
        int centerZ = (subMinZ + subMaxZ) / 2;

        ChokepointData data = dataMap.computeIfAbsent(instanceId, id -> {
            int surfaceY = EnemyEncounterSpawner.findDryLandSurfaceY(level, centerX, centerZ);
            if (surfaceY == Integer.MIN_VALUE) {
                surfaceY = player.getBlockY();
            }
            BlockPos centerPos = new BlockPos(centerX, surfaceY, centerZ);
            ChokepointData cd = new ChokepointData(centerPos);
            cd.defenseIntegrity = progress.currentProgress() > 0 ? progress.currentProgress() : 100;
            cd.currentWave = progress.phaseIndex();
            cd.activeWaveMobs.addAll(progress.trackedEntityUuids());
            progress.setCurrentProgress(cd.defenseIntegrity);
            progress.setTargetProgress(100);
            progress.setMissionSiteAnchor(centerPos);
            return cd;
        });

        // Purge dead or unloaded entities
        data.activeWaveMobs.removeIf(uuid -> {
            Entity e = level.getEntity(uuid);
            return e == null || !e.isAlive();
        });

        long now = level.getGameTime();

        // 1. Assault Wave Spawning
        boolean spawnNextWave = false;
        if (data.currentWave == 0) {
            spawnNextWave = true;
        } else if (data.currentWave < data.totalWaves && data.activeWaveMobs.size() <= 1) {
            if (now - data.lastWaveSpawnTick >= 100) { // 5-second breathing room between waves
                spawnNextWave = true;
            }
        }

        if (spawnNextWave) {
            data.currentWave++;
            data.lastWaveSpawnTick = now;
            progress.setPhaseIndex(data.currentWave);

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
                dirX = (subX == 0) ? -1 : 1;
            }

            int waveSize = switch (data.currentWave) {
                case 1 -> 5; // 3 Warriors, 1 Marksman, 1 Elite
                case 2 -> 6; // 3 Warriors, 2 Marksmen, 1 Elite
                default -> 7; // 3 Warriors, 2 Marksmen, 2 Elites
            };

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

                Mob mob;
                if (i >= waveSize - (data.currentWave == 3 ? 2 : 1)) {
                    mob = ModEntities.PILLAGER_ELITE.get().create(level);
                } else if (i % 2 == 1) {
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
                    data.activeWaveMobs.add(mob.getUUID());
                }
            }

            player.displayClientMessage(
                    Component.literal(String.format("§6⚔️ Defense Perimeter active! Wave %d/%d invading from the border!",
                            data.currentWave, data.totalWaves)),
                    true
            );
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.5F, 1.0F);

            ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
        }

        // 2. Abstract Defense Integrity Decay / Stabilization Evaluation (Every 20 ticks / 1 second)
        if (now - data.lastDecayTick >= 20) {
            data.lastDecayTick = now;

            // Count invading hostiles inside the defense perimeter (18m radius)
            int invadersInsidePerimeter = 0;
            for (UUID mobUuid : data.activeWaveMobs) {
                Entity e = level.getEntity(mobUuid);
                if (e != null && e.isAlive()) {
                    if (e.distanceToSqr(data.perimeterCenter.getX(), data.perimeterCenter.getY(), data.perimeterCenter.getZ()) <= data.perimeterRadiusSq) {
                        invadersInsidePerimeter++;
                    }
                }
            }

            // Check if player is present in or near the defense perimeter (24m contesting range)
            boolean playerContesting = false;
            for (ServerPlayer sp : level.players()) {
                if (sp.isAlive() && !sp.isSpectator()) {
                    if (sp.distanceToSqr(data.perimeterCenter.getX(), data.perimeterCenter.getY(), data.perimeterCenter.getZ()) <= 24.0D * 24.0D) {
                        playerContesting = true;
                        break;
                    }
                }
            }

            if (invadersInsidePerimeter > 0) {
                if (!playerContesting) {
                    // Uncontested penetration: Decay -1% per second per unengaged hostile
                    data.defenseIntegrity = Math.max(0, data.defenseIntegrity - invadersInsidePerimeter);
                }
                // When player IS contesting the perimeter, integrity is stabilized (no decay)
            } else if (playerContesting && data.defenseIntegrity < 100) {
                // Passive recovery when perimeter is secure (+1% per second)
                data.defenseIntegrity = Math.min(100, data.defenseIntegrity + 1);
            }

            progress.setCurrentProgress(data.defenseIntegrity);

            // Failure Condition: Defense Integrity reaches 0%
            if (data.defenseIntegrity <= 0) {
                ActiveCampaignMissionManager.failMission(level, regionX, regionZ, subX, subZ, progress,
                        "Defense perimeter overrun! Defense Integrity collapsed to 0%.");
                dataMap.remove(instanceId);
                return;
            }

            if (data.defenseIntegrity <= 35) {
                player.displayClientMessage(
                        Component.literal("§c§l⚠️ DEFENSE INTEGRITY CRITICAL: " + data.defenseIntegrity + "%!"),
                        true
                );
            }

            ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
        }

        // 3. Victory Condition: All 3 waves defeated with defense integrity > 0%
        if (data.currentWave >= data.totalWaves && data.activeWaveMobs.isEmpty() && data.defenseIntegrity > 0) {
            ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
            dataMap.remove(instanceId);
            player.displayClientMessage(
                    Component.literal("§a§l🛡️ CHOKEPOINT HELD! All hostile invasion waves repelled!"),
                    false
            );
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
        ChokepointData data = dataMap.get(instanceId);

        if (mobUuid != null && data != null && data.activeWaveMobs.contains(mobUuid)) {
            data.activeWaveMobs.remove(mobUuid);
            progress.trackedEntityUuids().remove(mobUuid);

            // Defeating hostiles provides +3% integrity stabilization
            data.defenseIntegrity = Math.min(100, data.defenseIntegrity + 3);
            progress.setCurrentProgress(data.defenseIntegrity);

            RegionData regions = RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
            }

            if (data.currentWave >= data.totalWaves && data.activeWaveMobs.isEmpty() && data.defenseIntegrity > 0) {
                ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
                dataMap.remove(instanceId);
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
            dataMap.remove(progress.missionInstanceId());
        }
    }
}
