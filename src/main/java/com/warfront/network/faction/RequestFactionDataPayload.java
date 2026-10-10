package com.warfront.network.faction;

import com.warfront.Warfront;
import com.warfront.faction.PlayerFactionData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestFactionDataPayload() implements CustomPacketPayload {

    public static final Type<RequestFactionDataPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "request_faction_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestFactionDataPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RequestFactionDataPayload decode(RegistryFriendlyByteBuf buf) {
            return new RequestFactionDataPayload();
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, RequestFactionDataPayload payload) {
        }
    };

    public static void handle(RequestFactionDataPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                PlayerFactionData.get(player.serverLevel()).syncToPlayer(player);
            });
        }
    }

    @Override
    public Type<RequestFactionDataPayload> type() {
        return TYPE;
    }
}
