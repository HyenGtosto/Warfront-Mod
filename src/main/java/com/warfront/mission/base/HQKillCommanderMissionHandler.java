package com.warfront.mission.base;

import com.warfront.block.SalvageMachineryBlock;
import com.warfront.entity.PillagerCommanderEntity;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.mission.MissionObjectiveHandler;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.base.BasePlacementManager;
import com.warfront.region.base.PersistentBaseProtectionManager;
import com.warfront.region.base.PillagerHeadquartersGenerator;
import com.warfront.region.base.garrison.BaseGarrisonData;
import com.warfront.region.base.garrison.BaseGarrisonManager;
import com.warfront.region.base.garrison.BaseGarrisonState;
import com.warfront.region.base.garrison.GarrisonSlot;
import com.warfront.region.base.garrison.PillagerHQGarrisonDefinition;
import com.warfront.region.generator.ProceduralRegionGenerator;
import com.warfront.spawn.MissionEntityTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Objective handler for {@link com.warfront.mission.MissionType#BASE_KILL_COMMANDER}.
 *
 * Directs the assault on the Garrison Commander's Keep inside the Pillager Headquarters:
 * - Initializes Headquarters placement and repairs the structure.
 * - Activates and enlists the Wing A garrison (Commander, Tower Cannoneer, Ironclad Bulwark,
 *   Office Elite, Stair Guard, Map Rapidbows, Stables Scouts) and perimeter wall sentries.
 * - Tracks the Garrison Commander located in the 2nd-floor executive office.
 * - On victory, disarms the Commander's War Desks, grants victory rewards, and captures the subregion.
 */
public final class HQKillCommanderMissionHandler implements MissionObjectiveHandler {

    private static final HQKillCommanderMissionHandler INSTANCE = new HQKillCommanderMissionHandler();

    private HQKillCommanderMissionHandler() {
    }

    public static HQKillCommanderMissionHandler getInstance() {
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
            initHeadquartersMission(level, regionX, regionZ, subX, subZ, progress);
        }
    }

    private void initHeadquartersMission(
            ServerLevel level,
            int regionX, int regionZ,
            int subX, int subZ,
            ActiveCampaignMissionManager.ActiveSubRegionProgress progress
    ) {
        // 1. Ensure Headquarters base is physically placed in world
        RegionData regions = RegionData.get(level);
        if (regions != null && !regions.isBasePlaced(regionX, regionZ)) {
            BasePlacementManager.tryPlaceBase(level, regionX, regionZ, false);
        }

        // 2. Resolve anchor position
        RegionData.RegionState state = (regions != null) ? regions.getSavedRegionState(regionX, regionZ) : null;
        BlockPos anchor = (state != null) ? state.baseAnchor() : null;

        if (anchor == null) {
            anchor = ProceduralRegionGenerator.getInstance()
                    .findPhysicalBaseAnchor(level, level.getSeed(), regionX, regionZ, BaseType.HEADQUARTERS)
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

        RegionData.RegionState regState = (state != null) ? state : new RegionData.RegionState(
                progress.targetFaction(), 100.0F, 100.0F, BaseType.HEADQUARTERS, 0L, anchor, true);

        // Instantly repair base structure blocks prior to combat
        PersistentBaseProtectionManager.instantRepairBase(level, regionX, regionZ, anchor, regState);

        // Rearm Commander War Desks inside the Keep
        rearmWarDesks(level, anchor);

        // 3. Prepare garrison (heals living defenders, instant respawns missing Wing A + Shell guards)
        BaseGarrisonManager.prepareAndEnlistMissionGarrison(
                level, regionX, regionZ, subX, subZ, anchor, instanceId, progress);

        // 4. Locate Commander UUID from Wing A slot
        BaseGarrisonData gData = BaseGarrisonData.get(level);
        BaseGarrisonState gState = (gData != null) ? gData.getGarrison(regionX, regionZ) : null;
        UUID commanderUuid = null;

        if (gState != null) {
            for (GarrisonSlot slot : gState.getSlots()) {
                if ("WING_A_COMMANDER".equals(slot.roleId())) {
                    commanderUuid = slot.getEntityUuid();
                    break;
                }
            }
        }

        progress.trackedEntityUuids().clear();
        if (commanderUuid != null) {
            progress.trackedEntityUuids().add(commanderUuid);
        }

        progress.setTargetProgress(1);
        progress.setCurrentProgress(0);
        progress.setPhaseIndex(1);

        if (regions != null) {
            regions.setDirty();
        }

        ActiveCampaignMissionManager.broadcastHudUpdate(level, regionX, regionZ, subX, subZ, progress);
    }

    private void rearmWarDesks(ServerLevel level, BlockPos anchor) {
        if (level == null || anchor == null) return;
        for (BlockPos deskPos : getKeepWarDeskPositions(anchor)) {
            if (level.hasChunkAt(deskPos)) {
                SalvageMachineryBlock.rearm(level, deskPos);
            }
        }
    }

    private void disarmWarDesks(ServerLevel level, BlockPos anchor) {
        if (level == null || anchor == null) return;
        for (BlockPos deskPos : getKeepWarDeskPositions(anchor)) {
            if (level.hasChunkAt(deskPos)) {
                SalvageMachineryBlock.disarm(level, deskPos);
            }
        }
    }

    public static List<BlockPos> getKeepWarDeskPositions(BlockPos anchor) {
        boolean isEastWest = PillagerHeadquartersGenerator.isEastWest(anchor);
        BlockPos shellBoxMin = PillagerHeadquartersGenerator.getShellBoxMin(anchor);
        Direction targetSlotAFacing = isEastWest ? Direction.SOUTH : Direction.EAST;
        Rotation slotARot = PillagerHeadquartersGenerator.getRotationBetween(
                PillagerHeadquartersGenerator.KEEP_INITIAL_FACING, targetSlotAFacing);
        BlockPos slotABoxMin = PillagerHeadquartersGenerator.getSlotBoxMin(
                shellBoxMin, isEastWest, true, PillagerHQGarrisonDefinition.KEEP_SIZE, slotARot);
        BlockPos slotAOrigin = PillagerHeadquartersGenerator.getRotatedOrigin(
                slotABoxMin, PillagerHQGarrisonDefinition.KEEP_SIZE, slotARot);

        List<BlockPos> desks = new ArrayList<>();
        // War Desks in Keep are at local X=[6..8], Y=9, Z=[11..12]
        for (int lx = 6; lx <= 8; lx++) {
            for (int lz = 11; lz <= 12; lz++) {
                BlockPos local = new BlockPos(lx, 9, lz);
                BlockPos world = slotAOrigin.offset(StructureTemplate.transform(local, Mirror.NONE, slotARot, BlockPos.ZERO));
                desks.add(world.immutable());
            }
        }
        return desks;
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

        UUID killedUuid = (mob != null) ? mob.getUUID() : null;
        boolean isCommander = (killedUuid != null && progress.trackedEntityUuids().contains(killedUuid))
                || mob instanceof PillagerCommanderEntity
                || "WING_A_COMMANDER".equals(mobRoleName);

        if (isCommander) {
            progress.setCurrentProgress(1);

            BlockPos anchor = progress.missionSiteAnchor();
            disarmWarDesks(level, anchor);

            // Victory fanfare
            if (mob != null) {
                level.playSound(null, mob.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
                level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, mob.getX(), mob.getY() + 1.0D, mob.getZ(), 40, 0.5D, 0.5D, 0.5D, 0.1D);
            }

            // Dual-language localized victory notification
            for (ServerPlayer player : level.players()) {
                player.sendSystemMessage(Component.translatable("chat.warfront.mission.hq_commander_neutralized"));
            }

            ActiveCampaignMissionManager.completeMission(level, regionX, regionZ, subX, subZ, progress);
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
            net.minecraft.world.level.block.state.BlockState state,
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
        BlockPos anchor = progress.missionSiteAnchor();

        if (progress.isCompleted()) {
            if (anchor != null) {
                disarmWarDesks(level, anchor);
            }

            // Immediately disperse all surviving Wing A enlisted defenders with POOF
            for (UUID uuid : new ArrayList<>(progress.trackedEntityUuids())) {
                Entity e = level.getEntity(uuid);
                if (e instanceof Mob m && m.isAlive()) {
                    level.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + 0.5D, m.getZ(), 12, 0.3D, 0.5D, 0.3D, 0.05D);
                    level.playSound(null, m.blockPosition(), SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 0.7F, 1.2F);
                    m.discard();
                }
                MissionEntityTracker.unregisterMissionMob(uuid);
            }

            // Clear Wing A garrison slot UUIDs in BaseGarrisonData so defeated Keep guards don't respawn
            BaseGarrisonData gData = BaseGarrisonData.get(level);
            if (gData != null) {
                BaseGarrisonState gState = gData.getGarrison(regionX, regionZ);
                if (gState != null) {
                    for (GarrisonSlot slot : gState.getSlots()) {
                        if (slot.roleId().startsWith("WING_A_")) {
                            UUID gUuid = slot.getEntityUuid();
                            if (gUuid != null) {
                                Entity gEntity = level.getEntity(gUuid);
                                if (gEntity instanceof Mob gMob && gMob.isAlive()) {
                                    level.sendParticles(ParticleTypes.POOF, gMob.getX(), gMob.getY() + 0.5D, gMob.getZ(), 12, 0.3D, 0.5D, 0.3D, 0.05D);
                                    gMob.discard();
                                }
                                slot.setEntityUuid(null);
                            }
                        }
                    }
                    gData.setDirty();
                }
            }
        } else {
            progress.trackedEntityUuids().clear();
        }
    }
}
