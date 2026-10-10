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

public record UpdateFactionSettingsPayload(String name, String description, int color) implements CustomPacketPayload {

    public static final Type<UpdateFactionSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "update_faction_settings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateFactionSettingsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, UpdateFactionSettingsPayload::name,
            ByteBufCodecs.STRING_UTF8, UpdateFactionSettingsPayload::description,
            ByteBufCodecs.INT, UpdateFactionSettingsPayload::color,
            UpdateFactionSettingsPayload::new
    );

    public static void handle(UpdateFactionSettingsPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                PlayerFactionData.get(player.serverLevel()).updateFactionSettings(player, payload.name(), payload.description(), payload.color());
            });
        }
    }

    @Override
    public Type<UpdateFactionSettingsPayload> type() {
        return TYPE;
    }
}
