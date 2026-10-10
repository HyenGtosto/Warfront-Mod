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

public record CreateFactionPayload(String name, String description, int color) implements CustomPacketPayload {

    public static final Type<CreateFactionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "create_faction"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CreateFactionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CreateFactionPayload::name,
            ByteBufCodecs.STRING_UTF8, CreateFactionPayload::description,
            ByteBufCodecs.INT, CreateFactionPayload::color,
            CreateFactionPayload::new
    );

    public static void handle(CreateFactionPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                PlayerFactionData.get(player.serverLevel()).createFaction(player, payload.name(), payload.description(), payload.color());
            });
        }
    }

    @Override
    public Type<CreateFactionPayload> type() {
        return TYPE;
    }
}
