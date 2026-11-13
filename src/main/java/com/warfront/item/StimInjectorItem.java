package com.warfront.item;

import com.warfront.block.WarfrontBlocks;
import com.warfront.effect.WarfrontMobEffects;
import com.warfront.stim.StimClientState;
import com.warfront.stim.StimConstants;
import com.warfront.stim.StimServerEvents;
import com.warfront.stim.StimState;
import com.warfront.stim.WarfrontAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Usable Zombie-faction bio-stimulator injector sharing a collective tolerance/overdose pool.
 */
public class StimInjectorItem extends Item {

    public enum StimType {
        COMBAT("combat"),
        ACROBATICS("acrobatics"),
        HEALING("healing");

        private final String key;

        StimType(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private final StimType stimType;

    public StimInjectorItem(StimType stimType, Properties properties) {
        super(properties);
        this.stimType = stimType;
    }

    public StimType getStimType() {
        return stimType;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide()) {
            if (StimClientState.isOverdoseLocked()) {
                return InteractionResultHolder.fail(stack);
            }
            return InteractionResultHolder.success(stack);
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }

        StimState state = serverPlayer.getData(WarfrontAttachments.STIM_STATE);
        if (state.isOverdoseLocked()) {
            int remainingTicks = Math.max(0, state.getRecentStimCount() - 1) * StimConstants.TOLERANCE_DECAY_TICKS
                    + state.getDecayTicksRemaining();
            int remainingSeconds = (remainingTicks + 19) / 20;
            serverPlayer.displayClientMessage(
                    Component.translatable("message.warfront.stim.overdose_locked", remainingSeconds), true);
            level.playSound(null, serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                    SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.5F, 0.6F);
            return InteractionResultHolder.fail(stack);
        }

        applySharedCooldown(serverPlayer);
        if (!serverPlayer.getAbilities().instabuild) {
            stack.shrink(1);
        }

        boolean overdosed = state.recordStimUse();
        if (overdosed) {
            triggerOverdose(serverPlayer);
        } else {
            applyStimEffects(serverPlayer);
        }

        serverPlayer.awardStat(Stats.ITEM_USED.get(this));
        StimServerEvents.syncToPlayer(serverPlayer);
        return InteractionResultHolder.consume(stack);
    }

    private void applySharedCooldown(ServerPlayer player) {
        player.getCooldowns().addCooldown(WarfrontBlocks.COMBAT_STIM.get(), StimConstants.ITEM_USE_COOLDOWN_TICKS);
        player.getCooldowns().addCooldown(WarfrontBlocks.ACROBATICS_STIM.get(), StimConstants.ITEM_USE_COOLDOWN_TICKS);
        player.getCooldowns().addCooldown(WarfrontBlocks.HEALING_STIM.get(), StimConstants.ITEM_USE_COOLDOWN_TICKS);
    }

    private void applyStimEffects(ServerPlayer player) {
        int duration = StimConstants.STIM_DURATION_TICKS;
        switch (stimType) {
            case COMBAT -> {
                player.addEffect(new MobEffectInstance(WarfrontMobEffects.COMBAT_STIM, duration, 0, false, true, true));
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, duration, 0, false, true, true));
                player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, duration, 0, false, true, true));
            }
            case ACROBATICS -> {
                player.addEffect(new MobEffectInstance(WarfrontMobEffects.ACROBATICS_STIM, duration, 0, false, true, true));
                player.addEffect(new MobEffectInstance(MobEffects.JUMP, duration, 0, false, true, true));
            }
            case HEALING -> {
                player.addEffect(new MobEffectInstance(WarfrontMobEffects.HEALING_STIM, duration, 0, false, true, true));
                player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, duration, 0, false, true, true));
                player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, duration, 0, false, true, true));
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, 0, false, true, true));
            }
        }

        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }

        StimServerEvents.syncStimUsageEffect(player);

        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BOTTLE_EMPTY, SoundSource.PLAYERS, 0.7F, 1.4F);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.4F, 1.35F);
    }

    private void triggerOverdose(ServerPlayer player) {
        // Milk-bucket clear of all active status effects before applying Wither
        player.removeAllEffects();
        player.addEffect(new MobEffectInstance(
                MobEffects.WITHER,
                StimConstants.OVERDOSE_WITHER_DURATION_TICKS,
                StimConstants.OVERDOSE_WITHER_AMPLIFIER,
                false, true, true
        ));

        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }

        ServerLevel serverLevel = player.serverLevel();
        serverLevel.sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 1.0D, player.getZ(),
                18, 0.35D, 0.4D, 0.35D, 0.04D);
        serverLevel.sendParticles(ParticleTypes.CRIMSON_SPORE, player.getX(), player.getY() + 1.0D, player.getZ(),
                14, 0.4D, 0.4D, 0.4D, 0.02D);
        serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.WITHER_HURT, SoundSource.PLAYERS, 0.75F, 1.1F);
        serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.PLAYERS, 0.65F, 0.9F);
        player.displayClientMessage(Component.translatable("message.warfront.stim.overdose"), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("tooltip.warfront.stim." + stimType.key() + ".effects")
                .withStyle(ChatFormatting.AQUA));
        tooltipComponents.add(Component.translatable("tooltip.warfront.stim." + stimType.key() + ".cost")
                .withStyle(ChatFormatting.RED));
        tooltipComponents.add(Component.translatable("tooltip.warfront.stim.shared_overdose")
                .withStyle(ChatFormatting.GRAY));
        if (StimClientState.isOverdoseLocked()) {
            tooltipComponents.add(Component.translatable("tooltip.warfront.stim.locked")
                    .withStyle(ChatFormatting.DARK_RED));
        } else if (StimClientState.getRecentStimCount() > 0) {
            tooltipComponents.add(Component.translatable(
                    "tooltip.warfront.stim.tolerance",
                    StimClientState.getRecentStimCount(),
                    StimConstants.OVERDOSE_THRESHOLD
            ).withStyle(ChatFormatting.YELLOW));
        }
    }
}
