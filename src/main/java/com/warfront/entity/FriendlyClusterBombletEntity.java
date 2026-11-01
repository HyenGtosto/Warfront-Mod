package com.warfront.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

/**
 * Dedicated submunition bomblet deployed by the Friendly Mortar's Airburst Shell.
 *
 * Plunges from the 20-block detonation altitude, covering a static 15-meter footprint.
 * Features strictly zero knockback on impact to prevent throwing mobs into chaotic trajectories.
 */
public class FriendlyClusterBombletEntity extends Projectile implements GeoEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int ticksInAir = 0;

    public FriendlyClusterBombletEntity(EntityType<? extends FriendlyClusterBombletEntity> entityType, Level level) {
        super(entityType, level);
        this.noCulling = true;
    }

    public FriendlyClusterBombletEntity(Level level, double x, double y, double z) {
        this(ModEntities.FRIENDLY_CLUSTER_BOMBLET.get(), level);
        setPos(x, y, z);
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
            if (hitResult.getType() == HitResult.Type.BLOCK && hitResult instanceof BlockHitResult blockHit) {
                BlockState state = level().getBlockState(blockHit.getBlockPos());
                if (!isPassableBlock(state, blockHit.getBlockPos())) {
                    onHit(hitResult);
                    return;
                }
            } else {
                onHit(hitResult);
                return;
            }
        }

        double nextX = getX() + movement.x;
        double nextY = getY() + movement.y;
        double nextZ = getZ() + movement.z;

        // Downward gravity (0.05 blocks/tick^2)
        setDeltaMovement(movement.x, movement.y - 0.05D, movement.z);

        double horizDist = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        setYRot((float) (Mth.atan2(movement.x, movement.z) * (180.0D / Math.PI)));
        setXRot((float) (Mth.atan2(movement.y, horizDist) * (180.0D / Math.PI)));
        this.yRotO = getYRot();
        this.xRotO = getXRot();

        setPos(nextX, nextY, nextZ);

        if (level().isClientSide) {
            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY(), getZ(), 0.0D, 0.01D, 0.0D);
            level().addParticle(ParticleTypes.CRIT, getX(), getY(), getZ(), 0.0D, 0.005D, 0.0D);
        } else {
            if (ticksInAir > 160 || getY() < level().getMinBuildHeight() - 10) {
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
            if (living != getOwner()) {
                living.hurt(level().damageSources().thrown(this, getOwner()), 14.0F);
            }
        }
    }

    private void explode() {
        if (!(level() instanceof ServerLevel serverLevel)) return;

        serverLevel.sendParticles(ParticleTypes.EXPLOSION, getX(), getY(), getZ(), 1, 0.0D, 0.0D, 0.0D, 0.0D);
        serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY(), getZ(), 5, 0.2D, 0.2D, 0.2D, 0.02D);
        serverLevel.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 2.5F, 1.4F);

        // 3.5m splash shrapnel damage with strictly zero knockback
        final double splashRadius = 3.5D;
        AABB area = getBoundingBox().inflate(splashRadius);
        List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class, area, this::canHitEntity);
        for (LivingEntity target : targets) {
            if (target == getOwner()) continue;

            double dist = distanceTo(target);
            if (dist <= splashRadius) {
                // Bypass vanilla invulnerability frames so overlapping submunition blasts stack
                target.invulnerableTime = 0;
                target.hurt(serverLevel.damageSources().explosion(this, getOwner()), 14.0F);
            }
        }
    }

    private boolean isPassableBlock(BlockState state, BlockPos pos) {
        return state.getCollisionShape(level(), pos).isEmpty()
                || !state.blocksMotion()
                || state.canBeReplaced()
                || state.is(BlockTags.REPLACEABLE);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (!super.canHitEntity(target)) {
            return false;
        }
        if (target instanceof FriendlyArtilleryShellEntity || target instanceof FriendlyClusterBombletEntity || target instanceof SmokeScreenEntity) {
            return false;
        }
        if (getOwner() != null && AlliedFactionHelper.isAllied(getOwner(), target)) {
            return false;
        }
        return true;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.ticksInAir = tag.getInt("TicksInAir");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("TicksInAir", this.ticksInAir);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
