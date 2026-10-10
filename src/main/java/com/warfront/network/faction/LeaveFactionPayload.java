package com.warfront.network.faction;

import com.warfront.Warfront;
import com.warfront.faction.PlayerFactionData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LeaveFactionPayload() implements CustomPacketPayload {

    public static final Type<LeaveFactionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "leave_faction"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LeaveFactionPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public LeaveFactionPayload decode(RegistryFriendlyByteBuf buf) {
            return new LeaveFactionPayload();
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, LeaveFactionPayload payload) {
        }
    };

    public static void handle(LeaveFactionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                PlayerFactionData.get(player.serverLevel()).leaveFaction(player);
            });
        }
    }

    @Override
    public Type<LeaveFactionPayload> type() {
        return TYPE;
    }
}
