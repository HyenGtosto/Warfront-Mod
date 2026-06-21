package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.map.BiomeMapColors;
import com.warfront.region.RegionData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestRegionMapPayload() implements CustomPacketPayload {
    public static final int MAP_SIZE_BLOCKS = 1024;
    public static final int MAP_CHUNK_DIAMETER = MAP_SIZE_BLOCKS / 16;
    public static final Type<RequestRegionMapPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "request_region_map"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestRegionMapPayload> STREAM_CODEC = StreamCodec.unit(
            new RequestRegionMapPayload());

    public static void handle(RequestRegionMapPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        ServerLevel level = player.serverLevel();
        RegionData regions = RegionData.get(level);
        int centerChunkX = player.chunkPosition().x;
        int centerChunkZ = player.chunkPosition().z;
        int originChunkX = centerChunkX - MAP_CHUNK_DIAMETER / 2;
        int originChunkZ = centerChunkZ - MAP_CHUNK_DIAMETER / 2;
        List<RegionMapPayload.ChunkData> chunks = new ArrayList<>(MAP_CHUNK_DIAMETER * MAP_CHUNK_DIAMETER);

        for (int chunkZ = originChunkZ; chunkZ < originChunkZ + MAP_CHUNK_DIAMETER; chunkZ++) {
            for (int chunkX = originChunkX; chunkX < originChunkX + MAP_CHUNK_DIAMETER; chunkX++) {
                BlockPos samplePosition = new BlockPos(chunkX * 16 + 8, level.getSeaLevel(), chunkZ * 16 + 8);
                int biomeColor = BiomeMapColors.colorFor(level.getBiome(samplePosition));
                int factionId = regions.regionAt(samplePosition).owner().id();
                chunks.add(new RegionMapPayload.ChunkData(chunkX, chunkZ, biomeColor, factionId));
            }
        }

        PacketDistributor.sendToPlayer(player, new RegionMapPayload(centerChunkX, centerChunkZ, chunks));
    }

    @Override
    public Type<RequestRegionMapPayload> type() {
        return TYPE;
    }
}
