package com.warfront.network;

import com.warfront.map.BiomeMapColors;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Isolated stand-alone Debug Map Handler.
 * Used exclusively by TestingMapTerminalItem for 3k map generation testing.
 * Can be cleanly deleted when testing concludes without touching production terminal code.
 */
public final class DebugMapHandler {
    private static final int DEBUG_CHUNK_DIAMETER = 192;

    private DebugMapHandler() {
    }

    public static void send3kDebugMapSnapshot(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        com.warfront.region.generator.ProceduralRegionGenerator.getInstance().clearBiomeCache();
        RegionData regions = RegionData.get(level);

        int centerChunkX = player.chunkPosition().x;
        int centerChunkZ = player.chunkPosition().z;
        int originChunkX = centerChunkX - DEBUG_CHUNK_DIAMETER / 2;
        int originChunkZ = centerChunkZ - DEBUG_CHUNK_DIAMETER / 2;

        List<RegionMapPayload.ChunkData> chunks = new ArrayList<>(DEBUG_CHUNK_DIAMETER * DEBUG_CHUNK_DIAMETER);
        List<RegionMapPayload.RegionMarkerData> markers = new ArrayList<>();

        int minRegionX = Math.floorDiv(originChunkX, 8);
        int maxRegionX = Math.floorDiv(originChunkX + DEBUG_CHUNK_DIAMETER - 1, 8);
        int minRegionZ = Math.floorDiv(originChunkZ, 8);
        int maxRegionZ = Math.floorDiv(originChunkZ + DEBUG_CHUNK_DIAMETER - 1, 8);

        Map<Long, CachedDebugRegion> regionCache = new HashMap<>();
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                RegionData.Region region = regions.regionAt(rx, rz);
                RegionData.SubRegionState s00 = regions.subRegionAt(rx, rz, 0, 0);
                RegionData.SubRegionState s10 = regions.subRegionAt(rx, rz, 1, 0);
                RegionData.SubRegionState s01 = regions.subRegionAt(rx, rz, 0, 1);
                RegionData.SubRegionState s11 = regions.subRegionAt(rx, rz, 1, 1);

                regionCache.put(net.minecraft.world.level.ChunkPos.asLong(rx, rz),
                        new CachedDebugRegion(s00, s10, s01, s11));

                if (region.baseType() != BaseType.NONE) {
                    markers.add(new RegionMapPayload.RegionMarkerData(rx, rz, region.owner().id(), region.baseType().id()));
                }
            }
        }

        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        int seaLevel = level.getSeaLevel();

        for (int chunkZ = originChunkZ; chunkZ < originChunkZ + DEBUG_CHUNK_DIAMETER; chunkZ++) {
            int rz = Math.floorDiv(chunkZ, 8);
            int subZ = Math.floorMod(chunkZ, 8) >= 4 ? 1 : 0;
            int blockZ = chunkZ * 16 + 8;

            for (int chunkX = originChunkX; chunkX < originChunkX + DEBUG_CHUNK_DIAMETER; chunkX++) {
                int rx = Math.floorDiv(chunkX, 8);
                int subX = Math.floorMod(chunkX, 8) >= 4 ? 1 : 0;
                int blockX = chunkX * 16 + 8;

                samplePos.set(blockX, seaLevel, blockZ);
                int biomeColor = BiomeMapColors.colorFor(level.getBiome(samplePos));

                CachedDebugRegion cache = regionCache.get(net.minecraft.world.level.ChunkPos.asLong(rx, rz));
                RegionData.SubRegionState subRegion = cache != null ? cache.getSubRegion(subX, subZ) :
                        new RegionData.SubRegionState(Faction.UNCLAIMED, 0.0F, false);

                // Force isVisited = true for ALL chunks on the Debug Map!
                chunks.add(new RegionMapPayload.ChunkData(chunkX, chunkZ, biomeColor, subRegion.owner().id(), subRegion.underSiege(), true));
            }
        }

        List<RegionMapPayload.SiegeArrowData> siegeArrows = new ArrayList<>();
        for (RegionData.SiegeCampaign campaign : regions.getActiveSieges().values()) {
            if (campaign.attacker() == com.warfront.region.Faction.HUMANITY) {
                continue; // Player attack arrows removed - only enemy AI arrows render
            }
            for (RegionData.SourcePos src : campaign.sources()) {
                siegeArrows.add(new RegionMapPayload.SiegeArrowData(
                        src.x(), src.z(),
                        campaign.targetRegionX(), campaign.targetRegionZ(),
                        campaign.attackValue(), campaign.encircled()
                ));
            }
        }

        List<String> logMessages = regions.getWarfrontLogs();

        // Send payload with isExplicitRequest = true, isCommandTerminalMap = true, isDebugMap = true
        PacketDistributor.sendToPlayer(player, new RegionMapPayload(
                centerChunkX, centerChunkZ, chunks, markers, siegeArrows, logMessages, true, true, true));
    }

    private record CachedDebugRegion(
            RegionData.SubRegionState s00, RegionData.SubRegionState s10,
            RegionData.SubRegionState s01, RegionData.SubRegionState s11) {
        public RegionData.SubRegionState getSubRegion(int subX, int subZ) {
            if (subX == 0 && subZ == 0) return s00;
            if (subX == 1 && subZ == 0) return s10;
            if (subX == 0 && subZ == 1) return s01;
            return s11;
        }
    }
}
