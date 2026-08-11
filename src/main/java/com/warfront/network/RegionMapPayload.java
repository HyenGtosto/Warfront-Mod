package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.client.RegionMapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RegionMapPayload(
        int centerChunkX, int centerChunkZ,
        java.util.List<ChunkData> chunks,
        java.util.List<RegionMarkerData> markers,
        java.util.List<SiegeArrowData> siegeArrows,
        java.util.List<String> logMessages,
        boolean isExplicitRequest,
        boolean isCommandTerminalMap,
        boolean isDebugMap
) implements CustomPacketPayload {

    public RegionMapPayload(int centerChunkX, int centerChunkZ, java.util.List<ChunkData> chunks, java.util.List<RegionMarkerData> markers, java.util.List<SiegeArrowData> siegeArrows, java.util.List<String> logMessages, boolean isExplicitRequest, boolean isCommandTerminalMap) {
        this(centerChunkX, centerChunkZ, chunks, markers, siegeArrows, logMessages, isExplicitRequest, isCommandTerminalMap, false);
    }

    public static final Type<RegionMapPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "region_map"));

    private static final StreamCodec<RegistryFriendlyByteBuf, ChunkData> CHUNK_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChunkData::chunkX,
            ByteBufCodecs.VAR_INT, ChunkData::chunkZ,
            ByteBufCodecs.INT, ChunkData::biomeColor,
            ByteBufCodecs.VAR_INT, ChunkData::factionId,
            ByteBufCodecs.BOOL, ChunkData::underSiege,
            ByteBufCodecs.BOOL, ChunkData::isVisited,
            ChunkData::new);

    private static final StreamCodec<RegistryFriendlyByteBuf, java.util.List<ChunkData>> CHUNKS_CODEC = ByteBufCodecs
            .<RegistryFriendlyByteBuf, ChunkData>list()
            .apply(CHUNK_CODEC);

    private static final StreamCodec<RegistryFriendlyByteBuf, RegionMarkerData> MARKER_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RegionMarkerData::regionX,
            ByteBufCodecs.VAR_INT, RegionMarkerData::regionZ,
            ByteBufCodecs.VAR_INT, RegionMarkerData::factionId,
            ByteBufCodecs.VAR_INT, RegionMarkerData::baseTypeId,
            RegionMarkerData::new);

    private static final StreamCodec<RegistryFriendlyByteBuf, java.util.List<RegionMarkerData>> MARKERS_CODEC = ByteBufCodecs
            .<RegistryFriendlyByteBuf, RegionMarkerData>list()
            .apply(MARKER_CODEC);

    private static final StreamCodec<RegistryFriendlyByteBuf, SiegeArrowData> ARROW_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SiegeArrowData::sourceRegionX,
            ByteBufCodecs.VAR_INT, SiegeArrowData::sourceRegionZ,
            ByteBufCodecs.VAR_INT, SiegeArrowData::targetRegionX,
            ByteBufCodecs.VAR_INT, SiegeArrowData::targetRegionZ,
            ByteBufCodecs.VAR_INT, SiegeArrowData::attackValue,
            ByteBufCodecs.BOOL, SiegeArrowData::encircled,
            SiegeArrowData::new);

    private static final StreamCodec<RegistryFriendlyByteBuf, java.util.List<SiegeArrowData>> ARROWS_CODEC = ByteBufCodecs
            .<RegistryFriendlyByteBuf, SiegeArrowData>list()
            .apply(ARROW_CODEC);

    private static final StreamCodec<RegistryFriendlyByteBuf, java.util.List<String>> LOGS_CODEC = ByteBufCodecs
            .<RegistryFriendlyByteBuf, String>list()
            .apply(ByteBufCodecs.STRING_UTF8.cast());

    public static final StreamCodec<RegistryFriendlyByteBuf, RegionMapPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RegionMapPayload decode(RegistryFriendlyByteBuf buf) {
            return new RegionMapPayload(
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    CHUNKS_CODEC.decode(buf),
                    MARKERS_CODEC.decode(buf),
                    ARROWS_CODEC.decode(buf),
                    LOGS_CODEC.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf)
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, RegionMapPayload payload) {
            ByteBufCodecs.VAR_INT.encode(buf, payload.centerChunkX());
            ByteBufCodecs.VAR_INT.encode(buf, payload.centerChunkZ());
            CHUNKS_CODEC.encode(buf, payload.chunks());
            MARKERS_CODEC.encode(buf, payload.markers());
            ARROWS_CODEC.encode(buf, payload.siegeArrows());
            LOGS_CODEC.encode(buf, payload.logMessages());
            ByteBufCodecs.BOOL.encode(buf, payload.isExplicitRequest());
            ByteBufCodecs.BOOL.encode(buf, payload.isCommandTerminalMap());
            ByteBufCodecs.BOOL.encode(buf, payload.isDebugMap());
        }
    };

    public static void handle(RegionMapPayload payload, IPayloadContext context) {
        if (Minecraft.getInstance().screen instanceof RegionMapScreen screen) {
            if (!payload.isExplicitRequest() && (screen.isDebugMap() || screen.isCommandTerminalMap() != payload.isCommandTerminalMap())) {
                // Ignore mismatched background snapshots (e.g. background AI broadcast sent while Debug Map or Command Terminal is open)
                return;
            }
            screen.updateMapData(payload);
        } else if (payload.isExplicitRequest()) {
            Minecraft.getInstance().setScreen(new RegionMapScreen(payload));
        }
    }

    @Override
    public Type<RegionMapPayload> type() {
        return TYPE;
    }

    public record ChunkData(int chunkX, int chunkZ, int biomeColor, int factionId, boolean underSiege, boolean isVisited) {
    }

    public record RegionMarkerData(int regionX, int regionZ, int factionId, int baseTypeId) {
    }

    public record SiegeArrowData(int sourceRegionX, int sourceRegionZ, int targetRegionX, int targetRegionZ, int attackValue, boolean encircled) {
    }
}
