package com.warfront.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Pillager Warrior — front-line melee fighter equipped with a heavy war axe.
 */
public class PillagerWarriorEntity extends AbstractIllager implements GeoEntity {

    public static final byte ACTION_NONE = 0;
    public static final byte ACTION_ATTACK = 1;

    private static final EntityDataAccessor<Byte> ACTION_STATE =
            SynchedEntityData.defineId(PillagerWarriorEntity.class, EntityDataSerializers.BYTE);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.pillager_warrior.idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.pillager_warrior.walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("animation.pillager_warrior.run");
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("animation.pillager_warrior.attack");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int actionTicks = 0;
    private LivingEntity pendingAttackTarget = null;
    private int attackImpactTicks = -1;

    public PillagerWarriorEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 8;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 32.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.27D)
                .add(Attributes.ATTACK_DAMAGE, 8.0D)
                .add(Attributes.ARMOR, 6.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.20D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ACTION_STATE, ACTION_NONE);
    }

    public byte getActionState() {
        return this.entityData.get(ACTION_STATE);
    }

    public void setActionState(byte state, int durationTicks) {
        this.entityData.set(ACTION_STATE, state);
        this.actionTicks = durationTicks;
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new Raider.HoldGroundAttackGoal(this, 10.0F));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.25D, false));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Raider.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide) {
            LivingEntity target = this.getTarget();
            boolean hasTarget = target != null && target.isAlive();
            this.setAggressive(hasTarget);
            this.setSprinting(hasTarget && (this.getDeltaMovement().horizontalDistanceSqr() > 0.005D));

            if (this.actionTicks > 0) {
                this.actionTicks--;
                if (this.actionTicks <= 0) {
                    this.entityData.set(ACTION_STATE, ACTION_NONE);
                }
            }

            // Damage impact frame calculation (contact frame at 9 ticks)
            if (this.attackImpactTicks > 0) {
                this.attackImpactTicks--;
                if (this.pendingAttackTarget != null && this.pendingAttackTarget.isAlive()) {
                    this.getLookControl().setLookAt(this.pendingAttackTarget, 30.0F, 30.0F);
                }
                if (this.attackImpactTicks == 0) {
                    this.attackImpactTicks = -1;
                    if (this.pendingAttackTarget != null && this.pendingAttackTarget.isAlive()) {
                        double reach = this.getBbWidth() * 2.0F + this.pendingAttackTarget.getBbWidth() + 1.2D;
                        if (this.distanceToSqr(this.pendingAttackTarget) <= reach * reach) {
                            super.doHurtTarget(this.pendingAttackTarget);
                            this.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.2F, 0.85F);
                        }
                    }
                    this.pendingAttackTarget = null;
                }
            }
        }
    }

    @Override
    public void die(DamageSource damageSource) {
        super.die(damageSource);
        this.attackImpactTicks = -1;
        this.pendingAttackTarget = null;
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        if (!(target instanceof LivingEntity livingTarget)) return false;
        if (this.attackImpactTicks > 0) return false;

        if (!this.level().isClientSide) {
            this.pendingAttackTarget = livingTarget;
            this.attackImpactTicks = 9; // Contact frame at 9 ticks (~0.45s)
            setActionState(ACTION_ATTACK, 20);
            this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.8F);
        }
        return true;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        controllers.add(new AnimationController<>(this, "attack", 2, this::attackPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerWarriorEntity> state) {
        if (state.isMoving()) {
            if (this.isSprinting() || this.isAggressive() || this.getTarget() != null) {
                return state.setAndContinue(RUN);
            }
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState attackPredicate(AnimationState<PillagerWarriorEntity> state) {
        if (getActionState() == ACTION_ATTACK) {
            return state.setAndContinue(ATTACK);
        }
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
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
        return SoundEvents.PILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return SoundEvents.PILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PILLAGER_DEATH;
    }

    @Override
    public AbstractIllager.IllagerArmPose getArmPose() {
        if (this.isAggressive()) {
            return AbstractIllager.IllagerArmPose.ATTACKING;
        }
        return AbstractIllager.IllagerArmPose.NEUTRAL;
    }
}
