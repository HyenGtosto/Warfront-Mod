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

public record AcceptJoinRequestPayload(UUID requesterUuid) implements CustomPacketPayload {

    public static final Type<AcceptJoinRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "accept_join_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AcceptJoinRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AcceptJoinRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new AcceptJoinRequestPayload(buf.readUUID());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AcceptJoinRequestPayload payload) {
            buf.writeUUID(payload.requesterUuid);
        }
    };

    public static void handle(AcceptJoinRequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                PlayerFactionData.get(player.serverLevel()).acceptJoinRequest(player, payload.requesterUuid());
            });
        }
    }

    @Override
    public Type<AcceptJoinRequestPayload> type() {
        return TYPE;
    }
}
