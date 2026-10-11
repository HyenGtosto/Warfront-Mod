package com.warfront.mission.hard;

import com.warfront.block.SabotageTargetBlock;
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
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hard-tier mission handler for {@link com.warfront.mission.MissionType#STRONGPOINT_ASSAULT}.
 *
 * Spawns a 20x20 WWII-style concrete & deepslate pillbox redoubt with embrasures,
 * angled entrance blast chicane, low-profile roof, 2 volatile shield relays, and 1 heavy vault.
 *
 * Dual-path victory condition:
 * - Sabotage Path: Destroy both volatile Defense Shield Relays (0/2).
 * - Demolition Path: Destroy >= 50% of total physical pillbox blocks.
 *
 * On victory, the Heavy Machinery Vault disarms, allowing harvest of Pristine Redoubt Plates
 * and Heavy Pivot Bearings.
 */
public final class StrongpointAssaultMissionHandler implements MissionObjectiveHandler {

    private static final StrongpointAssaultMissionHandler INSTANCE = new StrongpointAssaultMissionHandler();

    // Track active site data per mission instance
    private final ConcurrentHashMap<UUID, StrongpointMissionData> siteDataMap = new ConcurrentHashMap<>();

    public record StrongpointMissionData(
            BlockPos anchor,
            BlockPos vaultPos,
            List<BlockPos> shieldRelays,
            int totalStructureBlocks,
            int initialRelayCount
    ) {}

    private StrongpointAssaultMissionHandler() {
    }

    public static StrongpointAssaultMissionHandler getInstance() {
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

        // If pillbox has already been constructed, do not build again
        if (progress.missionSiteAnchor() != null && !progress.trackedBlockPositions().isEmpty()) {
            return;
        }

        RegionData regions = RegionData.get(level);
        long missionSeed = (regions != null) ? regions.calculateMissionSeed(regionX, regionZ) : 0L;

        // 1. Resolve deterministic dry-land anchor
        BlockPos anchor = MissionSiteAnchorResolver.resolveDryLandAnchor(level, regionX, regionZ, subX, subZ, missionSeed);
        progress.setMissionSiteAnchor(anchor);

        UUID instanceId = progress.missionInstanceId();
        MissionSiteSnapshot snapshot = MissionSiteSnapshotManager.getOrCreate(instanceId, regionX, regionZ, subX, subZ);

        // 2. Build 20x20 WWII Pillbox structure
        TemporaryStructureBuilder.StrongpointResult result = TemporaryStructureBuilder.buildStrongpointPillbox(level, anchor, snapshot);

        progress.trackedBlockPositions().clear();
        progress.trackedBlockPositions().addAll(result.structureBlocks());

        int totalBlocks = result.structureBlocks().size();
        int targetDestroyed = (int) Math.ceil(totalBlocks * 0.50); // 50% demolition target
        progress.setTargetProgress(targetDestroyed);
        progress.setCurrentProgress(0);

        StrongpointMissionData data = new StrongpointMissionData(
                anchor,
                result.vaultPos(),
                new ArrayList<>(result.shieldRelayPositions()),
                totalBlocks,
                result.shieldRelayPositions().size()
        );
        siteDataMap.put(instanceId, data);

        // 3. Spawn garrison defenders
        // 2 Armored Elites guarding the entrance chicane
        int[][] chicaneOffsets = {{-1, 8}, {1, 8}};
        for (int[] off : chicaneOffsets) {
            Mob guard = ModEntities.PILLAGER_ARMORED_ELITE.get().create(level);
            if (guard != null) {
                guard.moveTo(anchor.getX() + off[0] + 0.5D, anchor.getY() + 1.0D, anchor.getZ() + off[1] + 0.5D, 0.0F, 0.0F);
                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(guard, level, level.getCurrentDifficultyAt(guard.blockPosition()), MobSpawnType.EVENT, null);
                if (level.addFreshEntity(guard)) {
                    setupMob(guard, instanceId, regionX, regionZ, subX, subZ, progress);
                }
            }
        }

        // 3 Marksmen snipers manning embrasures
        int[][] sniperOffsets = {{0, -7}, {-7, 0}, {7, 0}};
        for (int[] off : sniperOffsets) {
            Mob sniper = ModEntities.PILLAGER_MARKSMAN.get().create(level);
            if (sniper != null) {
                sniper.moveTo(anchor.getX() + off[0] + 0.5D, anchor.getY() + 1.0D, anchor.getZ() + off[1] + 0.5D, 0.0F, 0.0F);
                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(sniper, level, level.getCurrentDifficultyAt(sniper.blockPosition()), MobSpawnType.EVENT, null);
                if (level.addFreshEntity(sniper)) {
                    setupMob(sniper, instanceId, regionX, regionZ, subX, subZ, progress);
                }
            }
        }

        // 2 Warriors inside the bunker
        int[][] warriorOffsets = {{-3, 0}, {3, 0}};
        for (int[] off : warriorOffsets) {
            Mob warrior = ModEntities.PILLAGER_WARRIOR.get().create(level);
            if (warrior != null) {
                warrior.moveTo(anchor.getX() + off[0] + 0.5D, anchor.getY() + 1.0D, anchor.getZ() + off[1] + 0.5D, 0.0F, 0.0F);
                net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(warrior, level, level.getCurrentDifficultyAt(warrior.blockPosition()), MobSpawnType.EVENT, null);
                if (level.addFreshEntity(warrior)) {
                    setupMob(warrior, instanceId, regionX, regionZ, subX, subZ, progress);
                }
            }
        }

        if (regions != null) {
            regions.setDirty();
        }
        ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
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

        StrongpointMissionData data = siteDataMap.get(progress.missionInstanceId());

        // Check if broken block was a Sabotage Shield Relay
        if (data != null && data.shieldRelays.remove(pos)) {
            level.playSound(null, pos, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, 0.8F, 1.8F);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 20, 0.4D, 0.4D, 0.4D, 0.1D);

            int remainingRelays = data.shieldRelays.size();
            int relaysDestroyed = data.initialRelayCount - remainingRelays;

            if (player != null) {
                player.displayClientMessage(
                        Component.literal(String.format("§c[Warfront] ⚡ Shield Relay Sabotaged! [%d/%d]", relaysDestroyed, data.initialRelayCount)),
                        true
                );
            }

            // Both shield relays destroyed -> Complete victory via sabotage path!
            if (remainingRelays <= 0) {
                triggerVictory(level, regionX, regionZ, subX, subZ, progress, data, player, "⚡ Pillbox Shield Relays Sabotaged!");
                return true;
            }
        }

        // Track demolition progress of structure blocks
        if (progress.trackedBlockPositions().remove(pos)) {
            progress.incrementProgress();
            RegionData regions = RegionData.get(level);
            if (regions != null) {
                regions.setDirty();
            }

            // Check if 50% demolition goal reached
            if (progress.currentProgress() >= progress.targetProgress()) {
                triggerVictory(level, regionX, regionZ, subX, subZ, progress, data, player, "💥 Pillbox Fortification Demolished (50%)!");
                return true;
            } else {
                ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
            }
            return true;
        }

        return false;
    }

    private void triggerVictory(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress,
            StrongpointMissionData data,
            ServerPlayer player,
            String victoryBanner
    ) {
        if (data != null && data.vaultPos != null) {
            SalvageMachineryBlock.disarm(level, data.vaultPos);
        }

        if (player != null) {
            player.sendSystemMessage(Component.literal("§a§l[WARFRONT] " + victoryBanner + " Heavy Machinery Vault disarmed!"));
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
        StrongpointMissionData data = siteDataMap.remove(progress.missionInstanceId());
        if (data != null && data.vaultPos != null) {
            // Disarm vault block upon cleanup/completion so players can mine it post-victory
            SalvageMachineryBlock.disarm(level, data.vaultPos);
        }

        // If mission failed without completion, restore terrain
        if (!progress.isCompleted()) {
            MissionSiteSnapshotManager.restoreAndRemove(level, progress.missionInstanceId());
        }
    }
}
