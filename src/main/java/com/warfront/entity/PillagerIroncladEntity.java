package com.warfront.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.EnumSet;
import java.util.List;

/**
 * Pillager Ironclad — 3-block tall towering iron shield meatwall bodyguard.
 * Has no weapons; completely blocks all incoming frontal projectiles and attacks with its massive iron slab shield.
 * Actively positions itself between approaching players and allied gunners/commanders.
 * Attacks solely with concussive, high-knockback shield bashes, turning aggressive only when isolated.
 */
public class PillagerIroncladEntity extends AbstractIllager implements GeoEntity {

    private static final EntityDataAccessor<Boolean> IS_ENRAGED =
            SynchedEntityData.defineId(PillagerIroncladEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Boolean> SHIELD_BASHING =
            SynchedEntityData.defineId(PillagerIroncladEntity.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walking");
    private static final RawAnimation BASH = RawAnimation.begin().thenPlay("shield_bash");
    private static final RawAnimation ROAR = RawAnimation.begin().thenPlay("enrage_roar");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private boolean lastBashing = false;

    public PillagerIroncladEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 16;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 80.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.16D)
                .add(Attributes.ATTACK_DAMAGE, 6.0D)
                .add(Attributes.ARMOR, 10.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.90D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(IS_ENRAGED, false);
        builder.define(SHIELD_BASHING, false);
    }

    public boolean isEnraged() {
        return this.entityData.get(IS_ENRAGED);
    }

    public void setEnraged(boolean enraged) {
        this.entityData.set(IS_ENRAGED, enraged);
    }

    public boolean isShieldBashing() {
        return this.entityData.get(SHIELD_BASHING);
    }

    public void setShieldBashing(boolean bashing) {
        this.entityData.set(SHIELD_BASHING, bashing);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new IroncladBodyguardGoal(this));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 10.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Raider.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Zombie.class, true));
    }

    /**
     * Absolute frontal shield wall:
     * Absorbs 100% of damage and projectiles within a 150-degree frontal cone.
     * Hits from behind deal normal damage, rewarding flanking and positioning.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        Entity attacker = source.getDirectEntity() != null ? source.getDirectEntity() : source.getEntity();
        if (attacker != null) {
            Vec3 look = getLookAngle();
            Vec3 toAttacker = new Vec3(attacker.getX() - getX(), 0.0D, attacker.getZ() - getZ()).normalize();
            double dot = look.x * toAttacker.x + look.z * toAttacker.z;

            // Frontal hemisphere (dot > 0.15 = ~160 degree block arc)
            if (dot > 0.15D) {
                // Completely blocked by massive iron slab shield!
                playSound(SoundEvents.SHIELD_BLOCK, 1.2F, 0.8F);
                playSound(SoundEvents.ANVIL_LAND, 0.6F, 1.6F);

                if (level() instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.CRIT, getX() + look.x * 0.8D, getY() + 1.5D, getZ() + look.z * 0.8D,
                            5, 0.2D, 0.3D, 0.2D, 0.05D);
                }
                return false;
            }
        }
        return super.hurt(source, amount);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        controllers.add(new AnimationController<>(this, "attack", 2, this::attackPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerIroncladEntity> state) {
        boolean isMoving = state.isMoving()
                || this.walkAnimation.isMoving()
                || this.walkAnimation.speed() > 0.001F
                || state.getLimbSwingAmount() > 0.001F
                || (this.getDeltaMovement().horizontalDistanceSqr() > 0.00005D)
                || (this.getX() != this.xo || this.getZ() != this.zo);

        if (isMoving) {
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState attackPredicate(AnimationState<PillagerIroncladEntity> state) {
        boolean bashing = isShieldBashing();
        if (bashing != this.lastBashing) {
            state.getController().forceAnimationReset();
            this.lastBashing = bashing;
        }

        if (bashing) {
            return state.setAndContinue(BASH);
        }
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    @Override
    public boolean isAlliedTo(Entity other) {
        if (AlliedFactionHelper.isAllied(this, other)) return true;
        return super.isAlliedTo(other);
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (AlliedFactionHelper.isAllied(this, target)) return false;
        return super.canAttack(target);
    }

    @Override
    public void setTarget(LivingEntity target) {
        if (target != null && AlliedFactionHelper.isAllied(this, target)) {
            return;
        }
        super.setTarget(target);
    }

    @Override
    public void applyRaidBuffs(ServerLevel level, int wave, boolean unused) {
    }

    @Override
    public SoundEvent getCelebrateSound() {
        return SoundEvents.PILLAGER_CELEBRATE;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.RAVAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return SoundEvents.RAVAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }

    @Override
    public AbstractIllager.IllagerArmPose getArmPose() {
        return AbstractIllager.IllagerArmPose.CROSSBOW_HOLD; // Raised shield stance
    }

    /**
     * AI Goal coordinating bodyguard line-of-fire interception, slow turn rate, concussive shield bashes,
     * and last-man-standing aggressive switch.
     */
    static class IroncladBodyguardGoal extends Goal {
        private final PillagerIroncladEntity ironclad;
        private int bashCooldown = 0;
        private int bashAnimTicks = 0;

        public IroncladBodyguardGoal(PillagerIroncladEntity ironclad) {
            this.ironclad = ironclad;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.ironclad.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public void start() {
            super.start();
            this.bashCooldown = 0;
            this.bashAnimTicks = 0;
        }

        @Override
        public void stop() {
            super.stop();
            this.ironclad.setShieldBashing(false);
            this.bashAnimTicks = 0;
        }

        @Override
        public void tick() {
            LivingEntity target = this.ironclad.getTarget();
            if (target == null || !target.isAlive()) {
                stop();
                return;
            }

            if (bashCooldown > 0) bashCooldown--;
            if (bashAnimTicks > 0) {
                bashAnimTicks--;
                if (bashAnimTicks == 0) {
                    this.ironclad.setShieldBashing(false);
                }
            }

            // Restrict rotational turn speed to 3.5 degrees/tick max (prime balancing factor)
            double dX = target.getX() - this.ironclad.getX();
            double dZ = target.getZ() - this.ironclad.getZ();
            float targetYaw = (float) (Mth.atan2(dZ, dX) * (180.0D / Math.PI)) - 90.0F;
            float currentYaw = this.ironclad.getYRot();
            float diff = Mth.wrapDegrees(targetYaw - currentYaw);
            float step = Math.clamp(diff, -3.5F, 3.5F);
            this.ironclad.setYRot(currentYaw + step);
            this.ironclad.yBodyRot = this.ironclad.getYRot();
            this.ironclad.yHeadRot = this.ironclad.getYRot();

            // Find nearby allied Pillagers in 24-block radius to protect
            AABB allySearchArea = this.ironclad.getBoundingBox().inflate(24.0D);
            List<LivingEntity> allies = this.ironclad.level().getEntitiesOfClass(LivingEntity.class, allySearchArea,
                    e -> e != this.ironclad && e.isAlive() && !(e instanceof PillagerIroncladEntity) && AlliedFactionHelper.isPillager(e));

            double distToPlayer = this.ironclad.distanceTo(target);

            // 1. Close melee defense: execute concussive shield bash
            if (distToPlayer <= 2.6D && bashCooldown <= 0) {
                executeShieldBash(target);
                return;
            }

            // 2. Interception vs Aggression
            if (!allies.isEmpty()) {
                // Protecting allies: position directly between player and nearest ally
                this.ironclad.setEnraged(false);
                LivingEntity priorityAlly = allies.get(0);
                double minAllyDist = Double.MAX_VALUE;
                for (LivingEntity a : allies) {
                    double d = a.distanceToSqr(target);
                    if (d < minAllyDist) {
                        minAllyDist = d;
                        priorityAlly = a;
                    }
                }

                Vec3 allyPos = priorityAlly.position();
                Vec3 playerPos = target.position();
                Vec3 toPlayer = playerPos.subtract(allyPos).normalize();
                double allyToPlayerDist = allyPos.distanceTo(playerPos);

                // Stand 4-5 blocks ahead of the ally along the line-of-fire
                double offsetDist = Math.min(5.0D, allyToPlayerDist * 0.5D);
                Vec3 interceptPoint = allyPos.add(toPlayer.scale(offsetDist));

                if (this.ironclad.distanceToSqr(interceptPoint) > 1.5D * 1.5D) {
                    this.ironclad.getNavigation().moveTo(interceptPoint.x, interceptPoint.y, interceptPoint.z, 1.0D);
                } else {
                    this.ironclad.getNavigation().stop();
                }
            } else {
                // Last Man Standing: No allies left, switch to aggressive advance!
                this.ironclad.setEnraged(true);
                this.ironclad.getNavigation().moveTo(target, 1.35D);
            }
        }

        private void executeShieldBash(LivingEntity target) {
            this.bashCooldown = 40; // 2 seconds between bashes
            this.bashAnimTicks = 12;
            this.ironclad.setShieldBashing(true);

            this.ironclad.playSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.4F, 0.7F);
            this.ironclad.playSound(SoundEvents.ANVIL_LAND, 0.8F, 1.2F);

            // 6.0 damage + violent 3.5m knockback + 3s concussion (Slowness II + Nausea)
            target.hurt(this.ironclad.level().damageSources().mobAttack(this.ironclad), 6.0F);

            Vec3 knock = target.position().subtract(this.ironclad.position()).normalize().scale(1.4D);
            target.push(knock.x, 0.45D, knock.z);
            target.hurtMarked = true;

            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1), this.ironclad);
            target.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 60, 0), this.ironclad);
        }
    }
}
