package com.warfront.network;

import com.warfront.Warfront;
import com.warfront.client.hud.ActiveMissionHudOverlay;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ActiveMissionHudPayload(
        boolean hasActiveMission,
        int regionX,
        int regionZ,
        int subX,
        int subZ,
        String missionName,
        int currentKills,
        int requiredKills,
        long remainingTicks,
        int factionId,
        boolean isDefense
) implements CustomPacketPayload {

    public static final Type<ActiveMissionHudPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "active_mission_hud"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ActiveMissionHudPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ActiveMissionHudPayload decode(RegistryFriendlyByteBuf buf) {
            return new ActiveMissionHudPayload(
                    ByteBufCodecs.BOOL.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf)
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ActiveMissionHudPayload payload) {
            ByteBufCodecs.BOOL.encode(buf, payload.hasActiveMission());
            ByteBufCodecs.VAR_INT.encode(buf, payload.regionX());
            ByteBufCodecs.VAR_INT.encode(buf, payload.regionZ());
            ByteBufCodecs.VAR_INT.encode(buf, payload.subX());
            ByteBufCodecs.VAR_INT.encode(buf, payload.subZ());
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.missionName());
            ByteBufCodecs.VAR_INT.encode(buf, payload.currentKills());
            ByteBufCodecs.VAR_INT.encode(buf, payload.requiredKills());
            ByteBufCodecs.VAR_LONG.encode(buf, payload.remainingTicks());
            ByteBufCodecs.VAR_INT.encode(buf, payload.factionId());
            ByteBufCodecs.BOOL.encode(buf, payload.isDefense());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ActiveMissionHudPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ActiveMissionHudOverlay.updateHud(payload));
    }
}
