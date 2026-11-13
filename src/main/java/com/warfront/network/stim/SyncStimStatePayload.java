package com.warfront.network.stim;

import com.warfront.Warfront;
import com.warfront.stim.StimClientState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-to-client synchronization packet for the local player's stim tolerance and cooldowns.
 */
public record SyncStimStatePayload(
        int recentStimCount,
        int decayTicksRemaining,
        boolean overdoseLocked,
        int doubleJumpCooldownTicks,
        int dashCooldownTicks
) implements CustomPacketPayload {

    public static final Type<SyncStimStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "sync_stim_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncStimStatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncStimStatePayload::recentStimCount,
            ByteBufCodecs.VAR_INT, SyncStimStatePayload::decayTicksRemaining,
            ByteBufCodecs.BOOL, SyncStimStatePayload::overdoseLocked,
            ByteBufCodecs.VAR_INT, SyncStimStatePayload::doubleJumpCooldownTicks,
            ByteBufCodecs.VAR_INT, SyncStimStatePayload::dashCooldownTicks,
            SyncStimStatePayload::new
    );

    public static void handle(SyncStimStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> StimClientState.update(
                payload.recentStimCount(),
                payload.decayTicksRemaining(),
                payload.overdoseLocked(),
                payload.doubleJumpCooldownTicks(),
                payload.dashCooldownTicks()
        ));
    }

    @Override
    public Type<SyncStimStatePayload> type() {
        return TYPE;
    }
}
