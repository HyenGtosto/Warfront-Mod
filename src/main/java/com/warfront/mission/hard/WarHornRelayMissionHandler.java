package com.warfront.mission.hard;

import com.warfront.ai.goal.MissionPostGuardGoal;
import com.warfront.block.SalvageMachineryBlock;
import com.warfront.block.WarfrontBlocks;
import com.warfront.entity.ModEntities;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.mission.MissionObjectiveHandler;
import com.warfront.mission.site.MissionSiteAnchorResolver;
import com.warfront.mission.site.MissionSiteSnapshot;
import com.warfront.mission.site.MissionSiteSnapshotManager;
import com.warfront.mission.site.TemporaryStructureBuilder;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.spawn.EnemyEncounterSpawner;
import com.warfront.spawn.ExplorationSpawnManager;
import com.warfront.spawn.MissionEntityTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
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
        final List<BlockPos> fusePositions;
        final List<BlockPos> hornBellPositions;
        int countdownTicks = 120 * 20; // 120 seconds
        boolean fuseSabotaged = false;
        final List<UUID> battalionUuids = new ArrayList<>();
        int battalionKills = 0;
        final int targetBattalionKills = 6; // 2 Elites, 3 Marksmen, 1 Commander
        long lastTickTime = 0;
        long lastWaveSpawnTime = 0;

        HornTowerData(BlockPos anchor, List<BlockPos> fusePositions, List<BlockPos> hornBellPositions) {
            this.anchor = anchor;
            this.fusePositions = new ArrayList<>(fusePositions);
            this.hornBellPositions = new ArrayList<>(hornBellPositions);
            this.fusePos = fusePositions.isEmpty() ? null : fusePositions.get(0);
            this.hornBellPos = hornBellPositions.isEmpty() ? null : hornBellPositions.get(0);
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
            if (progress.temporaryStructuresBuilt() || (progress.missionSiteAnchor() != null && !progress.trackedBlockPositions().isEmpty())) {
                progress.setTemporaryStructuresBuilt(true);
                BlockPos anc = progress.missionSiteAnchor();
                MissionSiteSnapshot snapshot = MissionSiteSnapshotManager.getOrCreate(instanceId, regionX, regionZ, subX, subZ);
                TemporaryStructureBuilder.WarHornTowerResult result = TemporaryStructureBuilder.buildWarHornTower(level, anc, snapshot);

                for (BlockPos b : result.hornBellPositions()) {
                    SalvageMachineryBlock.rearm(level, b);
                }
                for (BlockPos f : result.fuseRelayPositions()) {
                    SalvageMachineryBlock.rearm(level, f);
                }

                data = new HornTowerData(anc, result.fuseRelayPositions(), result.hornBellPositions());
                data.countdownTicks = 120 * 20;
                towerDataMap.put(instanceId, data);

                progress.trackedBlockPositions().clear();
                progress.trackedBlockPositions().addAll(result.structureBlocks());
                progress.setPhaseIndex(0); // Phase 1: 120s Scramble
                progress.setTargetProgress(120);
                progress.setCurrentProgress(0);
                progress.trackedEntityUuids().clear();

                level.playSound(null, anc.above(28), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 3.0F, 0.8F);

                if (player != null) {
                    player.displayClientMessage(Component.literal("§c⚠️ War Horn Tower signal has been re-activated!"), true);
                }
                RegionData regions = RegionData.get(level);
                if (regions != null) {
                    regions.setDirty();
                }
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
                return;
            } else {
                RegionData regions = RegionData.get(level);
                long missionSeed = (regions != null) ? regions.calculateMissionSeed(regionX, regionZ) : 0L;

                BlockPos anchor = MissionSiteAnchorResolver.resolveDryLandAnchor(level, regionX, regionZ, subX, subZ, missionSeed, 9, 9);
                progress.setMissionSiteAnchor(anchor);

                MissionSiteSnapshot snapshot = MissionSiteSnapshotManager.getOrCreate(instanceId, regionX, regionZ, subX, subZ);
                TemporaryStructureBuilder.WarHornTowerResult result = TemporaryStructureBuilder.buildWarHornTower(level, anchor, snapshot);

                data = new HornTowerData(anchor, result.fuseRelayPositions(), result.hornBellPositions());
                towerDataMap.put(instanceId, data);

                progress.trackedBlockPositions().clear();
                progress.trackedBlockPositions().addAll(result.structureBlocks());
                progress.setTemporaryStructuresBuilt(true);
                progress.setPhaseIndex(0); // Phase 1: 120s Scramble
                progress.setTargetProgress(120);
                progress.setCurrentProgress(0);

                // Unguarded broadcast tower: "it doesnt need any mobs protecting it. the thrill comes from mobs coming at you from borders."
                level.playSound(null, anchor.above(28), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 3.0F, 0.8F);

                if (regions != null) {
                    regions.setDirty();
                }
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
                return;
            }
        }

        // 2. Phase 1: 120-Second Signal Timer Scramble
        if (progress.phaseIndex() == 0) {
            long now = level.getGameTime();
            if (data.lastTickTime == 0) data.lastTickTime = now;
            long elapsed = now - data.lastTickTime;
            data.lastTickTime = now;

            int step = (elapsed > 0 && elapsed < 40) ? (int) elapsed : 1;
            data.countdownTicks = Math.max(0, data.countdownTicks - step);

            int remainingSeconds = data.countdownTicks / 20;
            progress.setCurrentProgress(120 - remainingSeconds);

            // Escalating reinforcement waves at 30s and 15s
            if (data.countdownTicks == 30 * 20 || data.countdownTicks == 80 * 20) {
                spawnBorderWaveIfReachable(level, regionX, regionZ, subX, subZ, progress, data);
            }

            if (data.countdownTicks % 20 == 0) {
                if (player != null) {
                    player.displayClientMessage(
                            Component.literal(String.format("§e📯 War Horn Signal: §c%ds §eleft! Sabotage summit fuse!", remainingSeconds)),
                            true
                    );
                }
            }

            // If countdown expires before sabotage: deafening war siren triggers and deploys response battalion
            if (data.countdownTicks <= 0) {
                BlockPos sirenPos = data.anchor.above(14);
                level.playSound(null, sirenPos, SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(2).value(), SoundSource.HOSTILE, 64.0F, 1.0F);
                level.playSound(null, sirenPos, SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(2).value(), SoundSource.HOSTILE, 64.0F, 1.06F);
                level.playSound(null, sirenPos, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 48.0F, 0.70F);
                transitionToPhase2(level, regionX, regionZ, subX, subZ, progress, data, false);
            }
        } else if (progress.phaseIndex() == 1) {
            long now = level.getGameTime();
            if (data.lastWaveSpawnTime == 0) data.lastWaveSpawnTime = now;
            // Every 35 seconds in Phase 2, if total living mission mobs < 6, spawn reinforcements from reachable borders
            if (now - data.lastWaveSpawnTime >= 35 * 20) {
                data.lastWaveSpawnTime = now;
                if (MissionEntityTracker.getLivingMissionMobCount(progress.missionInstanceId(), level) < 6) {
                    spawnBorderWaveIfReachable(level, regionX, regionZ, subX, subZ, progress, data);
                }
            }
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

        // Spawn response battalion: 2 Elites, 3 Marksmen, 1 Commander (6 combatants total around the tower)
        int[][] spawnOffsets = {{-10, 0}, {10, 0}, {0, -10}, {0, 10}, {-8, 8}, {8, -8}};
        for (int i = 0; i < spawnOffsets.length; i++) {
            int[] off = spawnOffsets[i];
            Mob mob;
            if (i < 2) {
                mob = ModEntities.PILLAGER_ELITE.get().create(level);
            } else if (i == 2) {
                mob = ModEntities.PILLAGER_COMMANDER.get().create(level);
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
                    mob.setPersistenceRequired();
                    mob.getPersistentData().putBoolean("preventActiveDespawn", true);
                    mob.goalSelector.addGoal(1, new MissionPostGuardGoal(mob, data.anchor, 28.0D, 1.15D));
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

    public enum SubRegionBorder {
        NORTH, SOUTH, WEST, EAST
    }

    private static List<SubRegionBorder> getPillagerControlledBorders(ServerLevel level, int rx, int rz, int sx, int sz) {
        RegionData regions = RegionData.get(level);
        if (regions == null) return Collections.emptyList();

        List<SubRegionBorder> pillagerBorders = new ArrayList<>();

        // Check North (sz == 0 -> rz - 1, sz = 1; sz == 1 -> sz = 0)
        int northRx = rx, northRz = (sz == 0) ? rz - 1 : rz;
        int northSx = sx, northSz = (sz == 0) ? 1 : 0;
        if (isPillagerTerritory(regions, northRx, northRz, northSx, northSz)) {
            pillagerBorders.add(SubRegionBorder.NORTH);
        }

        // Check South (sz == 1 -> rz + 1, sz = 0; sz == 0 -> sz = 1)
        int southRx = rx, southRz = (sz == 1) ? rz + 1 : rz;
        int southSx = sx, southSz = (sz == 1) ? 0 : 1;
        if (isPillagerTerritory(regions, southRx, southRz, southSx, southSz)) {
            pillagerBorders.add(SubRegionBorder.SOUTH);
        }

        // Check West (sx == 0 -> rx - 1, sx = 1; sx == 1 -> sx = 0)
        int westRx = (sx == 0) ? rx - 1 : rx, westRz = rz;
        int westSx = (sx == 0) ? 1 : 0, westSz = sz;
        if (isPillagerTerritory(regions, westRx, westRz, westSx, westSz)) {
            pillagerBorders.add(SubRegionBorder.WEST);
        }

        // Check East (sx == 1 -> rx + 1, sx = 0; sx == 0 -> sx = 1)
        int eastRx = (sx == 1) ? rx + 1 : rx, eastRz = rz;
        int eastSx = (sx == 1) ? 0 : 1, eastSz = sz;
        if (isPillagerTerritory(regions, eastRx, eastRz, eastSx, eastSz)) {
            pillagerBorders.add(SubRegionBorder.EAST);
        }

        return pillagerBorders;
    }

    private static boolean isPillagerTerritory(RegionData regions, int rx, int rz, int sx, int sz) {
        RegionData.SubRegionState state = regions.subRegionAt(rx, rz, sx, sz);
        if (state != null) {
            return state.owner() == Faction.PILLAGER_CONQUERORS;
        }
        RegionData.Region reg = regions.regionAt(rx, rz);
        return reg != null && reg.owner() == Faction.PILLAGER_CONQUERORS;
    }

    private void spawnBorderWaveIfReachable(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            HornTowerData data
    ) {
        List<SubRegionBorder> borders = getPillagerControlledBorders(level, regionX, regionZ, subX, subZ);
        if (borders.isEmpty()) {
            // Unprotected looking base: spawn nothing if it can reach nothing.
            return;
        }

        SubRegionBorder border = borders.get(level.getRandom().nextInt(borders.size()));
        BlockPos borderSpawn = pickBorderPointOnEdge(level, border, regionX, regionZ, subX, subZ);
        if (borderSpawn == null) {
            return;
        }

        String borderName = switch (border) {
            case NORTH -> "Northern";
            case SOUTH -> "Southern";
            case WEST -> "Western";
            case EAST -> "Eastern";
        };
        ActiveCampaignMissionManager.notifyAttackingPlayersActionbar(level, regionX, regionZ,
                "§c📯 Pillager reinforcements marching from the " + borderName + " border!");

        // Play Tri-Tone War Siren Cluster from the tower summit acoustic bell (deafening war siren)
        BlockPos sirenPos = data.anchor.above(14);
        level.playSound(null, sirenPos, SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(2).value(), SoundSource.HOSTILE, 128.0F, 1.0F);
        level.playSound(null, sirenPos, SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(2).value(), SoundSource.HOSTILE, 128.0F, 1.06F);
        level.playSound(null, sirenPos, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 96.0F, 0.70F);

        // Spawn a 3-man strike team: 2 Warriors and 1 Marksman (NO Armored Elites in waves!)
        for (int i = 0; i < 3; i++) {
            Mob mob = (i < 2)
                    ? ModEntities.PILLAGER_WARRIOR.get().create(level)
                    : ModEntities.PILLAGER_MARKSMAN.get().create(level);
            if (mob != null) {
                int ox = level.getRandom().nextInt(5) - 2;
                int oz = level.getRandom().nextInt(5) - 2;
                int mx = borderSpawn.getX() + ox;
                int mz = borderSpawn.getZ() + oz;
                int my = EnemyEncounterSpawner.findDryLandSurfaceY(level, mx, mz);
                if (my == Integer.MIN_VALUE) my = borderSpawn.getY();

                mob.moveTo(mx + 0.5D, my, mz + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(new BlockPos(mx, my, mz)), MobSpawnType.EVENT, null);
                if (level.addFreshEntity(mob)) {
                    setupMob(mob, progress.missionInstanceId(), regionX, regionZ, subX, subZ, progress);
                    mob.getNavigation().moveTo(data.anchor.getX() + 0.5D, data.anchor.getY(), data.anchor.getZ() + 0.5D, 1.15D);
                }
            }
        }
    }

    private BlockPos pickBorderPointOnEdge(ServerLevel level, SubRegionBorder border, int rx, int rz, int sx, int sz) {
        int subMinX = rx * RegionData.REGION_SIZE_BLOCKS + sx * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int subMinZ = rz * RegionData.REGION_SIZE_BLOCKS + sz * ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;
        int size = ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS;

        for (int attempt = 0; attempt < 16; attempt++) {
            int x, z;
            switch (border) {
                case NORTH -> { x = subMinX + level.getRandom().nextInt(size); z = subMinZ; }
                case SOUTH -> { x = subMinX + level.getRandom().nextInt(size); z = subMinZ + size - 1; }
                case WEST -> { x = subMinX; z = subMinZ + level.getRandom().nextInt(size); }
                default -> { x = subMinX + size - 1; z = subMinZ + level.getRandom().nextInt(size); }
            }
            int y = EnemyEncounterSpawner.findDryLandSurfaceY(level, x, z);
            if (y != Integer.MIN_VALUE) {
                BlockPos p = new BlockPos(x, y, z);
                if (level.getFluidState(p).isEmpty() && level.getFluidState(p.above()).isEmpty()) {
                    return p;
                }
            }
        }
        return null;
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
        if (progress.phaseIndex() == 0 && (data.fusePositions.contains(pos) || pos.equals(data.fusePos) || state.is(WarfrontBlocks.SIGNAL_FUSE_RELAY.get()))) {
            data.fuseSabotaged = true;
            data.fusePositions.clear();
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
        if (data != null && data.hornBellPositions != null && !data.hornBellPositions.isEmpty()) {
            for (BlockPos bp : data.hornBellPositions) {
                SalvageMachineryBlock.disarm(level, bp);
            }
        } else if (data != null && data.hornBellPos != null) {
            SalvageMachineryBlock.disarm(level, data.hornBellPos);
        }

        if (player != null) {
            player.sendSystemMessage(Component.literal("§a§l[WARFRONT] 🏆 Warhorn Broadcast Tower Neutralized! Acoustic Horn Bell Disarmed."));
        } else {
            ActiveCampaignMissionManager.notifyAttackingPlayers(level, regionX, regionZ,
                    "§a§l[WARFRONT] 🏆 Warhorn Broadcast Tower Neutralized! Acoustic Horn Bell Disarmed.");
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
        if (data != null && data.hornBellPositions != null && !data.hornBellPositions.isEmpty()) {
            for (BlockPos bp : data.hornBellPositions) {
                SalvageMachineryBlock.disarm(level, bp);
            }
        } else if (data != null && data.hornBellPos != null) {
            SalvageMachineryBlock.disarm(level, data.hornBellPos);
        }

        for (UUID uuid : progress.trackedEntityUuids()) {
            Entity ent = level.getEntity(uuid);
            if (ent != null && ent.isAlive()) {
                ent.discard();
            }
            MissionEntityTracker.unregisterMissionMob(uuid);
        }
        progress.trackedEntityUuids().clear();
    }
}
