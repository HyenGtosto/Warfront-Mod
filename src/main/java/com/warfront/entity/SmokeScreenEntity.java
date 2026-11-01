package com.warfront.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Volumetric smoke screen entity deployed by the Friendly Mortar's Smoke Shell.
 *
 * Modeled after tactical Counter-Strike smoke grenades:
 * - High-pressure explosive pop upon deployment.
 * - Multi-layered, dense, zero-drift spherical & ground carpet smoke cloud (400 ticks / 20 seconds).
 * - Full visual obstruction and immersion blindness for entities inside the cloud.
 * - Global event-driven aggro suppression preventing hostiles from targeting through or inside the smoke.
 */
public class SmokeScreenEntity extends Entity {

    public static final int DURATION_TICKS = 120; // 6 seconds
    public static final double RADIUS = 7.5D;

    private static final Set<SmokeScreenEntity> ACTIVE_SMOKE_SCREENS = ConcurrentHashMap.newKeySet();

    private int age = 0;

    public SmokeScreenEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
    }

    public SmokeScreenEntity(Level level, double x, double y, double z) {
        this(ModEntities.SMOKE_SCREEN.get(), level);
        setPos(x, y, z);
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        ACTIVE_SMOKE_SCREENS.add(this);
    }

    @Override
    public void onClientRemoval() {
        super.onClientRemoval();
        ACTIVE_SMOKE_SCREENS.remove(this);
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        ACTIVE_SMOKE_SCREENS.remove(this);
    }

    public static boolean isObstructedBySmoke(Vec3 from, Vec3 to) {
        for (SmokeScreenEntity smoke : ACTIVE_SMOKE_SCREENS) {
            if (!smoke.isAlive()) continue;
            Vec3 center = smoke.position().add(0, 1.5D, 0);
            if (lineIntersectsSphere(from, to, center, RADIUS)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isInsideSmoke(Vec3 pos) {
        for (SmokeScreenEntity smoke : ACTIVE_SMOKE_SCREENS) {
            if (!smoke.isAlive()) continue;
            Vec3 center = smoke.position().add(0, 1.5D, 0);
            if (pos.distanceToSqr(center) <= RADIUS * RADIUS) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void tick() {
        super.tick();
        this.age++;

        // Initial Pop Acoustic Feedback
        if (this.age == 1) {
            level().playSound(null, blockPosition(), SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 5.0F, 0.70F);
            level().playSound(null, blockPosition(), SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.BLOCKS, 4.0F, 0.85F);
        }

        if (level().isClientSide) {
            renderCounterStrikeSmoke();
        } else if (level() instanceof ServerLevel serverLevel) {
            // Apply blindness to players inside the smoke sphere (tactical CS smoke immersion)
            AABB smokeBox = getBoundingBox().inflate(RADIUS);
            List<Player> insidePlayers = serverLevel.getEntitiesOfClass(Player.class, smokeBox);
            Vec3 center = position().add(0, 1.5D, 0);
            for (Player player : insidePlayers) {
                if (player.position().distanceToSqr(center) <= (RADIUS - 0.5D) * (RADIUS - 0.5D)) {
                    player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false, false));
                }
            }

            // Periodically clear active targets of mobs whose sightlines are obstructed
            if (this.age % 4 == 0) {
                applyAggroReset(serverLevel);
            }

            if (this.age >= DURATION_TICKS) {
                discard();
            }
        }
    }

    /**
     * Renders a dense, multi-layered Counter-Strike style volumetric smoke grenade:
     * - Initial explosive pop bloom during ticks 0-15.
     * - Continuous zero-drift core and outer spherical shells.
     * - Ground carpet disc sealing visibility beneath the cloud.
     */
    private void renderCounterStrikeSmoke() {
        double px = getX();
        double py = getY();
        double pz = getZ();

        // 1. Initial Pop Burst (Bloom)
        if (this.age < 15) {
            for (int i = 0; i < 25; i++) {
                double u = random.nextDouble();
                double theta = random.nextDouble() * 2.0D * Math.PI;
                double phi = random.nextDouble() * Math.PI;
                double r = RADIUS * Math.cbrt(u);

                double dx = r * Math.sin(phi) * Math.cos(theta);
                double dy = Math.max(0.1D, r * Math.cos(phi) * 0.7D + 1.2D);
                double dz = r * Math.sin(phi) * Math.sin(theta);

                level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, px + dx, py + dy, pz + dz, 0.0D, 0.0D, 0.0D);
                if (i % 2 == 0) {
                    level().addParticle(ParticleTypes.POOF, px + dx, py + dy, pz + dz, 0.0D, 0.0D, 0.0D);
                }
            }
        }

        // 2. Sustained Dense Volumetric Sphere (35-40 particles/tick with zero drift)
        for (int i = 0; i < 30; i++) {
            double u = random.nextDouble();
            double theta = random.nextDouble() * 2.0D * Math.PI;
            double phi = (random.nextDouble() * 0.85D + 0.15D) * Math.PI; // bias towards upper dome
            double r = RADIUS * Math.cbrt(u);

            double dx = r * Math.sin(phi) * Math.cos(theta);
            double dy = Math.max(0.15D, r * Math.cos(phi) * 0.65D + 1.4D);
            double dz = r * Math.sin(phi) * Math.sin(theta);

            // Outer shell uses lingering campfire smoke; dense core uses poofs and dark smoke
            if (r > RADIUS * 0.5D) {
                level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, px + dx, py + dy, pz + dz, 0.0D, 0.0D, 0.0D);
            } else {
                level().addParticle(ParticleTypes.POOF, px + dx, py + dy, pz + dz, 0.0D, 0.0D, 0.0D);
                if (random.nextBoolean()) {
                    level().addParticle(ParticleTypes.SMOKE, px + dx, py + dy, pz + dz, 0.0D, 0.0D, 0.0D);
                }
            }
        }

        // 3. Ground Carpet (Seals the base so hostiles cannot peek beneath smoke)
        for (int i = 0; i < 10; i++) {
            double r = Math.sqrt(random.nextDouble()) * RADIUS;
            double theta = random.nextDouble() * 2.0D * Math.PI;
            double gx = r * Math.cos(theta);
            double gz = r * Math.sin(theta);
            double gy = 0.1D + random.nextDouble() * 0.6D;

            level().addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, px + gx, py + gy, pz + gz, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * Force-resets aggro for hostile mobs inside the smoke or whose line of sight
     * to targeted entities is obstructed by the smoke screen.
     */
    private void applyAggroReset(ServerLevel serverLevel) {
        Vec3 smokeCenter = position().add(0, 1.5D, 0);
        AABB scanArea = getBoundingBox().inflate(24.0D);
        List<Mob> nearbyMobs = serverLevel.getEntitiesOfClass(Mob.class, scanArea);

        for (Mob mob : nearbyMobs) {
            if (!mob.isAlive()) continue;

            Entity target = mob.getTarget();
            if (target == null) continue;

            Vec3 mobPos = mob.getEyePosition();
            Vec3 targetPos = target.getEyePosition();

            // 1. Either mob or target inside smoke
            if (mob.position().distanceToSqr(smokeCenter) <= RADIUS * RADIUS || target.position().distanceToSqr(smokeCenter) <= RADIUS * RADIUS) {
                resetMobAggro(mob);
                continue;
            }

            // 2. Line of sight between mob and target blocked by smoke
            if (lineIntersectsSphere(mobPos, targetPos, smokeCenter, RADIUS)) {
                resetMobAggro(mob);
            }
        }
    }

    private void resetMobAggro(Mob mob) {
        mob.setTarget(null);
        mob.getNavigation().stop();
        mob.setLastHurtByMob(null);
    }

    private static boolean lineIntersectsSphere(Vec3 lineStart, Vec3 lineEnd, Vec3 center, double radius) {
        Vec3 d = lineEnd.subtract(lineStart);
        Vec3 f = lineStart.subtract(center);

        double a = d.dot(d);
        if (a < 1.0E-6) return false;

        double b = 2.0D * f.dot(d);
        double c = f.dot(f) - radius * radius;

        double discriminant = b * b - 4.0D * a * c;
        if (discriminant >= 0.0D) {
            discriminant = Math.sqrt(discriminant);
            double t1 = (-b - discriminant) / (2.0D * a);
            double t2 = (-b + discriminant) / (2.0D * a);

            // Intersection point must lie within the line segment between mob and player
            return (t1 >= 0.0D && t1 <= 1.0D) || (t2 >= 0.0D && t2 <= 1.0D);
        }
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.age = tag.getInt("Age");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Age", this.age);
    }
}
