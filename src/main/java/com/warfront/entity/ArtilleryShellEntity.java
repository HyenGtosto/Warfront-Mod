package com.warfront.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Heavy ballistic artillery shell fired by the Pillager Heavy Mortar.
 * Affected by gravity, plunges from high altitude, and produces massive concussive detonations on impact.
 */
public class ArtilleryShellEntity extends Projectile implements GeoEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int ticksInAir = 0;

    public ArtilleryShellEntity(EntityType<? extends ArtilleryShellEntity> entityType, Level level) {
        super(entityType, level);
        this.noCulling = true;
    }

    public ArtilleryShellEntity(Level level, double x, double y, double z) {
        this(ModEntities.ARTILLERY_SHELL.get(), level);
        setPos(x, y, z);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 65536.0D; // 256 * 256 blocks
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
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

        // Apply downward ballistic gravity (0.05 blocks/tick^2)
        setDeltaMovement(movement.x, movement.y - 0.05D, movement.z);

        // Orient shell rotation facing trajectory
        double horizDist = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        setYRot((float) (Mth.atan2(movement.x, movement.z) * (180.0D / Math.PI)));
        setXRot((float) (Mth.atan2(movement.y, horizDist) * (180.0D / Math.PI)));
        this.yRotO = getYRot();
        this.xRotO = getXRot();

        setPos(nextX, nextY, nextZ);

        if (level().isClientSide) {
            // Flight particle smoke trail
            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY(), getZ(), 0.0D, 0.02D, 0.0D);
            level().addParticle(ParticleTypes.SMALL_FLAME, getX(), getY(), getZ(), 0.0D, 0.01D, 0.0D);
        } else {
            // Despawn after 30 seconds if never hits ground
            if (ticksInAir > 600 || getY() < level().getMinBuildHeight() - 20) {
                discard();
            }
        }
    }

    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);
        if (!level().isClientSide) {
            explode();
            discard();
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult hitResult) {
        super.onHitBlock(hitResult);
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (!level().isClientSide && hitResult.getEntity() instanceof LivingEntity living) {
            // Direct kinetic impact: devastating direct hit bonus
            living.hurt(level().damageSources().thrown(this, getOwner()), 12.0F);
        }
    }

    private void explode() {
        if (level() instanceof ServerLevel serverLevel) {
            // 1. Crater: Controlled 1.6F power destroys 5-6 dirt blocks (preventing moon craters)
            // Entity damage is disabled for this block-pass explosion so it doesn't interfere with our calibrated radius/damage.
            ExplosionDamageCalculator blockOnlyCalc = new ExplosionDamageCalculator() {
                @Override
                public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
                    return false;
                }
            };
            serverLevel.explode(this, null, blockOnlyCalc, getX(), getY(), getZ(), 1.6F, false, Level.ExplosionInteraction.BLOCK);
            serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY(), getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
            serverLevel.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 5.0F, 0.75F);

            // 2. Wide Lethality Radius: 5.0 blocks
            // Direct hit (dist <= 1.0) inflicts 25.0 raw damage:
            // Calculated against Netherite Armor (20 armor, 12 toughness):
            // defensePoints = clamp(20 - 25/5, 4, 20) = 15
            // damageMultiplier = 1 - 15/25 = 0.40
            // finalDamage = 25.0 * 0.40 = 10.0 exact damage (5 hearts)!
            // Non-direct hit falls off smoothly so players aren't one-shot unless direct hit.
            // Blast Protection enchantment applies via damageSources().explosion().
            final double maxDamageRadius = 5.0D;
            AABB damageArea = this.getBoundingBox().inflate(maxDamageRadius);
            List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class, damageArea);
            Vec3 explosionCenter = this.position();

            for (LivingEntity target : targets) {
                double dist = target.position().distanceTo(explosionCenter);
                if (dist <= maxDamageRadius) {
                    float exposure = Explosion.getSeenPercent(explosionCenter, target);
                    if (exposure > 0.0F) {
                        double distRatio = Math.max(0.0D, dist - 1.0D) / (maxDamageRadius - 1.0D);
                        // At direct hit (dist <= 1.0), rawDamage = 25.0 (10 damage to Netherite)
                        // At outer edge (dist = 5.0), rawDamage = 10.0 (4 damage to Netherite)
                        double falloff = 1.0D - distRatio * 0.60D;
                        float rawDamage = (float) (25.0D * falloff * exposure);

                        DamageSource src = serverLevel.damageSources().explosion(this, getOwner());
                        target.hurt(src, rawDamage);

                        Vec3 diff = target.position().subtract(explosionCenter);
                        double len = diff.length();
                        if (len > 0.001D) {
                            Vec3 knockback = diff.scale((1.0D - (dist / maxDamageRadius)) * 0.5D * exposure / len);
                            target.push(knockback.x, 0.20D, knockback.z);
                        }
                    }
                }
            }
        }
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return super.canHitEntity(target) && !(target instanceof ArtilleryShellEntity);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        this.ticksInAir = compound.getInt("TicksInAir");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putInt("TicksInAir", this.ticksInAir);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
