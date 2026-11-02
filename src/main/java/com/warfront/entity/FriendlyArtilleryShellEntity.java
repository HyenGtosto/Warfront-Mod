package com.warfront.entity;

import com.warfront.block.entity.LoadedShellType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
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
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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
 * Artillery projectile fired by the Friendly Mortar Support emplacement.
 * Supports HE, Incendiary, Airburst (Cluster), and Smoke payloads.
 */
public class FriendlyArtilleryShellEntity extends Projectile implements GeoEntity {

    private static final EntityDataAccessor<Byte> SHELL_TYPE =
            SynchedEntityData.defineId(FriendlyArtilleryShellEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> TARGET_GROUND_Y =
            SynchedEntityData.defineId(FriendlyArtilleryShellEntity.class, EntityDataSerializers.FLOAT);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int ticksInAir = 0;
    private boolean detonated = false;

    public FriendlyArtilleryShellEntity(EntityType<? extends FriendlyArtilleryShellEntity> entityType, Level level) {
        super(entityType, level);
        this.noCulling = true;
    }

    public FriendlyArtilleryShellEntity(Level level, double x, double y, double z, LoadedShellType type, float targetGroundY) {
        this(ModEntities.FRIENDLY_ARTILLERY_SHELL.get(), level);
        setPos(x, y, z);
        setShellType(type);
        setTargetGroundY(targetGroundY);
    }

    public LoadedShellType getShellType() {
        return LoadedShellType.fromId(this.entityData.get(SHELL_TYPE));
    }

    public void setShellType(LoadedShellType type) {
        this.entityData.set(SHELL_TYPE, (byte) type.getId());
    }

    public float getTargetGroundY() {
        return this.entityData.get(TARGET_GROUND_Y);
    }

    public void setTargetGroundY(float targetGroundY) {
        this.entityData.set(TARGET_GROUND_Y, targetGroundY);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 65536.0D; // 256 blocks
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SHELL_TYPE, (byte) LoadedShellType.HE.getId());
        builder.define(TARGET_GROUND_Y, 64.0F);
    }

