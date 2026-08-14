package com.warfront.network;

import com.warfront.Warfront;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record CloseMapSessionPayload() implements CustomPacketPayload {
    public static final Type<CloseMapSessionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "close_map_session"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CloseMapSessionPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public CloseMapSessionPayload decode(RegistryFriendlyByteBuf buf) {
            return new CloseMapSessionPayload();
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, CloseMapSessionPayload payload) {
        }
    };

    public static void handle(CloseMapSessionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            RequestRegionMapPayload.clearActiveSession(player);
        }
    }

    @Override
    public Type<CloseMapSessionPayload> type() {
        return TYPE;
    }
}
