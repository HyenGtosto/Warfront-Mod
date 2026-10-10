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

public record InvitePlayerPayload(String playerName) implements CustomPacketPayload {

    public static final Type<InvitePlayerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "invite_player"));

    public static final StreamCodec<RegistryFriendlyByteBuf, InvitePlayerPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, InvitePlayerPayload::playerName,
            InvitePlayerPayload::new
    );

    public static void handle(InvitePlayerPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                PlayerFactionData.get(player.serverLevel()).invitePlayer(player, payload.playerName());
            });
        }
    }

    @Override
    public Type<InvitePlayerPayload> type() {
        return TYPE;
    }
}
