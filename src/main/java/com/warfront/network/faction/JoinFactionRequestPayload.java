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

public record JoinFactionRequestPayload(String factionName) implements CustomPacketPayload {

    public static final Type<JoinFactionRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "join_faction_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, JoinFactionRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, JoinFactionRequestPayload::factionName,
            JoinFactionRequestPayload::new
    );

    public static void handle(JoinFactionRequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                PlayerFactionData.get(player.serverLevel()).requestJoin(player, payload.factionName());
            });
        }
    }

    @Override
    public Type<JoinFactionRequestPayload> type() {
        return TYPE;
    }
}
