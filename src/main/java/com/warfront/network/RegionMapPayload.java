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

public record RegionMapPayload(int centerChunkX, int centerChunkZ, java.util.List<ChunkData> chunks)
        implements CustomPacketPayload {
    public static final Type<RegionMapPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "region_map"));
    private static final StreamCodec<RegistryFriendlyByteBuf, ChunkData> CHUNK_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChunkData::chunkX,
            ByteBufCodecs.VAR_INT, ChunkData::chunkZ,
            ByteBufCodecs.INT, ChunkData::biomeColor,
            ByteBufCodecs.VAR_INT, ChunkData::factionId,
            ChunkData::new);
    private static final StreamCodec<RegistryFriendlyByteBuf, java.util.List<ChunkData>> CHUNKS_CODEC = ByteBufCodecs
            .<RegistryFriendlyByteBuf, ChunkData>list(RequestRegionMapPayload.MAP_CHUNK_DIAMETER
                    * RequestRegionMapPayload.MAP_CHUNK_DIAMETER)
            .apply(CHUNK_CODEC);
    public static final StreamCodec<RegistryFriendlyByteBuf, RegionMapPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RegionMapPayload::centerChunkX,
            ByteBufCodecs.VAR_INT, RegionMapPayload::centerChunkZ,
            CHUNKS_CODEC,
            RegionMapPayload::chunks,
            RegionMapPayload::new);

    public static void handle(RegionMapPayload payload, IPayloadContext context) {
        Minecraft.getInstance().setScreen(new RegionMapScreen(payload));
    }

    @Override
    public Type<RegionMapPayload> type() {
        return TYPE;
    }

    public record ChunkData(int chunkX, int chunkZ, int biomeColor, int factionId) {
    }
}
