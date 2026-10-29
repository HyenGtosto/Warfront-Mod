package com.warfront.entity;

import com.warfront.Warfront;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * High-velocity, armor-piercing ballistic round fired by the Pillager Rifleman.
 * Bypasses vanilla wooden shields and delivers calibrated armor-piercing damage:
 * 6.0 HP (3 hearts) unarmored, scaling down to exactly 2.0 HP (1 heart) against Full Protection IV Netherite.
 */
public class RifleBulletEntity extends Projectile {

    public static final ResourceKey<DamageType> RIFLE_BULLET_DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "rifle_bullet"));

    public static final TagKey<Item> BULLETPROOF_SHIELDS =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "bulletproof_shields"));

    private int ticksInAir = 0;

    public RifleBulletEntity(EntityType<? extends RifleBulletEntity> entityType, Level level) {
        super(entityType, level);
    }

    public RifleBulletEntity(Level level, LivingEntity shooter) {
        super(ModEntities.RIFLE_BULLET.get(), level);
        setOwner(shooter);
        setPos(shooter.getX(), shooter.getEyeY() - 0.1D, shooter.getZ());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    public void shoot(double x, double y, double z, float velocity, float inaccuracy) {
        Vec3 motion = new Vec3(x, y, z)
                .normalize()
                .add(
                        this.random.triangle(0.0D, 0.0172275D * (double) inaccuracy),
                        this.random.triangle(0.0D, 0.0172275D * (double) inaccuracy),
                        this.random.triangle(0.0D, 0.0172275D * (double) inaccuracy)
                )
                .scale(velocity);
        setDeltaMovement(motion);
        double horizDist = motion.horizontalDistance();
        setYRot((float) (Mth.atan2(motion.x, motion.z) * (180.0D / Math.PI)));
        setXRot((float) (Mth.atan2(motion.y, horizDist) * (180.0D / Math.PI)));
        this.yRotO = getYRot();
        this.xRotO = getXRot();
    }

    @Override
    public void tick() {
        super.tick();
        ticksInAir++;

        Vec3 movement = getDeltaMovement();
        HitResult hitResult = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        if (hitResult.getType() != HitResult.Type.MISS) {
            onHit(hitResult);
            return;
        }

        double nextX = getX() + movement.x;
        double nextY = getY() + movement.y;
        double nextZ = getZ() + movement.z;

        // High-velocity rifle trajectory: almost flat drop (0.005 blocks/tick^2)
        setDeltaMovement(movement.x, movement.y - 0.005D, movement.z);

        double horizDist = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        setYRot((float) (Mth.atan2(movement.x, movement.z) * (180.0D / Math.PI)));
        setXRot((float) (Mth.atan2(movement.y, horizDist) * (180.0D / Math.PI)));

        setPos(nextX, nextY, nextZ);

        if (level().isClientSide) {
            // High-speed tracer trail
            Vec3 pos = position();
            level().addParticle(ParticleTypes.CRIT, pos.x, pos.y, pos.z, movement.x * 0.1D, movement.y * 0.1D, movement.z * 0.1D);
            if (ticksInAir % 2 == 0) {
                level().addParticle(ParticleTypes.SMOKE, pos.x, pos.y, pos.z, 0.0D, 0.0D, 0.0D);
            }
        } else {
            if (ticksInAir > 100 || getY() < level().getMinBuildHeight() - 20) {
                discard();
            }
        }
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        if (!super.canHitEntity(entity)) return false;
        if (getOwner() != null && AlliedFactionHelper.isAllied(getOwner(), entity)) {
            return false;
        }
        return true;
    }

    @Override
    protected void onHitBlock(BlockHitResult hitResult) {
        super.onHitBlock(hitResult);
        if (!level().isClientSide) {
            level().playSound(null, hitResult.getBlockPos(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.3F, 2.0F);
            if (level() instanceof ServerLevel sl) {
                BlockState state = level().getBlockState(hitResult.getBlockPos());
                sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                        hitResult.getLocation().x, hitResult.getLocation().y, hitResult.getLocation().z, 6, 0.1, 0.1, 0.1, 0.05);
            }
            discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (level().isClientSide) return;

        Entity hitEntity = hitResult.getEntity();
        if (!(hitEntity instanceof LivingEntity living)) {
            discard();
            return;
        }

        // 1. Selective shield penetration:
        if (living.isBlocking()) {
            ItemStack useItem = living.getUseItem();
            boolean isBulletproof = useItem.is(BULLETPROOF_SHIELDS)
                    || useItem.getItem().getDescriptionId().contains("redoubt")
                    || useItem.getItem().getDescriptionId().contains("upgraded_shield");

            if (isBulletproof) {
                // Upgraded shield successfully absorbs the rifle bullet
                useItem.hurtAndBreak(4, living, EquipmentSlot.MAINHAND);
                level().playSound(null, living.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0F, 0.9F);
                if (level() instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.CRIT, getX(), getY(), getZ(), 10, 0.2, 0.2, 0.2, 0.1);
                }
                discard();
                return;
            } else {
                // Wooden / regular shield is shattered by high-velocity rifle round!
                if (living instanceof Player player) {
                    player.getCooldowns().addCooldown(useItem.getItem(), 100);
                    level().playSound(null, player.blockPosition(), SoundEvents.SHIELD_BREAK, SoundSource.PLAYERS, 1.2F, 1.0F);
                }
            }
        }

        // 2. Armor Piercing calculation:
        // Base damage: 6.0 HP (unarmored).
        // Armor reduced damage: exactly 2.0 HP (1.0 heart) against Full Protection IV Netherite gear.
        float armor = living.getArmorValue();
        float toughness = (float) living.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        DamageSource testSource = level().damageSources().thrown(this, getOwner());
        float afterArmor = CombatRules.getDamageAfterAbsorb(living, 6.0F, testSource, armor, toughness);
        float protPoints = (level() instanceof ServerLevel sl) ? EnchantmentHelper.getDamageProtection(sl, living, testSource) : 0.0F;
        float afterMagic = CombatRules.getDamageAfterMagicAbsorb(afterArmor, protPoints);

        float vanillaMitigationFraction = Math.clamp((6.0F - afterMagic) / 6.0F, 0.0F, 1.0F);
        // Scales vanilla max 91.072% reduction down to 66.667% (so remaining damage is 2.0F / 6.0F = 1 heart)
        float scaledMitigation = vanillaMitigationFraction * 0.732025F;
        float finalDamage = Math.max(1.0F, 6.0F * (1.0F - scaledMitigation));

        DamageSource bulletDamageSource;
        try {
            bulletDamageSource = level().damageSources().source(RIFLE_BULLET_DAMAGE_TYPE, this, getOwner());
        } catch (Exception e) {
            bulletDamageSource = level().damageSources().thrown(this, getOwner());
        }
        living.hurt(bulletDamageSource, finalDamage);

        if (level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.DAMAGE_INDICATOR, getX(), getY(), getZ(), 3, 0.1, 0.1, 0.1, 0.02);
        }
        discard();
    }
}
