package com.warfront.entity;

import com.warfront.faction.PlayerFactionData;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Supersonic piercing bolt fired by the Giant Ballista.
 *
 * Characteristics:
 * - Travels at 7.0 blocks/tick (140 m/s) with flat 0.005 gravity.
 * - Uses 4 sub-steps per tick along its flight vector to prevent high-speed entity/block tunneling.
 * - Slices through multiple living targets without stopping (20.0F piercing damage, bypasses invulnerableTime).
 * - Generates expanding sonic boom rings and applies perpendicular outward knockback (no status effects)
 *   to non-impaled hostile entities within a 2.0m shockwave radius.
 * - Bypasses non-blocking foliage (grass, flowers, ferns) and halts immediately upon hitting a solid block.
 */
public class BallistaBoltEntity extends Projectile implements GeoEntity {

    public static final double GRAVITY = 0.005D;
    public static final float PIERCING_DAMAGE = 20.0F;
    public static final double SHOCKWAVE_RADIUS = 4.0D;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final IntOpenHashSet hitEntityIds = new IntOpenHashSet();
    private final IntOpenHashSet shockwavedEntityIds = new IntOpenHashSet();

    private int ticksInAir = 0;
    private double distanceTraveled = 0.0D;
    private double nextSonicRingDist = 1.5D;
    private boolean pillagerFired = true;
    private UUID ownerFactionId = null;

    public BallistaBoltEntity(EntityType<? extends BallistaBoltEntity> entityType, Level level) {
        super(entityType, level);
        this.noCulling = true;
    }

    public BallistaBoltEntity(Level level, double x, double y, double z, boolean pillagerFired, UUID ownerFactionId) {
        this(ModEntities.BALLISTA_BOLT.get(), level);
        setPos(x, y, z);
        this.pillagerFired = pillagerFired;
        this.ownerFactionId = ownerFactionId;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 65536.0D; // 256 blocks
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void tick() {
        super.tick();
        ticksInAir++;

        Vec3 movement = getDeltaMovement();
        double speed = movement.length();
        if (speed < 1.0E-4D) {
            if (!level().isClientSide && ticksInAir > 5) {
                discard();
            }
            return;
        }

        Vec3 startPos = position();
        Vec3 endPos = startPos.add(movement);

        if (!level().isClientSide && level() instanceof ServerLevel serverLevel) {
            // 1. Sub-stepped solid block raycast (bypassing grass, flowers, ferns, and non-colliding foliage)
            Vec3 currentRayStart = startPos;
            boolean hitSolidBlock = false;
            BlockPos solidHitPos = null;

            for (int attempt = 0; attempt < 8; attempt++) {
                BlockHitResult blockHit = serverLevel.clip(new ClipContext(
                        currentRayStart,
                        endPos,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        this
                ));
                if (blockHit.getType() == HitResult.Type.MISS) {
                    break;
                }
                BlockPos hitPos = blockHit.getBlockPos();
                BlockState hitState = serverLevel.getBlockState(hitPos);
                if (isPassableBlock(hitState, hitPos)) {
                    // Advance ray slightly past this passable block
                    Vec3 dir = movement.normalize();
                    currentRayStart = blockHit.getLocation().add(dir.scale(0.15D));
                    if (currentRayStart.distanceToSqr(startPos) >= endPos.distanceToSqr(startPos)) {
                        break;
                    }
                } else {
                    endPos = blockHit.getLocation();
                    hitSolidBlock = true;
                    solidHitPos = hitPos;
                    break;
                }
            }

            // 2. Swept-segment piercing entity collision & sonic boom shockwave knockback
            processSweptEntitiesAndSonicBoom(serverLevel, startPos, endPos, movement.normalize());

            // 3. Emit Sonic Boom rings along the segment every ~2.5 blocks
            double segmentLen = startPos.distanceTo(endPos);
            Vec3 dir = segmentLen > 1.0E-4D ? endPos.subtract(startPos).scale(1.0D / segmentLen) : movement.normalize();
            double prevDist = this.distanceTraveled;
            this.distanceTraveled += segmentLen;

            while (this.nextSonicRingDist <= this.distanceTraveled) {
                double offsetAlongSegment = this.nextSonicRingDist - prevDist;
                Vec3 ringPos = startPos.add(dir.scale(offsetAlongSegment));
                serverLevel.sendParticles(ParticleTypes.SONIC_BOOM, ringPos.x, ringPos.y, ringPos.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
                serverLevel.sendParticles(ParticleTypes.CLOUD, ringPos.x, ringPos.y, ringPos.z, 6, 0.25D, 0.25D, 0.25D, 0.04D);
                this.nextSonicRingDist += 2.5D;
            }

            // 4. Stop immediately if a solid block was struck
            if (hitSolidBlock && solidHitPos != null) {
                serverLevel.sendParticles(ParticleTypes.CRIT, endPos.x, endPos.y, endPos.z, 18, 0.2D, 0.2D, 0.2D, 0.25D);
                serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, endPos.x, endPos.y, endPos.z, 6, 0.15D, 0.15D, 0.15D, 0.03D);
                serverLevel.playSound(null, solidHitPos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1.8F, 1.35F);
                serverLevel.playSound(null, solidHitPos, SoundEvents.ARROW_HIT, SoundSource.BLOCKS, 2.5F, 0.65F);
                discard();
                return;
            }

            if (ticksInAir > 200 || getY() < serverLevel.getMinBuildHeight() - 20) {
                discard();
                return;
            }
        } else if (level().isClientSide) {
            // Client high-speed vapor trail
            for (int i = 0; i < 4; i++) {
                double t = i / 4.0D;
                double px = Mth.lerp(t, startPos.x, endPos.x);
                double py = Mth.lerp(t, startPos.y, endPos.y);
                double pz = Mth.lerp(t, startPos.z, endPos.z);
                level().addParticle(ParticleTypes.CRIT, px, py, pz, 0.0D, 0.0D, 0.0D);
            }
        }

        // Update position and flat ballistic gravity
        setDeltaMovement(movement.x, movement.y - GRAVITY, movement.z);

        double horizDist = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        setYRot((float) (Mth.atan2(movement.x, movement.z) * (180.0D / Math.PI)));
        setXRot((float) (Mth.atan2(movement.y, horizDist) * (180.0D / Math.PI)));
        this.yRotO = getYRot();
        this.xRotO = getXRot();

        setPos(endPos.x, endPos.y, endPos.z);
    }

    private void processSweptEntitiesAndSonicBoom(ServerLevel serverLevel, Vec3 startPos, Vec3 endPos, Vec3 dir) {
        AABB sweptBox = new AABB(startPos, endPos).inflate(SHOCKWAVE_RADIUS + 1.0D);
        List<LivingEntity> candidates = serverLevel.getEntitiesOfClass(LivingEntity.class, sweptBox, this::canAffectEntity);

        for (LivingEntity target : candidates) {
            int id = target.getId();
            if (this.hitEntityIds.contains(id)) {
                continue;
            }

            // Check direct piercing intersection against expanded entity hitbox
            AABB targetBox = target.getBoundingBox().inflate(0.35D);
            Optional<Vec3> clipHit = targetBox.clip(startPos, endPos);
            boolean directHit = clipHit.isPresent() || targetBox.contains(startPos) || targetBox.contains(endPos);

            if (directHit) {
                this.hitEntityIds.add(id);
                this.shockwavedEntityIds.add(id);

                target.invulnerableTime = 0;
                DamageSource src = serverLevel.damageSources().thrown(this, getOwner());
                boolean hurt = target.hurt(src, PIERCING_DAMAGE);
                target.invulnerableTime = 0;

                if (hurt) {
                    // Devastating forward kinetic punch along bolt trajectory
                    target.push(dir.x * 1.1D, 0.25D, dir.z * 1.1D);
                    target.hurtMarked = true;
                }

                serverLevel.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.5D), target.getZ(), 15, 0.25D, 0.25D, 0.25D, 0.2D);
                serverLevel.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 2.0F, 0.7F);
                continue;
            }

