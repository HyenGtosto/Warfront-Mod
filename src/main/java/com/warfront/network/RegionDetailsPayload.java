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

public record RegionDetailsPayload(
        int regionX, int regionZ, int subX, int subZ,
        int factionId, float stability, float resistance,
        int baseTypeId, boolean underSiege, boolean isVisited,
        long remainingSiegeTicks, int dominoThreshold,
        int reachableMask, boolean regionReachable,
        int existingSiegeMask, int conqueredMask
) implements CustomPacketPayload {

    public RegionDetailsPayload(int regionX, int regionZ, int subX, int subZ, int factionId, float stability, float resistance, int baseTypeId, boolean underSiege, boolean isVisited) {
        this(regionX, regionZ, subX, subZ, factionId, stability, resistance, baseTypeId, underSiege, isVisited, 0L, 3, 0xF, true, 0, 0);
    }

    public static final Type<RegionDetailsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "region_details"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RegionDetailsPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RegionDetailsPayload decode(RegistryFriendlyByteBuf buf) {
            return new RegionDetailsPayload(
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf)
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, RegionDetailsPayload payload) {
            ByteBufCodecs.VAR_INT.encode(buf, payload.regionX());
            ByteBufCodecs.VAR_INT.encode(buf, payload.regionZ());
            ByteBufCodecs.VAR_INT.encode(buf, payload.subX());
            ByteBufCodecs.VAR_INT.encode(buf, payload.subZ());
            ByteBufCodecs.VAR_INT.encode(buf, payload.factionId());
            ByteBufCodecs.FLOAT.encode(buf, payload.stability());
            ByteBufCodecs.FLOAT.encode(buf, payload.resistance());
            ByteBufCodecs.VAR_INT.encode(buf, payload.baseTypeId());
            ByteBufCodecs.BOOL.encode(buf, payload.underSiege());
            ByteBufCodecs.BOOL.encode(buf, payload.isVisited());
            ByteBufCodecs.VAR_LONG.encode(buf, payload.remainingSiegeTicks());
            ByteBufCodecs.VAR_INT.encode(buf, payload.dominoThreshold());
            ByteBufCodecs.VAR_INT.encode(buf, payload.reachableMask());
            ByteBufCodecs.BOOL.encode(buf, payload.regionReachable());
            ByteBufCodecs.VAR_INT.encode(buf, payload.existingSiegeMask());
            ByteBufCodecs.VAR_INT.encode(buf, payload.conqueredMask());
        }
    };

    public static void handle(RegionDetailsPayload payload, IPayloadContext context) {
        if (Minecraft.getInstance().screen instanceof RegionMapScreen screen) {
            Warfront.LOGGER.debug("Received details for region {}, {}", payload.regionX(), payload.regionZ());
            screen.selectRegion(payload);
        }
    }

    @Override
    public Type<RegionDetailsPayload> type() {
        return TYPE;
    }
}
