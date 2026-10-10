package com.warfront.network.faction;

import com.warfront.Warfront;
import com.warfront.faction.PlayerFactionData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record DisbandFactionPayload() implements CustomPacketPayload {

    public static final Type<DisbandFactionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "disband_faction"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DisbandFactionPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public DisbandFactionPayload decode(RegistryFriendlyByteBuf buf) {
            return new DisbandFactionPayload();
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, DisbandFactionPayload payload) {
        }
    };

    public static void handle(DisbandFactionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                PlayerFactionData.get(player.serverLevel()).disbandFaction(player);
            });
        }
    }

    @Override
    public Type<DisbandFactionPayload> type() {
        return TYPE;
    }
}
