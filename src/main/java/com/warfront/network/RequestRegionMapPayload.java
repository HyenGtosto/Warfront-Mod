package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.map.BiomeMapColors;
import com.warfront.map.MapViewType;
import com.warfront.region.RegionData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

public record RequestRegionMapPayload(MapViewType viewType) implements CustomPacketPayload {
    private static final Map<UUID, MapViewType> ACTIVE_MAP_SESSIONS = new ConcurrentHashMap<>();

    public RequestRegionMapPayload() {
        this(MapViewType.COMMAND);
    }

    public static final Type<RequestRegionMapPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "request_region_map"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRegionMapPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, p -> p.viewType().id(),
            id -> new RequestRegionMapPayload(MapViewType.byId(id)));

    public static void setActiveSession(ServerPlayer player, MapViewType viewType) {
        if (player != null && viewType != null) {
            ACTIVE_MAP_SESSIONS.put(player.getUUID(), viewType);
        }
    }

    public static void clearActiveSession(ServerPlayer player) {
        if (player != null) {
            ACTIVE_MAP_SESSIONS.remove(player.getUUID());
        }
    }

    public static MapViewType getActiveSession(ServerPlayer player) {
        if (player == null) return null;
        return ACTIVE_MAP_SESSIONS.get(player.getUUID());
    }

    public static void handle(RequestRegionMapPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            sendSnapshot(player, payload.viewType(), true);
        }
    }

    public static void sendSnapshot(ServerPlayer player, MapViewType viewType, boolean isExplicitRequest) {
        setActiveSession(player, viewType);
        buildAndSendSnapshot(player, viewType, isExplicitRequest);
    }

    private static void buildAndSendSnapshot(ServerPlayer player, MapViewType viewType, boolean isExplicitRequest) {
        ServerLevel level = player.serverLevel();
        com.warfront.region.generator.ProceduralRegionGenerator.getInstance().clearBiomeCache();
        RegionData regions = RegionData.get(level);

        int diameter = viewType.chunkDiameter();
        int centerChunkX = player.chunkPosition().x;
        int centerChunkZ = player.chunkPosition().z;
        int originChunkX = centerChunkX - diameter / 2;
        int originChunkZ = centerChunkZ - diameter / 2;

        List<RegionMapPayload.ChunkData> chunks = new ArrayList<>(diameter * diameter);
        List<RegionMapPayload.RegionMarkerData> markers = new ArrayList<>();

        int minRegionX = Math.floorDiv(originChunkX, 8);
        int maxRegionX = Math.floorDiv(originChunkX + diameter - 1, 8);
        int minRegionZ = Math.floorDiv(originChunkZ, 8);
        int maxRegionZ = Math.floorDiv(originChunkZ + diameter - 1, 8);

        Map<Long, CachedRegionData> regionCache = new HashMap<>();
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                RegionData.Region region = regions.regionAt(rx, rz);

                // Fog-of-war rule: DEBUG view is always visited; COMMAND view checks persistent visited state; SCOUT view is local
                boolean isVisited = !viewType.hasFogOfWar() || regions.isRegionVisited(rx, rz);

                RegionData.SubRegionState s00 = regions.subRegionAt(rx, rz, 0, 0);
                RegionData.SubRegionState s10 = regions.subRegionAt(rx, rz, 1, 0);
                RegionData.SubRegionState s01 = regions.subRegionAt(rx, rz, 0, 1);
                RegionData.SubRegionState s11 = regions.subRegionAt(rx, rz, 1, 1);

                regionCache.put(net.minecraft.world.level.ChunkPos.asLong(rx, rz),
                        new CachedRegionData(isVisited, s00, s10, s01, s11));

                // Fog-of-war rule: Base markers ONLY included if region is VISITED or in DEBUG view
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

                chunks.add(new RegionMapPayload.ChunkData(chunkX, chunkZ, biomeColor, subRegion.owner().id(), subRegion.underSiege(), cache.isVisited(), subRegion.clusterId()));
            }
        }

        List<RegionMapPayload.SiegeArrowData> siegeArrows = new ArrayList<>();
        for (RegionData.SiegeCampaign campaign : regions.getActiveSieges().values()) {
            if (campaign.attacker() == com.warfront.region.Faction.HUMANITY) {
                continue; // Only enemy AI arrows render
            }

            int trx = campaign.targetRegionX();
            int trz = campaign.targetRegionZ();

            for (RegionData.SourcePos src : campaign.sources()) {
                // Fog-of-war rule: Siege arrows ONLY included if DEBUG view OR if source or target region is VISITED by player
                boolean arrowPermitted = !viewType.hasFogOfWar() || regions.isRegionVisited(src.x(), src.z()) || regions.isRegionVisited(trx, trz);
                if (arrowPermitted) {
                    siegeArrows.add(new RegionMapPayload.SiegeArrowData(
                            src.x(), src.z(),
                            trx, trz,
                            campaign.attackValue(), campaign.encircled()
                    ));
                }
            }
        }

        List<String> logMessages = regions.getWarfrontLogs();

        PacketDistributor.sendToPlayer(player, new RegionMapPayload(centerChunkX, centerChunkZ, chunks, markers, siegeArrows, logMessages, isExplicitRequest, viewType));
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

    /**
     * Broadcasts live updates when strategic events occur (attack starts, ends, timeouts).
     * Sends snapshot tailored strictly to each active player's current MapViewType.
     */
    public static void notifyActiveMapTerminals(ServerLevel level) {
        if (level == null || level.getServer() == null) return;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            MapViewType activeType = getActiveSession(player);
            if (activeType != null) {
                buildAndSendSnapshot(player, activeType, false);
            }
        }
    }

    @Override
    public Type<RequestRegionMapPayload> type() {
        return TYPE;
    }
}
