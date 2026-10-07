package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.client.map.RegionMapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
        int existingSiegeMask, int conqueredMask,
        int attackerFactionId,
        boolean isAwaitingReinforcements,
        long reinforcementRemainingTicks,
        boolean isEncircled,
        BlockPos baseAnchor,
        long missionSeed
) implements CustomPacketPayload {

    public RegionDetailsPayload(int regionX, int regionZ, int subX, int subZ, int factionId, float stability, float resistance, int baseTypeId, boolean underSiege, boolean isVisited) {
        this(regionX, regionZ, subX, subZ, factionId, stability, resistance, baseTypeId, underSiege, isVisited, 0L, 3, 0xF, true, 0, 0, 0, false, 0L, false, null, 0L);
    }

    public RegionDetailsPayload(
            int regionX, int regionZ, int subX, int subZ,
            int factionId, float stability, float resistance,
            int baseTypeId, boolean underSiege, boolean isVisited,
            long remainingSiegeTicks, int dominoThreshold,
            int reachableMask, boolean regionReachable,
            int existingSiegeMask, int conqueredMask,
            int attackerFactionId,
            boolean isAwaitingReinforcements,
            long reinforcementRemainingTicks,
            boolean isEncircled
    ) {
        this(regionX, regionZ, subX, subZ, factionId, stability, resistance, baseTypeId, underSiege, isVisited, remainingSiegeTicks, dominoThreshold, reachableMask, regionReachable, existingSiegeMask, conqueredMask, attackerFactionId, isAwaitingReinforcements, reinforcementRemainingTicks, isEncircled, null, 0L);
    }

    public static final Type<RegionDetailsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "region_details"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RegionDetailsPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RegionDetailsPayload decode(RegistryFriendlyByteBuf buf) {
            int rx = ByteBufCodecs.VAR_INT.decode(buf);
            int rz = ByteBufCodecs.VAR_INT.decode(buf);
            int sx = ByteBufCodecs.VAR_INT.decode(buf);
            int sz = ByteBufCodecs.VAR_INT.decode(buf);
            int factionId = ByteBufCodecs.VAR_INT.decode(buf);
            float stability = ByteBufCodecs.FLOAT.decode(buf);
            float resistance = ByteBufCodecs.FLOAT.decode(buf);
            int baseTypeId = ByteBufCodecs.VAR_INT.decode(buf);
            boolean underSiege = ByteBufCodecs.BOOL.decode(buf);
            boolean isVisited = ByteBufCodecs.BOOL.decode(buf);
            long remainingSiegeTicks = ByteBufCodecs.VAR_LONG.decode(buf);
            int dominoThreshold = ByteBufCodecs.VAR_INT.decode(buf);
            int reachableMask = ByteBufCodecs.VAR_INT.decode(buf);
            boolean regionReachable = ByteBufCodecs.BOOL.decode(buf);
            int existingSiegeMask = ByteBufCodecs.VAR_INT.decode(buf);
            int conqueredMask = ByteBufCodecs.VAR_INT.decode(buf);
            int attackerFactionId = ByteBufCodecs.VAR_INT.decode(buf);
            boolean isAwaitingReinforcements = ByteBufCodecs.BOOL.decode(buf);
            long reinforcementRemainingTicks = ByteBufCodecs.VAR_LONG.decode(buf);
            boolean isEncircled = ByteBufCodecs.BOOL.decode(buf);

            boolean hasAnchor = buf.readBoolean();
            BlockPos baseAnchor = hasAnchor ? buf.readBlockPos() : null;
            long missionSeed = ByteBufCodecs.VAR_LONG.decode(buf);

            return new RegionDetailsPayload(
                    rx, rz, sx, sz,
                    factionId, stability, resistance,
                    baseTypeId, underSiege, isVisited,
                    remainingSiegeTicks, dominoThreshold,
                    reachableMask, regionReachable,
                    existingSiegeMask, conqueredMask,
                    attackerFactionId,
                    isAwaitingReinforcements,
                    reinforcementRemainingTicks,
                    isEncircled,
                    baseAnchor,
                    missionSeed
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
            ByteBufCodecs.VAR_INT.encode(buf, payload.attackerFactionId());
            ByteBufCodecs.BOOL.encode(buf, payload.isAwaitingReinforcements());
            ByteBufCodecs.VAR_LONG.encode(buf, payload.reinforcementRemainingTicks());
            ByteBufCodecs.BOOL.encode(buf, payload.isEncircled());

            buf.writeBoolean(payload.baseAnchor() != null);
            if (payload.baseAnchor() != null) {
                buf.writeBlockPos(payload.baseAnchor());
            }
            ByteBufCodecs.VAR_LONG.encode(buf, payload.missionSeed());
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
