package com.warfront.event;

import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.region.SubRegionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Event listener intercepting block break events to attribute damage/destruction of
 * mission objective blocks (Command Cores, Observation Relays, Supply Crates, etc.).
 */
public final class MissionBlockEventHandler {

    private MissionBlockEventHandler() {
    }

    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }

        if (!(event.getPlayer() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        BlockPos pos = event.getPos();
        BlockState state = event.getState();

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        SubRegionPos subPos = SubRegionPos.fromChunk(chunkX, chunkZ);

        int rx = subPos.regionX();
        int rz = subPos.regionZ();
        int sx = subPos.subX();
        int sz = subPos.subZ();

        if (ActiveCampaignMissionManager.hasActiveMission(rx, rz, sx, sz)) {
            ActiveCampaignMissionManager.onBlockBroken(level, rx, rz, sx, sz, pos, state, player);
        }
    }

    public static void onExplosionDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        for (BlockPos pos : event.getAffectedBlocks()) {
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            SubRegionPos subPos = SubRegionPos.fromChunk(chunkX, chunkZ);

            int rx = subPos.regionX();
            int rz = subPos.regionZ();
            int sx = subPos.subX();
            int sz = subPos.subZ();

            if (ActiveCampaignMissionManager.hasActiveMission(rx, rz, sx, sz)) {
                BlockState state = level.getBlockState(pos);
                ActiveCampaignMissionManager.onBlockBroken(level, rx, rz, sx, sz, pos, state, null);
            }
        }
    }
}
