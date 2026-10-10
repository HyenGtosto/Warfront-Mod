package com.warfront.network.faction;

import com.warfront.Warfront;
import com.warfront.client.faction.FactionClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record FactionActionFeedbackPayload(boolean success, String message) implements CustomPacketPayload {

    public static final Type<FactionActionFeedbackPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "faction_action_feedback"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FactionActionFeedbackPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, FactionActionFeedbackPayload::success,
            ByteBufCodecs.STRING_UTF8, FactionActionFeedbackPayload::message,
            FactionActionFeedbackPayload::new
    );

    public static void handle(FactionActionFeedbackPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            FactionClientState.get().setFeedback(payload.success(), payload.message());
        });
    }

    @Override
    public Type<FactionActionFeedbackPayload> type() {
        return TYPE;
    }
}
