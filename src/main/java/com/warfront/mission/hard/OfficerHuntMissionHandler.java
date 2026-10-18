package com.warfront.mission.hard;

import com.warfront.ai.goal.MissionPostGuardGoal;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hard-tier mission handler for {@link com.warfront.mission.MissionType#OFFICER_HUNT}.
 *
 * Spawns a 32x32 procedural military encampment featuring outer tents, weapon racks,
 * and a large central Command Pavilion housing the Pillager Commander and his War Desk.
 *
 * Multi-phase gameplay:
 * - Phase 1: Assassinate the Pillager Commander inside the Command Pavilion.
 * - Phase 2: Camp alarm sounds. Survive the 45-second counter-assault OR eliminate the response squad.
 *
 * On victory, the Commander's War Desk disarms to yield the Commander's Battle Standard.
 */
public final class OfficerHuntMissionHandler implements MissionObjectiveHandler {

    private static final OfficerHuntMissionHandler INSTANCE = new OfficerHuntMissionHandler();

    private final ConcurrentHashMap<UUID, OfficerHuntData> huntDataMap = new ConcurrentHashMap<>();

    public static class OfficerHuntData {
        final BlockPos anchor;
        final BlockPos warDeskPos;
        final List<BlockPos> warDeskPositions;
        UUID commanderUuid;
        final List<UUID> responseSquadUuids = new ArrayList<>();
        int survivalTicks = 0;
        int responseKills = 0;
        final int targetResponseKills = 6;
        long lastTickTime = 0;

        OfficerHuntData(BlockPos anchor, BlockPos warDeskPos, List<BlockPos> warDeskPositions) {
            this.anchor = anchor;
            this.warDeskPos = warDeskPos;
            this.warDeskPositions = new ArrayList<>(warDeskPositions);
        }
    }

    private OfficerHuntMissionHandler() {
    }

    public static OfficerHuntMissionHandler getInstance() {
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
        OfficerHuntData data = huntDataMap.get(instanceId);

        // 1. Initial Site Construction & Garrison Generation
        if (data == null) {
            if (progress.temporaryStructuresBuilt() || (progress.missionSiteAnchor() != null && !progress.trackedBlockPositions().isEmpty())) {
                progress.setTemporaryStructuresBuilt(true);
                BlockPos anc = progress.missionSiteAnchor();
                BlockPos deskPos = null;
                List<BlockPos> deskList = new ArrayList<>();
                for (BlockPos p : progress.trackedBlockPositions()) {
                    if (level.hasChunkAt(p) && (level.getBlockState(p).is(com.warfront.block.WarfrontBlocks.COMMANDER_WAR_DESK.get()) || level.getBlockState(p).getBlock() instanceof SalvageMachineryBlock)) {
                        deskList.add(p);
                        if (deskPos == null) deskPos = p;
                    }
                }
                data = new OfficerHuntData(anc, deskPos != null ? deskPos : anc, deskList);
                huntDataMap.put(instanceId, data);
            } else {
                RegionData regions = RegionData.get(level);
                long missionSeed = (regions != null) ? regions.calculateMissionSeed(regionX, regionZ) : 0L;

                BlockPos anchor = MissionSiteAnchorResolver.resolveDryLandAnchor(level, regionX, regionZ, subX, subZ, missionSeed, 27, 31);
                progress.setMissionSiteAnchor(anchor);

                MissionSiteSnapshot snapshot = MissionSiteSnapshotManager.getOrCreate(instanceId, regionX, regionZ, subX, subZ);
                TemporaryStructureBuilder.OfficerCampResult result = TemporaryStructureBuilder.buildOfficerCamp(level, anchor, snapshot);

                data = new OfficerHuntData(anchor, result.warDeskPos(), result.warDeskPositions());
                huntDataMap.put(instanceId, data);

                progress.trackedBlockPositions().clear();
                progress.trackedBlockPositions().addAll(result.structureBlocks());
                progress.setTemporaryStructuresBuilt(true);
                progress.setPhaseIndex(0); // Phase 1: Assassination
                progress.setTargetProgress(1);
                progress.setCurrentProgress(0);

                // Spawn Commander inside Big Tent in front of War Desk with boosted armor & stats
                Mob commander = ModEntities.PILLAGER_COMMANDER.get().create(level);
                if (commander != null) {
                    commander.moveTo(result.commanderSpawnPos().getX() + 0.5D, result.commanderSpawnPos().getY(), result.commanderSpawnPos().getZ() + 0.5D, 0.0F, 0.0F);

                    var armorAttr = commander.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
                    if (armorAttr != null) armorAttr.setBaseValue(16.0D); // Reinforced heavy armor plating

                    var toughnessAttr = commander.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
                    if (toughnessAttr != null) toughnessAttr.setBaseValue(8.0D);

                    var healthAttr = commander.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
                    if (healthAttr != null) {
                        healthAttr.setBaseValue(65.0D);
                        commander.setHealth(65.0F);
                    }

                    var kbAttr = commander.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
                    if (kbAttr != null) kbAttr.setBaseValue(0.60D);

                    commander.setCustomName(Component.literal("§cField Commander §4[Target]"));
                    commander.setCustomNameVisible(true);

                    net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(commander, level, level.getCurrentDifficultyAt(result.commanderSpawnPos()), MobSpawnType.EVENT, null);
                    if (level.addFreshEntity(commander)) {
                        setupMob(commander, instanceId, regionX, regionZ, subX, subZ, progress);
                        commander.setPersistenceRequired();
                        commander.getPersistentData().putBoolean("preventActiveDespawn", true);
                        commander.goalSelector.addGoal(1, new MissionPostGuardGoal(commander, result.warDeskPos() != null ? result.warDeskPos() : result.commanderSpawnPos(), 20.0D, 1.15D));
                        data.commanderUuid = commander.getUUID();
                    }
                }

                // Spawn 2 Armored Elite bodyguards inside the tent
                for (BlockPos bgPos : result.bodyguardSpawnPositions()) {
                    Mob bodyguard = ModEntities.PILLAGER_ELITE.get().create(level);
                    if (bodyguard != null) {
                        bodyguard.moveTo(bgPos.getX() + 0.5D, bgPos.getY(), bgPos.getZ() + 0.5D, 0.0F, 0.0F);
                        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(bodyguard, level, level.getCurrentDifficultyAt(bgPos), MobSpawnType.EVENT, null);
                        if (level.addFreshEntity(bodyguard)) {
                            setupMob(bodyguard, instanceId, regionX, regionZ, subX, subZ, progress);
                            bodyguard.setPersistenceRequired();
                            bodyguard.getPersistentData().putBoolean("preventActiveDespawn", true);
                            bodyguard.goalSelector.addGoal(1, new MissionPostGuardGoal(bodyguard, bgPos, 20.0D, 1.15D));
                        }
                    }
                }

                // Spawn outer tent / campfire sentries
                for (BlockPos pPos : result.outerPatrolSpawnPositions()) {
                    Mob guard = ModEntities.PILLAGER_WARRIOR.get().create(level);
                    if (guard != null) {
                        guard.moveTo(pPos.getX() + 0.5D, pPos.getY(), pPos.getZ() + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(guard, level, level.getCurrentDifficultyAt(pPos), MobSpawnType.EVENT, null);
                        if (level.addFreshEntity(guard)) {
                            setupMob(guard, instanceId, regionX, regionZ, subX, subZ, progress);
                            guard.setPersistenceRequired();
                            guard.getPersistentData().putBoolean("preventActiveDespawn", true);
                            guard.goalSelector.addGoal(1, new MissionPostGuardGoal(guard, pPos, 16.0D, 1.10D));
                        }
                    }
                }

                if (regions != null) {
                    regions.setDirty();
                }
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
                return;
            }
        }

        // 2. Phase 2 Timer Evaluation (Survival countdown: 45s = 900 ticks)
        if (progress.phaseIndex() == 1) {
            long now = level.getGameTime();
            if (data.lastTickTime == 0) {
                data.lastTickTime = now;
            }
            long elapsed = now - data.lastTickTime;
            data.lastTickTime = now;
            if (elapsed > 0 && elapsed < 40) {
                data.survivalTicks += (int) elapsed;
            } else {
                data.survivalTicks += 1;
            }

            int remainingSeconds = Math.max(0, 45 - (data.survivalTicks / 20));
            progress.setCurrentProgress(45 - remainingSeconds);

            if (data.survivalTicks % 20 == 0 && remainingSeconds > 0) {
                if (player != null) {
                    player.displayClientMessage(
                            Component.literal(String.format("§c🚨 Camp Alarm! Survive Counter-Assault: §e%ds §f(or %d/%d kills)",
                                    remainingSeconds, data.responseKills, data.targetResponseKills)),
                            true
                    );
                }
            }

            // Victory condition: 45s survived OR all response squad eliminated!
            if (data.survivalTicks >= 45 * 20 || data.responseKills >= data.targetResponseKills) {
                triggerVictory(level, regionX, regionZ, subX, subZ, progress, data, player);
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

        OfficerHuntData data = huntDataMap.get(progress.missionInstanceId());
        if (data == null) {
            return false;
        }

        // Phase 1: Commander Assassination
        if (progress.phaseIndex() == 0) {
            if (mob != null && (mob.getUUID().equals(data.commanderUuid) || "COMMANDER".equalsIgnoreCase(mobRoleName))) {
                // Commander has fallen! Transition to Phase 2: Camp Alarm & Counter-Assault
                progress.setPhaseIndex(1);
                progress.setTargetProgress(45); // 45 seconds survival
                progress.setCurrentProgress(0);
                data.survivalTicks = 0;
                data.lastTickTime = level.getGameTime();

                // Sound camp alarm horn
                level.playSound(null, data.anchor, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 3.0F, 1.0F);

                // Spawn response strike team: 4 Warriors + 2 Marksmen
                int[][] responseOffsets = {{-12, 0}, {12, 0}, {0, -12}, {0, 12}, {-8, -8}, {8, 8}};
                for (int i = 0; i < responseOffsets.length; i++) {
                    int[] off = responseOffsets[i];
                    Mob responder = (i < 4) ? ModEntities.PILLAGER_WARRIOR.get().create(level) : ModEntities.PILLAGER_MARKSMAN.get().create(level);
                    if (responder != null) {
                        int rx = data.anchor.getX() + off[0];
                        int rz = data.anchor.getZ() + off[1];
                        int ry = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rx, rz);
                        responder.moveTo(rx + 0.5D, ry, rz + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(responder, level, level.getCurrentDifficultyAt(new BlockPos(rx, ry, rz)), MobSpawnType.EVENT, null);
                        if (level.addFreshEntity(responder)) {
                            setupMob(responder, progress.missionInstanceId(), regionX, regionZ, subX, subZ, progress);
                            responder.setPersistenceRequired();
                            responder.getPersistentData().putBoolean("preventActiveDespawn", true);
                            responder.goalSelector.addGoal(1, new MissionPostGuardGoal(responder, data.anchor, 24.0D, 1.15D));
                            data.responseSquadUuids.add(responder.getUUID());
                        }
                    }
                }

                RegionData regions = RegionData.get(level);
                if (regions != null) {
                    regions.setDirty();
                }

                ActiveCampaignMissionManager.notifyAttackingPlayersActionbar(level, regionX, regionZ,
                        "§c🚨 CAMP ALARM! Commander eliminated! Survive counter-assault (45s)!");
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
                return true;
            }
        } else if (progress.phaseIndex() == 1) {
            // Phase 2: Killing response squad members accelerates survival
            if (mob != null && data.responseSquadUuids.remove(mob.getUUID())) {
                data.responseKills++;
                if (data.responseKills >= data.targetResponseKills) {
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
        return false;
    }

    private void triggerVictory(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            OfficerHuntData data,
            ServerPlayer player
    ) {
        if (data != null && data.warDeskPositions != null && !data.warDeskPositions.isEmpty()) {
            for (BlockPos dp : data.warDeskPositions) {
                SalvageMachineryBlock.disarm(level, dp);
            }
        } else if (data != null && data.warDeskPos != null) {
            SalvageMachineryBlock.disarm(level, data.warDeskPos);
        }

        if (player != null) {
            player.sendSystemMessage(Component.literal("§a§l[WARFRONT] 🏆 Commander Encampment Neutralized! Tactical Headquarters Disarmed."));
        } else {
            ActiveCampaignMissionManager.notifyAttackingPlayers(level, regionX, regionZ,
                    "§a§l[WARFRONT] 🏆 Commander Encampment Neutralized! Tactical Headquarters Disarmed.");
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
        OfficerHuntData data = huntDataMap.remove(progress.missionInstanceId());
        if (data != null && data.warDeskPositions != null && !data.warDeskPositions.isEmpty()) {
            for (BlockPos dp : data.warDeskPositions) {
                SalvageMachineryBlock.disarm(level, dp);
            }
        } else if (data != null && data.warDeskPos != null) {
            SalvageMachineryBlock.disarm(level, data.warDeskPos);
        }

        for (UUID uuid : progress.trackedEntityUuids()) {
            Entity ent = level.getEntity(uuid);
            if (ent != null && ent.isAlive()) {
                ent.discard();
            }
            MissionEntityTracker.unregisterMissionMob(uuid);
        }
        progress.trackedEntityUuids().clear();

        if (!progress.isCompleted()) {
            MissionSiteSnapshotManager.restoreAndRemove(level, progress.missionInstanceId(), progress.trackedBlockPositions(), progress.missionSiteAnchor());
        }
    }
}
