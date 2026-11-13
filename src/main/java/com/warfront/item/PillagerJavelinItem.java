package com.warfront.item;

import com.warfront.client.renderer.PillagerJavelinItemRenderer;
import com.warfront.entity.JavelinProjectileEntity;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/**
 * Single-use throwable Pillager Javelin dropped by Pillager Legionnaires.
 * Charges and launches with identical pull mechanics to a vanilla Trident, deals 16 damage,
 * reuses {@link JavelinProjectileEntity} gravity/drag physics and 3D model, and disables left-click attacks.
 */
public class PillagerJavelinItem extends Item implements GeoItem {

    public static final int THROW_THRESHOLD_TIME = 10;
    public static final float SHOOT_POWER = 2.5F;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public PillagerJavelinItem(Properties properties) {
        super(properties);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPEAR;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity livingEntity, int timeCharged) {
        if (!(livingEntity instanceof Player player)) {
            return;
        }
        int chargeTicks = this.getUseDuration(stack, livingEntity) - timeCharged;
        if (chargeTicks < THROW_THRESHOLD_TIME) {
            return;
        }

        if (!level.isClientSide) {
            JavelinProjectileEntity javelin = new JavelinProjectileEntity(level, player);
            javelin.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, SHOOT_POWER, 1.0F);
            javelin.syncOrientationFromVelocity(javelin.getDeltaMovement());
            level.addFreshEntity(javelin);
            level.playSound(null, javelin, SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
            stack.consume(1, player);
        }

        player.awardStat(Stats.ITEM_USED.get(this));
    }

    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }

    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity entity) {
        return true;
    }

    @Override
    public boolean onEntitySwing(ItemStack stack, LivingEntity entity, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND;
    }

    @Override
    public int getEnchantmentValue() {
        return 0;
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private PillagerJavelinItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (this.renderer == null) {
                    this.renderer = new PillagerJavelinItemRenderer();
                }
                return this.renderer;
            }
        });
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Static held 3D javelin model; charge/throw pose handled via UseAnim.SPEAR & display transforms
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