            // Near-miss Sonic Boom Shockwave (Option B - Knockback Only within 2.0m perpendicular distance)
            if (!this.shockwavedEntityIds.contains(id)) {
                Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
                Vec3 closestPoint = closestPointOnSegment(startPos, endPos, targetCenter);
                double perpDist = targetCenter.distanceTo(closestPoint);

                if (perpDist <= SHOCKWAVE_RADIUS) {
                    this.shockwavedEntityIds.add(id);
                    Vec3 outward = targetCenter.subtract(closestPoint);
                    double outHorizLen = Math.sqrt(outward.x * outward.x + outward.z * outward.z);
                    double falloff = 1.0D - (perpDist / SHOCKWAVE_RADIUS) * 0.5D;
                    double kbStrength = 0.85D * falloff;

                    if (outHorizLen > 1.0E-4D) {
                        target.push((outward.x / outHorizLen) * kbStrength, 0.25D * falloff, (outward.z / outHorizLen) * kbStrength);
                    } else {
                        target.push(dir.x * kbStrength, 0.25D * falloff, dir.z * kbStrength);
                    }
                    target.hurtMarked = true;
                }
            }
        }
    }

    private static Vec3 closestPointOnSegment(Vec3 a, Vec3 b, Vec3 p) {
        Vec3 ab = b.subtract(a);
        double lenSq = ab.lengthSqr();
        if (lenSq < 1.0E-6D) return a;
        double t = Mth.clamp(p.subtract(a).dot(ab) / lenSq, 0.0D, 1.0D);
        return a.add(ab.scale(t));
    }

    private boolean canAffectEntity(LivingEntity target) {
        if (!target.isAlive() || target.isSpectator()) {
            return false;
        }
        if (target instanceof Player player && player.isCreative()) {
            return false;
        }
        if (this.pillagerFired) {
            if (AlliedFactionHelper.isPillager(target)) {
                return false;
            }
        } else if (this.ownerFactionId != null && level() instanceof ServerLevel serverLevel) {
            if (target instanceof Player player) {
                PlayerFactionData factionData = PlayerFactionData.get(serverLevel);
                var playerFac = factionData.getFactionByPlayer(player.getUUID());
                if (playerFac != null && this.ownerFactionId.equals(playerFac.getId())) {
                    return false;
                }
            }
            if (getOwner() != null && AlliedFactionHelper.isAllied(getOwner(), target)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPassableBlock(BlockState state, BlockPos pos) {
        if (state.isAir() || !state.blocksMotion() || state.canBeReplaced()) {
            return true;
        }
        if (state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS) || state.is(BlockTags.CROPS)) {
            return true;
        }
        return state.getCollisionShape(level(), pos).isEmpty();
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        this.ticksInAir = compound.getInt("TicksInAir");
        this.pillagerFired = !compound.contains("PillagerFired") || compound.getBoolean("PillagerFired");
        if (compound.hasUUID("OwnerFactionId")) {
            this.ownerFactionId = compound.getUUID("OwnerFactionId");
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putInt("TicksInAir", this.ticksInAir);
        compound.putBoolean("PillagerFired", this.pillagerFired);
        if (this.ownerFactionId != null) {
            compound.putUUID("OwnerFactionId", this.ownerFactionId);
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
