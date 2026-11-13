package com.warfront.network.stim;

import com.warfront.Warfront;
import com.warfront.effect.WarfrontMobEffects;
import com.warfront.stim.StimConstants;
import com.warfront.stim.StimServerEvents;
import com.warfront.stim.StimState;
import com.warfront.stim.WarfrontAttachments;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-to-server request to execute an Acrobatics Stim double jump or directional dash.
 */
public record TriggerStimMobilityPayload(boolean isDash, float dirX, float dirZ) implements CustomPacketPayload {

    public static final Type<TriggerStimMobilityPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "trigger_stim_mobility"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TriggerStimMobilityPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, TriggerStimMobilityPayload::isDash,
            ByteBufCodecs.FLOAT, TriggerStimMobilityPayload::dirX,
            ByteBufCodecs.FLOAT, TriggerStimMobilityPayload::dirZ,
            TriggerStimMobilityPayload::new
    );

    public static void handle(TriggerStimMobilityPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            if (!player.isAlive() || player.isSpectator() || player.isPassenger()) {
                return;
            }
            if (!player.hasEffect(WarfrontMobEffects.ACROBATICS_STIM)) {
                return;
            }

            StimState state = player.getData(WarfrontAttachments.STIM_STATE);
            ServerLevel level = player.serverLevel();
            Vec3 vel = player.getDeltaMovement();

            if (!payload.isDash()) {
                if (state.getDoubleJumpCooldownTicks() > 0) {
                    return;
                }
                if (player.onGround() || player.isInWater() || player.onClimbable() || player.isFallFlying()) {
                    return;
                }
                if (player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                    return;
                }

                player.setDeltaMovement(vel.x, StimConstants.DOUBLE_JUMP_VERTICAL_VELOCITY, vel.z);
                player.fallDistance = 0.0F;
                player.hurtMarked = true;
                state.startDoubleJumpCooldown();

                level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.1D, player.getZ(),
                        10, 0.25D, 0.05D, 0.25D, 0.04D);
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, 0.7F, 1.35F);
                StimServerEvents.syncToPlayer(player);
            } else {
                if (state.getDashCooldownTicks() > 0) {
                    return;
                }
                double dx = payload.dirX();
                double dz = payload.dirZ();
                double lenSq = dx * dx + dz * dz;
                if (!Double.isFinite(lenSq) || lenSq < 1.0E-4D) {
                    return;
                }
                double invLen = 1.0D / Math.sqrt(lenSq);
                double nx = dx * invLen;
                double nz = dz * invLen;

                double nextY = Math.max(vel.y, StimConstants.DASH_VERTICAL_LIFT);
                player.setDeltaMovement(nx * StimConstants.DASH_HORIZONTAL_SPEED, nextY, nz * StimConstants.DASH_HORIZONTAL_SPEED);
                player.fallDistance = Math.min(player.fallDistance, 1.0F);
                player.hurtMarked = true;
                state.startDashCooldown();

                level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.5D, player.getZ(),
                        12, 0.3D, 0.2D, 0.3D, 0.06D);
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.BREEZE_SLIDE, SoundSource.PLAYERS, 0.75F, 1.25F);
                StimServerEvents.syncToPlayer(player);
            }
        });
    }

    @Override
    public Type<TriggerStimMobilityPayload> type() {
        return TYPE;
    }
}
