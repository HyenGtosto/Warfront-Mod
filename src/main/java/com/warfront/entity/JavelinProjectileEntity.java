package com.warfront.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Thrown Pillager javelin projectile used by Pillager Legionnaire.
 * Deals impact damage (respecting active vanilla shield blocks and Warfront alliances)
 * and embeds physically in the ground/surface so its owner can march over and retrieve it.
 */
public class JavelinProjectileEntity extends Projectile implements GeoEntity {

    public static final double GRAVITY = 0.045D;
    public static final double AIR_DRAG = 0.99D;
    public static final float DAMAGE = 8.0F;
    public static final float PLAYER_THROW_DAMAGE = 16.0F;
    private static final int MAX_STUCK_TICKS = 1200; // 60 seconds safety TTL
    private static final int PLAYER_MAX_STUCK_TICKS = 60; // 3 seconds decorative embed for single-use player throws

    private static final EntityDataAccessor<Boolean> IN_GROUND =
            SynchedEntityData.defineId(JavelinProjectileEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> RENDER_YAW =
            SynchedEntityData.defineId(JavelinProjectileEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> RENDER_PITCH =
            SynchedEntityData.defineId(JavelinProjectileEntity.class, EntityDataSerializers.FLOAT);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private int inGroundTicks = 0;
    private int ticksInAir = 0;
    private boolean struckEntity = false;
    private float impactDamage = DAMAGE;
    private boolean playerThrown = false;
    @Nullable
    private BlockPos stuckBlockPos = null;
    @Nullable
    private UUID legionnaireOwnerUuid = null;

    public JavelinProjectileEntity(EntityType<? extends JavelinProjectileEntity> entityType, Level level) {
        super(entityType, level);
        this.noCulling = true;
    }

    public JavelinProjectileEntity(Level level, PillagerLegionnaireEntity owner, double x, double y, double z) {
        this(ModEntities.JAVELIN_PROJECTILE.get(), level);
        this.setOwner(owner);
        this.legionnaireOwnerUuid = owner.getUUID();
        this.impactDamage = DAMAGE;
        this.playerThrown = false;
        this.setPos(x, y, z);
    }

    public JavelinProjectileEntity(Level level, Player shooter) {
        this(ModEntities.JAVELIN_PROJECTILE.get(), level);
        this.setOwner(shooter);
        this.impactDamage = PLAYER_THROW_DAMAGE;
        this.playerThrown = true;
        this.setPos(shooter.getX(), shooter.getEyeY() - 0.1D, shooter.getZ());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(IN_GROUND, false);
        builder.define(RENDER_YAW, 0.0F);
        builder.define(RENDER_PITCH, 0.0F);
    }

    public boolean isInGround() {
        return this.entityData.get(IN_GROUND);
    }

    public float getRenderYaw() {
        return this.entityData.get(RENDER_YAW);
    }

    public float getRenderPitch() {
        return this.entityData.get(RENDER_PITCH);
    }

    public void syncOrientationFromVelocity(Vec3 vel) {
        double horiz = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (horiz > 1.0E-4D || Math.abs(vel.y) > 1.0E-4D) {
            float yaw = (float) Math.toDegrees(Math.atan2(vel.x, -vel.z));
            float pitch = (float) Math.toDegrees(Math.atan2(vel.y, Math.max(1.0E-4D, horiz)));
            this.entityData.set(RENDER_YAW, yaw);
            this.entityData.set(RENDER_PITCH, pitch);
            this.setYRot(yaw);
            this.setXRot(pitch);
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 16384.0D; // 128 blocks
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            if (this.isInLava() || this.getY() < this.level().getMinBuildHeight() - 8) {
                this.discard();
                return;
            }
        }

        if (this.isInGround()) {
            this.setDeltaMovement(Vec3.ZERO);
            if (!this.level().isClientSide && this.level() instanceof ServerLevel serverLevel) {
                this.inGroundTicks++;
                int maxStuck = this.playerThrown ? PLAYER_MAX_STUCK_TICKS : MAX_STUCK_TICKS;
                if (this.inGroundTicks >= maxStuck) {
                    this.discard();
                    return;
                }
                // Fall if supporting block was broken
                if (this.stuckBlockPos != null) {
                    BlockState state = serverLevel.getBlockState(this.stuckBlockPos);
                    if (state.getCollisionShape(serverLevel, this.stuckBlockPos).isEmpty()) {
                        this.entityData.set(IN_GROUND, false);
                        this.stuckBlockPos = null;
                        this.inGroundTicks = 0;
                        this.setDeltaMovement(new Vec3(0.0D, -0.05D, 0.0D));
                        return;
                    }
                }
                // Remove orphan stuck Legionnaire javelin quickly if owner died
                if (!this.playerThrown && this.inGroundTicks > 100 && this.legionnaireOwnerUuid != null) {
                    Entity owner = serverLevel.getEntity(this.legionnaireOwnerUuid);
                    if (owner == null || !owner.isAlive()) {
                        this.discard();
                    }
                }
            }
            return;
        }

        this.ticksInAir++;
        Vec3 movement = this.getDeltaMovement();
        Vec3 start = this.position();
        Vec3 end = start.add(movement);

        if (!this.level().isClientSide) {
            BlockHitResult blockHit = this.level().clip(new ClipContext(
                    start,
                    end,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    this
            ));
            Vec3 entityScanEnd = blockHit.getType() != HitResult.Type.MISS ? blockHit.getLocation() : end;
            EntityHitResult entityHit = this.struckEntity
                    ? null
                    : ProjectileUtil.getEntityHitResult(
                            this.level(),
                            this,
                            start,
                            entityScanEnd,
                            this.getBoundingBox().expandTowards(movement).inflate(0.35D),
                            this::canHitEntity
                    );

            if (entityHit != null) {
                onHitEntity(entityHit);
                // After entity deflect/hit, snap or continue falling to ground
                movement = this.getDeltaMovement();
                end = this.position().add(movement);
            } else if (blockHit.getType() == HitResult.Type.BLOCK) {
                onHitBlock(blockHit);
                return;
            }
        }

        Vec3 nextPos = start.add(movement);
        this.setPos(nextPos.x, nextPos.y, nextPos.z);

        Vec3 nextVel = movement.scale(AIR_DRAG).add(0.0D, -GRAVITY, 0.0D);
        this.setDeltaMovement(nextVel);
        if (!this.level().isClientSide) {
            syncOrientationFromVelocity(nextVel);
        }

        if (!this.level().isClientSide && this.ticksInAir > 400) {
            this.discard();
        }
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (!super.canHitEntity(target)) return false;
        Entity owner = this.getOwner();
        if (target == owner) return false;
        if (owner != null && AlliedFactionHelper.isAllied(owner, target)) return false;
        return true;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        this.struckEntity = true;
        Entity hitEntity = result.getEntity();
        Entity owner = this.getOwner();

        LivingEntity livingOwner = owner instanceof LivingEntity le ? le : null;
        DamageSource source = this.playerThrown
                ? this.damageSources().trident(this, owner != null ? owner : this)
                : this.damageSources().mobProjectile(this, livingOwner);

        boolean blocked = false;
        if (hitEntity instanceof LivingEntity living && living.isDamageSourceBlocked(source)) {
            blocked = true;
            if (living instanceof Player player) {
                ItemStack useItem = player.getUseItem();
                if (!useItem.isEmpty() && useItem.isDamageableItem()) {
                    EquipmentSlot slot = player.getUsedItemHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                            ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
                    useItem.hurtAndBreak(Mth.ceil(this.impactDamage), player, slot);
                }
            }
            this.playSound(SoundEvents.SHIELD_BLOCK, 1.0F, 0.9F + this.random.nextFloat() * 0.2F);
        } else {
            hitEntity.hurt(source, this.impactDamage);
            this.playSound(SoundEvents.TRIDENT_HIT, 1.0F, 1.05F);
            if (this.level() instanceof ServerLevel sl) {
                Vec3 loc = result.getLocation();
                sl.sendParticles(ParticleTypes.CRIT, loc.x, loc.y, loc.z, 6, 0.1D, 0.1D, 0.1D, 0.1D);
            }
        }

        // Deflect downward nearby so the thrown javelin always falls onto the ground for retrieval
        Vec3 cur = this.getDeltaMovement();
        double bounceScale = blocked ? -0.18D : -0.10D;
        Vec3 dropVel = new Vec3(cur.x * bounceScale, 0.10D, cur.z * bounceScale);
        this.setPos(result.getLocation());
        this.setDeltaMovement(dropVel);
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        this.entityData.set(IN_GROUND, true);
        this.stuckBlockPos = result.getBlockPos();
        this.inGroundTicks = 0;
        syncOrientationFromVelocity(this.getDeltaMovement());
        Vec3 loc = result.getLocation();
        this.setPos(loc.x, loc.y, loc.z);
        this.setDeltaMovement(Vec3.ZERO);
        this.playSound(SoundEvents.TRIDENT_HIT_GROUND, 1.0F, 1.0F);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putBoolean("InGround", isInGround());
        compound.putInt("InGroundTicks", this.inGroundTicks);
        compound.putBoolean("StruckEntity", this.struckEntity);
        compound.putFloat("ImpactDamage", this.impactDamage);
        compound.putBoolean("PlayerThrown", this.playerThrown);
        compound.putFloat("RenderYaw", getRenderYaw());
        compound.putFloat("RenderPitch", getRenderPitch());
        if (this.legionnaireOwnerUuid != null) {
            compound.putUUID("LegionnaireOwner", this.legionnaireOwnerUuid);
        }
        if (this.stuckBlockPos != null) {
            compound.putInt("StuckX", this.stuckBlockPos.getX());
            compound.putInt("StuckY", this.stuckBlockPos.getY());
            compound.putInt("StuckZ", this.stuckBlockPos.getZ());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.contains("InGround")) {
            this.entityData.set(IN_GROUND, compound.getBoolean("InGround"));
        }
        this.inGroundTicks = compound.getInt("InGroundTicks");
        this.struckEntity = compound.getBoolean("StruckEntity");
        if (compound.contains("ImpactDamage")) {
            this.impactDamage = compound.getFloat("ImpactDamage");
        }
        this.playerThrown = compound.getBoolean("PlayerThrown");
        if (compound.contains("RenderYaw")) {
            this.entityData.set(RENDER_YAW, compound.getFloat("RenderYaw"));
        }
        if (compound.contains("RenderPitch")) {
            this.entityData.set(RENDER_PITCH, compound.getFloat("RenderPitch"));
        }
        if (compound.hasUUID("LegionnaireOwner")) {
            this.legionnaireOwnerUuid = compound.getUUID("LegionnaireOwner");
        }
        if (compound.contains("StuckX")) {
            this.stuckBlockPos = new BlockPos(
                    compound.getInt("StuckX"),
                    compound.getInt("StuckY"),
                    compound.getInt("StuckZ")
            );
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Static projectile model; orientation driven by JavelinProjectileRenderer
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
