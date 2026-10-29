package com.warfront.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

/**
 * Ballistic explosive cannon bomb fired by the Pillager Cannoneer.
 * Features tangible weight with an upward ballistic arc; detonates on impact dealing
 * 8.0 damage and radial explosion knockback without breaking any blocks.
 */
public class CannonBombEntity extends Projectile implements GeoEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int ticksInAir = 0;

    public CannonBombEntity(EntityType<? extends CannonBombEntity> entityType, Level level) {
        super(entityType, level);
    }

    public CannonBombEntity(Level level, LivingEntity shooter) {
        this(ModEntities.CANNON_BOMB.get(), level);
        setOwner(shooter);
        setPos(shooter.getX(), shooter.getEyeY() - 0.1D, shooter.getZ());
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

        // Weighted ballistic gravity arc (0.085 blocks/tick^2)
        setDeltaMovement(movement.x, movement.y - 0.085D, movement.z);

        // Orient rotation facing trajectory
        double horizDist = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        setYRot((float) (Mth.atan2(movement.x, movement.z) * (180.0D / Math.PI)));
        setXRot((float) (Mth.atan2(movement.y, horizDist) * (180.0D / Math.PI)));
        this.yRotO = getYRot();
        this.xRotO = getXRot();

        setPos(nextX, nextY, nextZ);

        if (level().isClientSide) {
            level().addParticle(ParticleTypes.SMOKE, getX(), getY(), getZ(), 0.0D, 0.01D, 0.0D);
            level().addParticle(ParticleTypes.SMALL_FLAME, getX(), getY(), getZ(), 0.0D, 0.005D, 0.0D);
        } else {
            if (ticksInAir > 200 || getY() < level().getMinBuildHeight() - 10) {
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
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (!level().isClientSide && hitResult.getEntity() instanceof LivingEntity living) {
            if (!AlliedFactionHelper.isAllied(getOwner(), living)) {
                living.hurt(level().damageSources().thrown(this, getOwner()), 8.0F);
            }
        }
    }

    private void explode() {
        if (level() instanceof ServerLevel serverLevel) {
            // Visual and audio feedback: guaranteed non-destructive (Level.ExplosionInteraction.NONE)
            serverLevel.sendParticles(ParticleTypes.EXPLOSION, getX(), getY(), getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
            serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY(), getZ(), 8, 0.3D, 0.3D, 0.3D, 0.05D);
            serverLevel.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.5F, 1.3F);

            // Splash concussive blast dealing 8 damage with explosive knockback
            final double splashRadius = 4.0D;
            AABB area = getBoundingBox().inflate(splashRadius);
            List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class, area, this::canHitEntity);
            for (LivingEntity target : targets) {
                if (target == getOwner()) continue;
                if (AlliedFactionHelper.isAllied(getOwner(), target)) continue;

                double dist = distanceTo(target);
                if (dist <= splashRadius) {
                    target.hurt(serverLevel.damageSources().explosion(this, getOwner()), 12.0F);

                    // Explosive knockback away from detonation center
                    Vec3 knockVec = target.position().subtract(position()).normalize().scale(1.1D);
                    target.push(knockVec.x, 0.40D, knockVec.z);
                    target.hurtMarked = true;
                }
            }
        }
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (!super.canHitEntity(target)) {
            return false;
        }
        if (AlliedFactionHelper.isAllied(this.getOwner(), target)) {
            return false;
        }
        return true;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
