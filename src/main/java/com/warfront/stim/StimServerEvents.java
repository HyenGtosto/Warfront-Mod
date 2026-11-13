package com.warfront.stim;

import com.warfront.effect.WarfrontMobEffects;
import com.warfront.network.stim.SyncStimStatePayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Server-side lifecycle handler for stim tolerance decay, overdose unlocking, death resets, and client syncing.
 */
public final class StimServerEvents {

    private StimServerEvents() {
    }

    public static void syncStimUsageEffect(ServerPlayer player) {
        StimState state = player.getData(WarfrontAttachments.STIM_STATE);
        if (state.isOverdoseLocked() || state.getRecentStimCount() <= 0 || state.getDecayTicksRemaining() <= 0) {
            if (player.hasEffect(WarfrontMobEffects.STIM_USAGE)) {
                player.removeEffect(WarfrontMobEffects.STIM_USAGE);
            }
            return;
        }

        int targetAmplifier = Math.min(StimConstants.OVERDOSE_THRESHOLD - 1, state.getRecentStimCount() - 1);
        int targetDuration = state.getDecayTicksRemaining();
        MobEffectInstance current = player.getEffect(WarfrontMobEffects.STIM_USAGE);

        if (current != null
                && current.getAmplifier() == targetAmplifier
                && Math.abs(current.getDuration() - targetDuration) <= 2) {
            return;
        }

        // Remove first so amplifier downgrades (Level II -> Level I) and exact duration refreshes always apply.
        if (current != null) {
            player.removeEffect(WarfrontMobEffects.STIM_USAGE);
        }
        player.addEffect(new MobEffectInstance(
                WarfrontMobEffects.STIM_USAGE,
                targetDuration,
                targetAmplifier,
                false,
                false,
                true
        ));
    }

    public static void syncToPlayer(ServerPlayer player) {
        syncStimUsageEffect(player);
        StimState state = player.getData(WarfrontAttachments.STIM_STATE);
        player.connection.send(new SyncStimStatePayload(
                state.getRecentStimCount(),
                state.getDecayTicksRemaining(),
                state.isOverdoseLocked(),
                state.getDoubleJumpCooldownTicks(),
                state.getDashCooldownTicks()
        ));
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        StimState state = player.getData(WarfrontAttachments.STIM_STATE);
        boolean wasLocked = state.isOverdoseLocked();
        if (state.tickServer()) {
            if (wasLocked && !state.isOverdoseLocked()) {
                player.displayClientMessage(Component.translatable("message.warfront.stim.overdose_cleared"), true);
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5F, 1.1F);
            }
            syncToPlayer(player);
            return;
        }

        // Re-sync informational indicator if removed externally (e.g., milk bucket) while tolerance is active.
        boolean shouldShowUsage = !state.isOverdoseLocked() && state.getRecentStimCount() > 0 && state.getDecayTicksRemaining() > 0;
        if (shouldShowUsage != player.hasEffect(WarfrontMobEffects.STIM_USAGE)) {
            syncStimUsageEffect(player);
        }
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncToPlayer(player);
        }
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncToPlayer(player);
        }
    }

    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncToPlayer(player);
        }
    }

    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() && event.getOriginal().hasData(WarfrontAttachments.STIM_STATE)) {
            StimState prev = event.getOriginal().getData(WarfrontAttachments.STIM_STATE);
            event.getEntity().setData(WarfrontAttachments.STIM_STATE, prev.copy());
        }
    }
}
