package com.warfront.mission.hard;

import com.warfront.block.SalvageMachineryBlock;
import com.warfront.entity.ModEntities;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.mission.MissionObjectiveHandler;
import com.warfront.mission.site.MissionSiteAnchorResolver;
import com.warfront.mission.site.MissionSiteSnapshot;
import com.warfront.mission.site.MissionSiteSnapshotManager;
import com.warfront.mission.site.TemporaryStructureBuilder;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.spawn.MissionEntityTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hard-tier mission handler for {@link com.warfront.mission.MissionType#WAR_HORN_RELAY}
 * (and {@link com.warfront.mission.MissionType#CUT_THE_SUPPLY_LINE} logistics tower).
 *
 * Spawns a 14-block tall Dark Oak signal mast topped with a crow's nest,
 * housing the Signal Fuse Relay (sabotage target) and Acoustic Horn Bell (salvage prize).
 *
 * Multi-phase gameplay:
 * - Phase 1 (The Relay Scramble): 45-second countdown to scale the tower and sabotage the summit fuse.
 * - Phase 2 (Battalion Defense): Eliminate the response battalion.
 * - Lenient Anti-Sky-Camping: Warns and shells players only if stationary in the sky for >15 seconds.
 *
 * On victory, the Acoustic Horn Bell disarms to yield the Acoustic War Horn Relay.
 */
public final class WarHornRelayMissionHandler implements MissionObjectiveHandler {

    private static final WarHornRelayMissionHandler INSTANCE = new WarHornRelayMissionHandler();

    private final ConcurrentHashMap<UUID, HornTowerData> towerDataMap = new ConcurrentHashMap<>();

    public static class HornTowerData {
        final BlockPos anchor;
        BlockPos fusePos;
        final BlockPos hornBellPos;
        int countdownTicks = 45 * 20; // 45 seconds
        boolean fuseSabotaged = false;
        final List<UUID> battalionUuids = new ArrayList<>();
        int battalionKills = 0;
        final int targetBattalionKills = 5;

        // Anti-sky-camping tracking
        double lastPlayerX;
        double lastPlayerY;
        double lastPlayerZ;
        int stationarySkyTicks = 0;
        long lastTickTime = 0;

        HornTowerData(BlockPos anchor, BlockPos fusePos, BlockPos hornBellPos) {
            this.anchor = anchor;
            this.fusePos = fusePos;
            this.hornBellPos = hornBellPos;
        }
    }

    private WarHornRelayMissionHandler() {
    }

    public static WarHornRelayMissionHandler getInstance() {
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
        HornTowerData data = towerDataMap.get(instanceId);

        // 1. Initial Site Construction & Defender Generation
        if (data == null) {
            RegionData regions = RegionData.get(level);
            long missionSeed = (regions != null) ? regions.calculateMissionSeed(regionX, regionZ) : 0L;

            BlockPos anchor = MissionSiteAnchorResolver.resolveDryLandAnchor(level, regionX, regionZ, subX, subZ, missionSeed);
            progress.setMissionSiteAnchor(anchor);

            MissionSiteSnapshot snapshot = MissionSiteSnapshotManager.getOrCreate(instanceId, regionX, regionZ, subX, subZ);
            TemporaryStructureBuilder.WarHornTowerResult result = TemporaryStructureBuilder.buildWarHornTower(level, anchor, snapshot);

            data = new HornTowerData(anchor, result.fuseRelayPos(), result.hornBellPos());
            towerDataMap.put(instanceId, data);

            progress.trackedBlockPositions().clear();
            progress.trackedBlockPositions().addAll(result.structureBlocks());
            progress.setPhaseIndex(0); // Phase 1: 45s Scramble
            progress.setTargetProgress(45);
            progress.setCurrentProgress(0);

            // Initial tower guards: 1 Marksman perched at summit, 2 Warriors at the base
            Mob summitSniper = ModEntities.PILLAGER_MARKSMAN.get().create(level);
            if (summitSniper != null) {
                summitSniper.moveTo(anchor.getX() + 0.5D, anchor.getY() + 14.5D, anchor.getZ() + 0.5D, 0.0F, 0.0F);
                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(summitSniper, level, level.getCurrentDifficultyAt(anchor.above(14)), MobSpawnType.EVENT, null);
                if (level.addFreshEntity(summitSniper)) {
                    setupMob(summitSniper, instanceId, regionX, regionZ, subX, subZ, progress);
                }
            }

            for (int dx : new int[]{-3, 3}) {
                Mob guard = ModEntities.PILLAGER_WARRIOR.get().create(level);
                if (guard != null) {
                    guard.moveTo(anchor.getX() + dx + 0.5D, anchor.getY() + 1.0D, anchor.getZ() + 0.5D, 0.0F, 0.0F);
                    net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(guard, level, level.getCurrentDifficultyAt(guard.blockPosition()), MobSpawnType.EVENT, null);
                    if (level.addFreshEntity(guard)) {
                        setupMob(guard, instanceId, regionX, regionZ, subX, subZ, progress);
                    }
                }
            }

            // Sound initial alert horn
            level.playSound(null, anchor.above(14), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 3.0F, 0.8F);

            if (regions != null) {
                regions.setDirty();
            }
            ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
            return;
        }

        // 2. Phase 1: 45-Second Signal Timer Scramble
        if (progress.phaseIndex() == 0) {
            long now = level.getGameTime();
            if (data.lastTickTime == 0) data.lastTickTime = now;
            long elapsed = now - data.lastTickTime;
            data.lastTickTime = now;

            int step = (elapsed > 0 && elapsed < 40) ? (int) elapsed : 1;
            data.countdownTicks = Math.max(0, data.countdownTicks - step);

            int remainingSeconds = data.countdownTicks / 20;
            progress.setCurrentProgress(45 - remainingSeconds);

            // Escalating horn blasts at 30s and 15s
            if (data.countdownTicks == 30 * 20 || data.countdownTicks == 15 * 20) {
                level.playSound(null, data.anchor.above(14), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 3.5F, (data.countdownTicks == 15 * 20) ? 1.4F : 1.1F);
            }

            if (data.countdownTicks % 20 == 0) {
                if (player != null) {
                    player.displayClientMessage(
                            Component.literal(String.format("§e📯 War Horn Signal: §c%ds §eleft! Sabotage summit fuse!", remainingSeconds)),
                            true
                    );
                }
            }

            // If countdown expires before sabotage: horn fires signal and deploys response battalion
            if (data.countdownTicks <= 0) {
                level.playSound(null, data.anchor.above(14), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 4.0F, 1.6F);
                transitionToPhase2(level, regionX, regionZ, subX, subZ, progress, data, false);
            }
        } else if (progress.phaseIndex() == 1) {
            // 3. Phase 2: Lenient Anti-Sky-Camping (warns/shells if stationary > 15s in the sky)
            tickAntiSkyCamping(level, data, player);
        }
    }

    private void transitionToPhase2(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            HornTowerData data,
            boolean fuseSabotaged
    ) {
        progress.setPhaseIndex(1);
        progress.setTargetProgress(data.targetBattalionKills);
        progress.setCurrentProgress(0);

        String titleMsg = fuseSabotaged
                ? "§a⚡ Signal Fuse Sabotaged! Eliminate the defense battalion!"
                : "§c📯 Horn Signal sounded! Repel incoming response battalion!";

        ActiveCampaignMissionManager.notifyAttackingPlayersActionbar(level, regionX, regionZ, titleMsg);

        // Spawn response battalion: 2 Armored Elites, 2 Warriors, 1 Marksman
        int[][] spawnOffsets = {{-10, 0}, {10, 0}, {0, -10}, {0, 10}, {-6, 6}};
        for (int i = 0; i < spawnOffsets.length; i++) {
            int[] off = spawnOffsets[i];
            Mob mob;
            if (i < 2) {
                mob = ModEntities.PILLAGER_ARMORED_ELITE.get().create(level);
            } else if (i < 4) {
                mob = ModEntities.PILLAGER_WARRIOR.get().create(level);
            } else {
                mob = ModEntities.PILLAGER_MARKSMAN.get().create(level);
            }

            if (mob != null) {
                int sx = data.anchor.getX() + off[0];
                int sz = data.anchor.getZ() + off[1];
                int sy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
                mob.moveTo(sx + 0.5D, sy, sz + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(new BlockPos(sx, sy, sz)), MobSpawnType.EVENT, null);
                if (level.addFreshEntity(mob)) {
                    setupMob(mob, progress.missionInstanceId(), regionX, regionZ, subX, subZ, progress);
                    data.battalionUuids.add(mob.getUUID());
                }
            }
        }

        RegionData regions = RegionData.get(level);
        if (regions != null) {
            regions.setDirty();
        }
        ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
    }

    private void tickAntiSkyCamping(ServerLevel level, HornTowerData data, ServerPlayer player) {
        if (player == null) return;

        double py = player.getY();
        double groundY = data.anchor.getY();

        // Sky camping gate: >12 blocks above base ground
        if (py - groundY > 12.0D) {
            double distSq = player.distanceToSqr(data.lastPlayerX, data.lastPlayerY, data.lastPlayerZ);
            if (distSq < 1.0D) {
                // Stationary in sky
                data.stationarySkyTicks++;
                if (data.stationarySkyTicks == 10 * 20) { // 10s warning
                    player.displayClientMessage(
                            Component.literal("§c⚠️ WARNING: Sky roost detected! Anti-aircraft mortar targeting stationary position..."),
                            true
                    );
                    level.playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.HOSTILE, 1.0F, 1.8F);
                } else if (data.stationarySkyTicks >= 15 * 20) { // 15s mortar shell fired
                    data.stationarySkyTicks = 8 * 20; // reset cooldown to 7s for subsequent shells
                    level.playSound(null, player.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3.0F, 0.6F);
                    level.explode(
                            null,
                            player.getX(), player.getY() + 1.0D, player.getZ(),
                            2.5F,
                            Level.ExplosionInteraction.MOB
                    );
                }
            } else {
                data.stationarySkyTicks = 0;
            }
        } else {
            data.stationarySkyTicks = 0;
        }

        data.lastPlayerX = player.getX();
        data.lastPlayerY = player.getY();
        data.lastPlayerZ = player.getZ();
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

        HornTowerData data = towerDataMap.get(progress.missionInstanceId());
        if (data == null) {
            return false;
        }

        if (progress.phaseIndex() == 1) {
            if (mob != null && data.battalionUuids.remove(mob.getUUID())) {
                data.battalionKills++;
                progress.incrementProgress();

                RegionData regions = RegionData.get(level);
                if (regions != null) {
                    regions.setDirty();
                }

                if (data.battalionKills >= data.targetBattalionKills || data.battalionUuids.isEmpty()) {
                    triggerVictory(level, regionX, regionZ, subX, subZ, progress, data, null);
                } else {
                    ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
                }
                return true;
            }
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

        HornTowerData data = towerDataMap.get(progress.missionInstanceId());
        if (data == null) {
            return false;
        }

        // Check if Signal Fuse Relay sabotaged during Phase 1
        if (progress.phaseIndex() == 0 && pos.equals(data.fusePos)) {
            data.fuseSabotaged = true;
            data.fusePos = null;

            level.playSound(null, pos, SoundEvents.ITEM_BREAK, SoundSource.BLOCKS, 1.2F, 0.8F);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 15, 0.3D, 0.3D, 0.3D, 0.08D);

            if (player != null) {
                player.displayClientMessage(
                        Component.literal("§a⚡ Signal Fuse Relay Sabotaged! Enemy reinforcements severed!"),
                        true
                );
            }

            transitionToPhase2(level, regionX, regionZ, subX, subZ, progress, data, true);
            return true;
        }

        return false;
    }

    private void triggerVictory(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            HornTowerData data,
            ServerPlayer player
    ) {
        if (data != null && data.hornBellPos != null) {
            SalvageMachineryBlock.disarm(level, data.hornBellPos);
        }

        if (player != null) {
            player.sendSystemMessage(Component.literal("§a§l[WARFRONT] 🏆 Signal Mast Secured! Acoustic Horn Bell Disarmed."));
        } else {
            ActiveCampaignMissionManager.notifyAttackingPlayers(level, regionX, regionZ,
                    "§a§l[WARFRONT] 🏆 Signal Mast Secured! Acoustic Horn Bell Disarmed.");
        }

        ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
    }

    @Override
    public void onCleanup(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        HornTowerData data = towerDataMap.remove(progress.missionInstanceId());
        if (data != null && data.hornBellPos != null) {
            SalvageMachineryBlock.disarm(level, data.hornBellPos);
        }

        if (!progress.isCompleted()) {
            MissionSiteSnapshotManager.restoreAndRemove(level, progress.missionInstanceId());
        }
    }
}