    @Override
    public void tick() {
        super.tick();
        ticksInAir++;

        Vec3 movement = getDeltaMovement();

        // 1. Airburst Altitude Trigger Check: Detonates strictly 25 blocks above targeted ground
        if (!level().isClientSide && getShellType() == LoadedShellType.AIRBURST && !detonated) {
            float burstAltitude = getTargetGroundY() + 25.0F;
            if (movement.y < 0.0D && getY() <= burstAltitude) {
                detonateAirburst();
                return;
            }
        }

        // 2. Collision detection (Bypasses passable/replaceable foliage like grass, flowers, ferns)
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

        // Downward ballistic gravity (0.05 blocks/tick^2)
        setDeltaMovement(movement.x, movement.y - 0.05D, movement.z);

        // Trajectory orientation
        double horizDist = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        setYRot((float) (Mth.atan2(movement.x, movement.z) * (180.0D / Math.PI)));
        setXRot((float) (Mth.atan2(movement.y, horizDist) * (180.0D / Math.PI)));
        this.yRotO = getYRot();
        this.xRotO = getXRot();

        setPos(nextX, nextY, nextZ);

        if (level().isClientSide) {
            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY(), getZ(), 0.0D, 0.02D, 0.0D);
            if (getShellType() == LoadedShellType.INCENDIARY) {
                level().addParticle(ParticleTypes.FLAME, getX(), getY(), getZ(), 0.0D, 0.01D, 0.0D);
            } else if (getShellType() == LoadedShellType.SMOKE) {
                level().addParticle(ParticleTypes.POOF, getX(), getY(), getZ(), 0.0D, 0.01D, 0.0D);
            }
        } else {
            if (ticksInAir > 600 || getY() < level().getMinBuildHeight() - 20) {
                discard();
            }
        }
    }

    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);
        if (!level().isClientSide && !detonated) {
            detonated = true;
            detonateImpact();
            discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (!level().isClientSide && hitResult.getEntity() instanceof LivingEntity living) {
            float directDamage = switch (getShellType()) {
                case HE -> 25.0F;
                case INCENDIARY -> 15.0F;
                case AIRBURST -> 12.0F;
                case SMOKE -> 5.0F;
                case NONE -> 10.0F;
            };
            living.hurt(level().damageSources().thrown(this, getOwner()), directDamage);
        }
    }

    private void detonateImpact() {
        if (!(level() instanceof ServerLevel serverLevel)) return;

        LoadedShellType type = getShellType();
        switch (type) {
            case HE -> detonateHE(serverLevel);
            case INCENDIARY -> detonateIncendiary(serverLevel);
            case AIRBURST -> detonateAirburst();
            case SMOKE -> detonateSmoke(serverLevel);
            case NONE -> detonateHE(serverLevel);
        }
    }

    /**
     * HE: Heavy 2.8F block crater + devastating 11.0m blast radius with dynamic exposure falloff.
     */
    private void detonateHE(ServerLevel serverLevel) {
        ExplosionDamageCalculator blockOnlyCalc = new ExplosionDamageCalculator() {
            @Override
            public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
                return false;
            }
        };
        serverLevel.explode(this, null, blockOnlyCalc, getX(), getY(), getZ(), 2.8F, false, Level.ExplosionInteraction.BLOCK);
        serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY(), getZ(), 3, 0.4D, 0.4D, 0.4D, 0.0D);
        serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY(), getZ(), 20, 1.5D, 0.8D, 1.5D, 0.08D);
        serverLevel.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 7.0F, 0.70F);

        final double maxDamageRadius = 11.0D;
        AABB damageArea = this.getBoundingBox().inflate(maxDamageRadius);
        List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class, damageArea);
        Vec3 explosionCenter = this.position();

        for (LivingEntity target : targets) {
            double dist = target.position().distanceTo(explosionCenter);
            if (dist <= maxDamageRadius) {
                float exposure = Explosion.getSeenPercent(explosionCenter, target);
                if (exposure > 0.0F) {
                    double distRatio = Math.max(0.0D, dist - 2.0D) / (maxDamageRadius - 2.0D);
                    double falloff = 1.0D - distRatio * 0.71D; // 35.0 at core, 10.0 at 11.0m
                    float rawDamage = (float) (35.0D * falloff * exposure);

                    DamageSource src = serverLevel.damageSources().explosion(this, getOwner());
                    target.hurt(src, rawDamage);

                    Vec3 diff = target.position().subtract(explosionCenter);
                    double len = diff.length();
                    if (len > 0.001D) {
                        Vec3 knockback = diff.scale((1.0D - (dist / maxDamageRadius)) * 0.6D * exposure / len);
                        target.push(knockback.x, 0.25D, knockback.z);
                    }
                }
            }
        }
    }

    /**
     * Incendiary: Napalm fire field covering 7.5m radius (1.5x) with sparse surface ignition + 8s burn DoT.
     */
    private void detonateIncendiary(ServerLevel serverLevel) {
        serverLevel.sendParticles(ParticleTypes.FLAME, getX(), getY() + 0.5D, getZ(), 60, 1.8D, 0.5D, 1.8D, 0.1D);
        serverLevel.sendParticles(ParticleTypes.LAVA, getX(), getY() + 0.5D, getZ(), 20, 1.2D, 0.4D, 1.2D, 0.05D);
        serverLevel.playSound(null, blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 4.0F, 0.8F);
        serverLevel.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4.0F, 0.9F);

        // Place surface fire blocks in 1.5x radius (6 blocks), with reduced ignition density (25%)
        BlockPos center = blockPosition();
        int radius = 7;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z <= radius * radius) {
                    BlockPos surfacePos = serverLevel.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, center.offset(x, 0, z));
                    if (serverLevel.getBlockState(surfacePos).isAir() && serverLevel.getBlockState(surfacePos.below()).isSolidRender(serverLevel, surfacePos.below())) {
                        if (random.nextFloat() < 0.25F) {
                            serverLevel.setBlock(surfacePos, Blocks.FIRE.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }

        // Damage and ignite entities across 1.5x area (7.5m)
        AABB damageArea = this.getBoundingBox().inflate(7.5D);
        List<LivingEntity> targets = serverLevel.getEntitiesOfClass(LivingEntity.class, damageArea);
        for (LivingEntity target : targets) {
            target.igniteForSeconds(8.0F);
            target.hurt(serverLevel.damageSources().inFire(), 8.0F);
        }
    }

    /**
     * Airburst: Mid-air explosion at h=25 scattering 18 dedicated cluster bomblets across a 20m radius footprint.
     */
    private void detonateAirburst() {
        if (!(level() instanceof ServerLevel serverLevel)) return;
        detonated = true;

        serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY(), getZ(), 2, 0.3D, 0.3D, 0.3D, 0.0D);
        serverLevel.playSound(null, blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.BLOCKS, 6.0F, 0.9F);
        serverLevel.playSound(null, blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 5.0F, 0.7F);

        // Disperse 18 bomblets across a 20-meter radius footprint using Mitchell's Best-Candidate algorithm
        // (Blue noise semi-random scatter: each bomblet picks a location maximizing distance from previously chosen bomblets)
        // Freefall from h=25 under g=0.05 takes ~31.62 ticks (t_fall = sqrt(2 * 25.0 / 0.05)).
        // Randomized vertical velocity creates 0.2-0.3s (4-6 ticks) arrival time variation for an authentic rolling strike.
        final int bombletCount = 18;
        final int candidatesPerBomblet = 14;
        final double maxRadius = 10.0D;
        final double nominalFallTicks = 31.6227766D;

        double[] chosenX = new double[bombletCount];
        double[] chosenZ = new double[bombletCount];

        for (int i = 0; i < bombletCount; i++) {
            double bestX = 0.0D;
            double bestZ = 0.0D;
            double maxMinDistSq = -1.0D;

            int candidateCount = (i == 0) ? 1 : candidatesPerBomblet;
            for (int c = 0; c < candidateCount; c++) {
                double theta = random.nextDouble() * Math.PI * 2.0D;
                double r = maxRadius * Math.sqrt(0.04D + 0.96D * random.nextDouble());
                double candX = r * Math.cos(theta);
                double candZ = r * Math.sin(theta);

                if (i == 0) {
                    bestX = candX;
                    bestZ = candZ;
                    break;
                }

                double minDistSq = Double.MAX_VALUE;
                for (int j = 0; j < i; j++) {
                    double dx = candX - chosenX[j];
                    double dz = candZ - chosenZ[j];
                    double distSq = dx * dx + dz * dz;
                    if (distSq < minDistSq) {
                        minDistSq = distSq;
                    }
                }

                if (minDistSq > maxMinDistSq) {
                    maxMinDistSq = minDistSq;
                    bestX = candX;
                    bestZ = candZ;
                }
            }

            chosenX[i] = bestX;
            chosenZ[i] = bestZ;

            double vx = bestX / nominalFallTicks;
            double vz = bestZ / nominalFallTicks;
            double vy = (random.nextDouble() * 0.30D - 0.15D); // +/- 0.15 b/t produces 4-6 ticks (0.2-0.3s) arrival variation

            FriendlyClusterBombletEntity bomblet = new FriendlyClusterBombletEntity(serverLevel, getX(), getY(), getZ());
            bomblet.setOwner(getOwner());
            bomblet.setDeltaMovement(vx, vy, vz);
            serverLevel.addFreshEntity(bomblet);
        }

        discard();
    }

    /**
     * Smoke: Deploys 20s volumetric smoke screen and breaks hostile mob aggro.
     */
    private void detonateSmoke(ServerLevel serverLevel) {
        serverLevel.playSound(null, blockPosition(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.BLOCKS, 4.0F, 0.8F);
        serverLevel.playSound(null, blockPosition(), SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 3.5F, 0.7F);

        SmokeScreenEntity smoke = new SmokeScreenEntity(serverLevel, getX(), getY(), getZ());
        serverLevel.addFreshEntity(smoke);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        return super.canHitEntity(target) && !(target instanceof FriendlyArtilleryShellEntity) && !(target instanceof SmokeScreenEntity);
    }

    private boolean isPassableBlock(BlockState state, BlockPos pos) {
        return state.getCollisionShape(level(), pos).isEmpty()
                || !state.blocksMotion()
                || state.canBeReplaced()
                || state.is(BlockTags.REPLACEABLE);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.ticksInAir = tag.getInt("TicksInAir");
        this.detonated = tag.getBoolean("Detonated");
        setShellType(LoadedShellType.fromId(tag.getByte("ShellType")));
        setTargetGroundY(tag.getFloat("TargetGroundY"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("TicksInAir", this.ticksInAir);
        tag.putBoolean("Detonated", this.detonated);
        tag.putByte("ShellType", (byte) getShellType().getId());
        tag.putFloat("TargetGroundY", getTargetGroundY());
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
