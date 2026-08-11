package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.region.RegionData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestRegionDetailsPayload(int regionX, int regionZ, int subX, int subZ, boolean isCommandTerminalMap, boolean isDebugMap) implements CustomPacketPayload {
    public RequestRegionDetailsPayload(int regionX, int regionZ, int subX, int subZ) {
        this(regionX, regionZ, subX, subZ, false, false);
    }

    public RequestRegionDetailsPayload(int regionX, int regionZ, int subX, int subZ, boolean isCommandTerminalMap) {
        this(regionX, regionZ, subX, subZ, isCommandTerminalMap, false);
    }

    public static final Type<RequestRegionDetailsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "request_region_details"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRegionDetailsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RequestRegionDetailsPayload::regionX,
            ByteBufCodecs.VAR_INT, RequestRegionDetailsPayload::regionZ,
            ByteBufCodecs.VAR_INT, RequestRegionDetailsPayload::subX,
            ByteBufCodecs.VAR_INT, RequestRegionDetailsPayload::subZ,
            ByteBufCodecs.BOOL, RequestRegionDetailsPayload::isCommandTerminalMap,
            ByteBufCodecs.BOOL, RequestRegionDetailsPayload::isDebugMap,
            RequestRegionDetailsPayload::new);

    public static void handle(RequestRegionDetailsPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        if (!payload.isDebugMap() && !isInsideMapSnapshot(player, payload.regionX(), payload.regionZ())) {
            Warfront.LOGGER.warn("Rejected region detail request outside the map snapshot: {}, {}", payload.regionX(), payload.regionZ());
            return;
        }

        RegionData regions = RegionData.get(player.serverLevel());
        boolean isVisited = payload.isDebugMap() || !payload.isCommandTerminalMap() || regions.isRegionVisited(payload.regionX(), payload.regionZ());

        if (!isVisited) {
            PacketDistributor.sendToPlayer(player, new RegionDetailsPayload(
                    payload.regionX(), payload.regionZ(), payload.subX(), payload.subZ(),
                    com.warfront.region.Faction.UNCLAIMED.id(), 0.0F, 0.0F, com.warfront.region.BaseType.NONE.id(), false, false));
        } else {
            RegionData.Region region = regions.regionAt(payload.regionX(), payload.regionZ());
            RegionData.SubRegionState subState = regions.subRegionAt(payload.regionX(), payload.regionZ(), payload.subX(), payload.subZ());
            float effectiveResistance = regions.calculateEffectiveResistance(payload.regionX(), payload.regionZ());
            float effectiveStability = regions.calculateEffectiveStability(payload.regionX(), payload.regionZ());

            long remainingTicks = 0L;
            RegionData.SiegeCampaign siege = regions.getActiveSieges().get(net.minecraft.world.level.ChunkPos.asLong(payload.regionX(), payload.regionZ()));
            if (siege != null) {
                long elapsed = player.serverLevel().getGameTime() - siege.startTick();
                remainingTicks = Math.max(0L, siege.durationTicks() - elapsed);
            }

            int dominoThreshold = regions.calculateDominoThreshold(payload.regionX(), payload.regionZ());

            int reachableMask = regions.computeReachableMask(payload.regionX(), payload.regionZ());
            int conqueredMask = regions.computeConqueredMask(payload.regionX(), payload.regionZ());
            boolean regionReachable = regions.isRegionReachable(payload.regionX(), payload.regionZ());
            // Pass back the current campaign mask so the client can restore button state after reopening
            int existingSiegeMask = (siege != null) ? siege.activeSubRegionsMask() : 0;

            Warfront.LOGGER.debug("Sending details for sub-region ({}, {}, sub: {}, {})", region.x(), region.z(), payload.subX(), payload.subZ());
            PacketDistributor.sendToPlayer(player, new RegionDetailsPayload(
                    region.x(), region.z(), payload.subX(), payload.subZ(),
                    subState.owner().id(), effectiveStability, effectiveResistance,
                    region.baseType().id(), subState.underSiege(), true,
                    remainingTicks, dominoThreshold, reachableMask, regionReachable, existingSiegeMask, conqueredMask));
        }
    }

    private static boolean isInsideMapSnapshot(ServerPlayer player, int regionX, int regionZ) {
        int chunksPerRegion = RegionData.REGION_SIZE_BLOCKS / 16;
        int mapOriginX = player.chunkPosition().x - RequestRegionMapPayload.COMMAND_TERMINAL_CHUNK_DIAMETER / 2;
        int mapOriginZ = player.chunkPosition().z - RequestRegionMapPayload.COMMAND_TERMINAL_CHUNK_DIAMETER / 2;
        int regionStartX = regionX * chunksPerRegion;
        int regionStartZ = regionZ * chunksPerRegion;
        return regionStartX < mapOriginX + RequestRegionMapPayload.COMMAND_TERMINAL_CHUNK_DIAMETER
                && regionStartX + chunksPerRegion > mapOriginX
                && regionStartZ < mapOriginZ + RequestRegionMapPayload.COMMAND_TERMINAL_CHUNK_DIAMETER
                && regionStartZ + chunksPerRegion > mapOriginZ;
    }

    @Override
    public Type<RequestRegionDetailsPayload> type() {
        return TYPE;
    }
}
