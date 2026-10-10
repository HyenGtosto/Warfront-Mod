package com.warfront.network.faction;

import com.warfront.Warfront;
import com.warfront.faction.PlayerFactionData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SendFactionChatPayload(String message) implements CustomPacketPayload {

    public static final Type<SendFactionChatPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "send_faction_chat"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SendFactionChatPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SendFactionChatPayload::message,
            SendFactionChatPayload::new
    );

    public static void handle(SendFactionChatPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                PlayerFactionData.get(player.serverLevel()).sendChatMessage(player, payload.message());
            });
        }
    }

    @Override
    public Type<SendFactionChatPayload> type() {
        return TYPE;
    }
}
