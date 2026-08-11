package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.map.BiomeMapColors;
import com.warfront.region.RegionData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestRegionMapPayload(boolean isCommandTerminalMap) implements CustomPacketPayload {
    public static final int MAP_SIZE_BLOCKS = 1024;
    public static final int MAP_CHUNK_DIAMETER = MAP_SIZE_BLOCKS / 16;
    public static final int COMMAND_TERMINAL_CHUNK_DIAMETER = MAP_CHUNK_DIAMETER * 3; // 9x Area = 3x Diameter (192 chunks)

    public RequestRegionMapPayload() {
        this(false);
    }

    public static final Type<RequestRegionMapPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "request_region_map"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRegionMapPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, RequestRegionMapPayload::isCommandTerminalMap,
            RequestRegionMapPayload::new);

    public static void handle(RequestRegionMapPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        if (payload.isCommandTerminalMap()) {
            send9xCommandTerminalMapSnapshotToPlayer(player);
        } else {
            sendMapSnapshotToPlayer(player, true);
        }
    }

    public static void sendMapSnapshotToPlayer(ServerPlayer player, boolean isExplicitRequest) {
        buildAndSendSnapshot(player, MAP_CHUNK_DIAMETER, isExplicitRequest, false, false);
    }

    public static void send9xCommandTerminalMapSnapshotToPlayer(ServerPlayer player) {
        send9xCommandTerminalMapSnapshotToPlayer(player, true);
    }

    public static void send9xCommandTerminalMapSnapshotToPlayer(ServerPlayer player, boolean isExplicitRequest) {
        buildAndSendSnapshot(player, COMMAND_TERMINAL_CHUNK_DIAMETER, isExplicitRequest, true, false);
    }

    public static void send3kTestingMapSnapshotToPlayer(ServerPlayer player) {
        buildAndSendSnapshot(player, COMMAND_TERMINAL_CHUNK_DIAMETER, true, true, true);
    }

    private static void buildAndSendSnapshot(ServerPlayer player, int diameter, boolean isExplicitRequest, boolean isCommandTerminalMap, boolean isDebugMap) {
        ServerLevel level = player.serverLevel();
        com.warfront.region.generator.ProceduralRegionGenerator.getInstance().clearBiomeCache();
        RegionData regions = RegionData.get(level);
        int centerChunkX = player.chunkPosition().x;
        int centerChunkZ = player.chunkPosition().z;
        int originChunkX = centerChunkX - diameter / 2;
        int originChunkZ = centerChunkZ - diameter / 2;

        List<RegionMapPayload.ChunkData> chunks = new ArrayList<>(diameter * diameter);
        List<RegionMapPayload.RegionMarkerData> markers = new ArrayList<>();

        // Pre-compute region & sub-region data for the viewport bounds
        int minRegionX = Math.floorDiv(originChunkX, 8);
        int maxRegionX = Math.floorDiv(originChunkX + diameter - 1, 8);
        int minRegionZ = Math.floorDiv(originChunkZ, 8);
        int maxRegionZ = Math.floorDiv(originChunkZ + diameter - 1, 8);

        Map<Long, CachedRegionData> regionCache = new HashMap<>();
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                RegionData.Region region = regions.regionAt(rx, rz);
                boolean isVisited = isDebugMap || !isCommandTerminalMap || regions.isRegionVisited(rx, rz);

                RegionData.SubRegionState s00 = regions.subRegionAt(rx, rz, 0, 0);
                RegionData.SubRegionState s10 = regions.subRegionAt(rx, rz, 1, 0);
                RegionData.SubRegionState s01 = regions.subRegionAt(rx, rz, 0, 1);
                RegionData.SubRegionState s11 = regions.subRegionAt(rx, rz, 1, 1);

                regionCache.put(net.minecraft.world.level.ChunkPos.asLong(rx, rz),
                        new CachedRegionData(isVisited, s00, s10, s01, s11));

                if (isVisited && region.baseType() != com.warfront.region.BaseType.NONE) {
                    markers.add(new RegionMapPayload.RegionMarkerData(rx, rz, region.owner().id(), region.baseType().id()));
                }
            }
        }

        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        int seaLevel = level.getSeaLevel();

        for (int chunkZ = originChunkZ; chunkZ < originChunkZ + diameter; chunkZ++) {
            int rz = Math.floorDiv(chunkZ, 8);
            int subZ = Math.floorMod(chunkZ, 8) >= 4 ? 1 : 0;
            int blockZ = chunkZ * 16 + 8;

            for (int chunkX = originChunkX; chunkX < originChunkX + diameter; chunkX++) {
                int rx = Math.floorDiv(chunkX, 8);
                int subX = Math.floorMod(chunkX, 8) >= 4 ? 1 : 0;
                int blockX = chunkX * 16 + 8;

                samplePos.set(blockX, seaLevel, blockZ);
                int biomeColor = BiomeMapColors.colorFor(level.getBiome(samplePos));

                CachedRegionData cache = regionCache.get(net.minecraft.world.level.ChunkPos.asLong(rx, rz));
                RegionData.SubRegionState subRegion = cache.getSubRegion(subX, subZ);

                chunks.add(new RegionMapPayload.ChunkData(chunkX, chunkZ, biomeColor, subRegion.owner().id(), subRegion.underSiege(), cache.isVisited()));
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

        PacketDistributor.sendToPlayer(player, new RegionMapPayload(centerChunkX, centerChunkZ, chunks, markers, siegeArrows, logMessages, isExplicitRequest, isCommandTerminalMap, isDebugMap));
    }

    private record CachedRegionData(boolean isVisited,
            RegionData.SubRegionState s00, RegionData.SubRegionState s10,
            RegionData.SubRegionState s01, RegionData.SubRegionState s11) {
        public RegionData.SubRegionState getSubRegion(int subX, int subZ) {
            if (subX == 0 && subZ == 0) return s00;
            if (subX == 1 && subZ == 0) return s10;
            if (subX == 0 && subZ == 1) return s01;
            return s11;
        }
    }

    public static void sendMapSnapshotToAllPlayers(ServerLevel level) {
        if (level.getServer() != null) {
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                send9xCommandTerminalMapSnapshotToPlayer(player, false);
                sendMapSnapshotToPlayer(player, false);
            }
        }
    }

    @Override
    public Type<RequestRegionMapPayload> type() {
        return TYPE;
    }
}
