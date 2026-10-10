package com.warfront.network.faction;

import com.warfront.Warfront;
import com.warfront.faction.PlayerFactionData;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record KickPlayerPayload(UUID targetPlayerUuid) implements CustomPacketPayload {

    public static final Type<KickPlayerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "kick_player"));

    public static final StreamCodec<RegistryFriendlyByteBuf, KickPlayerPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public KickPlayerPayload decode(RegistryFriendlyByteBuf buf) {
            return new KickPlayerPayload(buf.readUUID());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, KickPlayerPayload payload) {
            buf.writeUUID(payload.targetPlayerUuid);
        }
    };

    public static void handle(KickPlayerPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                PlayerFactionData.get(player.serverLevel()).kickPlayer(player, payload.targetPlayerUuid());
            });
        }
    }

    @Override
    public Type<KickPlayerPayload> type() {
        return TYPE;
    }
}
