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
 * Ballistic explosive cannonball fired by the Pillager Cannoneer.
 * Direct-fire with slight gravity arc; detonates on impact dealing 10.0 impact damage
 * and a 2-block concussive blast that knocks players off ladders/ramparts without breaking blocks.
 */
public class CannonballEntity extends Projectile implements GeoEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int ticksInAir = 0;

    public CannonballEntity(EntityType<? extends CannonballEntity> entityType, Level level) {
        super(entityType, level);
    }

    public CannonballEntity(Level level, LivingEntity shooter) {
        this(ModEntities.CANNONBALL.get(), level);
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

        // Slight ballistic gravity arc (0.035 blocks/tick^2)
        setDeltaMovement(movement.x, movement.y - 0.035D, movement.z);

        // Orient rotation facing trajectory
        double horizDist = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        setYRot((float) (Mth.atan2(movement.x, movement.z) * (180.0D / Math.PI)));
        setXRot((float) (Mth.atan2(movement.y, horizDist) * (180.0D / Math.PI)));
        this.yRotO = getYRot();
        this.xRotO = getXRot();

        setPos(nextX, nextY, nextZ);

        if (level().isClientSide) {
            level().addParticle(ParticleTypes.SMOKE, getX(), getY(), getZ(), 0.0D, 0.01D, 0.0D);
            level().addParticle(ParticleTypes.FLAME, getX(), getY(), getZ(), 0.0D, 0.005D, 0.0D);
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
            living.hurt(level().damageSources().thrown(this, getOwner()), 10.0F);
        }
    }

    private void explode() {
        if (level() instanceof ServerLevel serverLevel) {
            // Visual and audio feedback: fortress safe (Level.ExplosionInteraction.NONE)
            serverLevel.sendParticles(ParticleTypes.EXPLOSION, getX(), getY(), getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
            serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY(), getZ(), 8, 0.3D, 0.3D, 0.3D, 0.05D);
            serverLevel.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3.0F, 1.2F);

            // Splash concussive blast (2.0 blocks radius)
            final double splashRadius = 2.0D;
            AABB area = getBoundingBox().inflate(splashRadius);
            List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class, area, this::canHitEntity);
            for (LivingEntity target : targets) {
                if (target == getOwner()) continue;
                if (AlliedFactionHelper.isAllied(getOwner(), target)) continue;

                double dist = distanceTo(target);
                if (dist <= splashRadius) {
                    float damage = (float) (6.0F * (1.0D - (dist / splashRadius)));
                    target.hurt(serverLevel.damageSources().explosion(this, getOwner()), Math.max(2.0F, damage));

                    // Heavy knockback away from detonation center (ladder-clearing knockback)
                    Vec3 knockVec = target.position().subtract(position()).normalize().scale(1.2D);
                    target.push(knockVec.x, 0.4D, knockVec.z);
                    target.hurtMarked = true;
                }
            }
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
