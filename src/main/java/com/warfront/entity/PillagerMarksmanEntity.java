package com.warfront.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
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
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.EnumSet;

/**
 * Pillager Marksman — specialized crossbow sniper who attacks with arrows from long range.
 */
public class PillagerMarksmanEntity extends AbstractIllager implements GeoEntity, RangedAttackMob {

    public static final byte STATE_IDLE = 0;
    public static final byte STATE_AIMING = 1;
    public static final byte STATE_SHOOTING = 2;
    public static final byte STATE_RELOADING = 3;

    private static final EntityDataAccessor<Byte> COMBAT_STATE =
            SynchedEntityData.defineId(PillagerMarksmanEntity.class, EntityDataSerializers.BYTE);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.pillager_marksman.idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.pillager_marksman.walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("animation.pillager_marksman.run");
    private static final RawAnimation AIMING = RawAnimation.begin().thenLoop("animation.pillager_marksman.aiming");
    private static final RawAnimation SHOOTING = RawAnimation.begin().thenPlay("animation.pillager_marksman.shooting");
    private static final RawAnimation RELOADING = RawAnimation.begin().thenPlay("animation.pillager_marksman.reloading");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public PillagerMarksmanEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 8;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 26.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.26D)
                .add(Attributes.ATTACK_DAMAGE, 4.0D)
                .add(Attributes.ARMOR, 4.0D)
                .add(Attributes.FOLLOW_RANGE, 40.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(COMBAT_STATE, STATE_IDLE);
    }

    public byte getCombatState() {
        return this.entityData.get(COMBAT_STATE);
    }

    public void setCombatState(byte state) {
        this.entityData.set(COMBAT_STATE, state);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new Raider.HoldGroundAttackGoal(this, 10.0F));
        this.goalSelector.addGoal(3, new MarksmanAttackGoal(this, 1.0D, 18.0F));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Raider.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        ItemStack arrowItem = new ItemStack(Items.ARROW);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, arrowItem, distanceFactor, null);
        double dX = target.getX() - this.getX();
        double dY = target.getY(0.3333333333333333D) - arrow.getY();
        double dZ = target.getZ() - this.getZ();
        double dDist = Math.sqrt(dX * dX + dZ * dZ);
        arrow.shoot(dX, dY + dDist * 0.15D, dZ, 1.8F, (float) (12 - this.level().getDifficulty().getId() * 3));
        this.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(arrow);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide) {
            LivingEntity target = this.getTarget();
            boolean hasTarget = target != null && target.isAlive();
            this.setAggressive(hasTarget);
            this.setSprinting(hasTarget && (getCombatState() == STATE_IDLE) && (this.getDeltaMovement().horizontalDistanceSqr() > 0.005D));
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        controllers.add(new AnimationController<>(this, "attack", 2, this::attackPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerMarksmanEntity> state) {
        if (state.isMoving()) {
            if (this.isSprinting() || (this.isAggressive() && getCombatState() == STATE_IDLE) || (this.getTarget() != null && getCombatState() == STATE_IDLE)) {
                return state.setAndContinue(RUN);
            }
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState attackPredicate(AnimationState<PillagerMarksmanEntity> state) {
        byte s = getCombatState();
        return switch (s) {
            case STATE_AIMING -> state.setAndContinue(AIMING);
            case STATE_SHOOTING -> state.setAndContinue(SHOOTING);
            case STATE_RELOADING -> state.setAndContinue(RELOADING);
            default -> PlayState.CONTINUE;
        };
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
        if (getCombatState() == STATE_AIMING || getCombatState() == STATE_SHOOTING) {
            return AbstractIllager.IllagerArmPose.CROSSBOW_HOLD;
        }
        if (getCombatState() == STATE_RELOADING) {
            return AbstractIllager.IllagerArmPose.CROSSBOW_CHARGE;
        }
        return AbstractIllager.IllagerArmPose.NEUTRAL;
    }

    /**
     * AI Goal coordinating sniper spacing, aiming, arrow release, and reloading.
     */
    static class MarksmanAttackGoal extends Goal {
        private final PillagerMarksmanEntity mob;
        private final double speedModifier;
        private final float attackRadius;
        private int attackPhaseTicks = 0;
        private int currentPhase = STATE_IDLE;
        private int seeTime = 0;

        public MarksmanAttackGoal(PillagerMarksmanEntity mob, double speedModifier, float attackRadius) {
            this.mob = mob;
            this.speedModifier = speedModifier;
            this.attackRadius = attackRadius;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.mob.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public void start() {
            super.start();
            this.currentPhase = STATE_AIMING;
            this.attackPhaseTicks = 0;
            this.mob.setCombatState(STATE_AIMING);
        }

        @Override
        public void stop() {
            super.stop();
            this.seeTime = 0;
            this.currentPhase = STATE_IDLE;
            this.mob.setCombatState(STATE_IDLE);
            this.mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            LivingEntity target = this.mob.getTarget();
            if (target == null) return;

            double distSq = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
            boolean canSee = this.mob.getSensing().hasLineOfSight(target);

            if (canSee) {
                this.seeTime++;
            } else {
                this.seeTime = 0;
            }

            this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

            // Tactical positioning: back away if target is too close (< 6 blocks)
            if (distSq < 36.0D) {
                double backX = this.mob.getX() - (target.getX() - this.mob.getX());
                double backZ = this.mob.getZ() - (target.getZ() - this.mob.getZ());
                this.mob.getNavigation().moveTo(backX, this.mob.getY(), backZ, this.speedModifier * 1.15D);
            } else if (distSq > (double) (this.attackRadius * this.attackRadius) || !canSee) {
                this.mob.getNavigation().moveTo(target, this.speedModifier);
            } else {
                this.mob.getNavigation().stop();
            }

            this.attackPhaseTicks++;

            switch (this.currentPhase) {
                case STATE_AIMING -> {
                    this.mob.setCombatState(STATE_AIMING);
                    if (this.attackPhaseTicks >= 20 && canSee) {
                        this.currentPhase = STATE_SHOOTING;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_SHOOTING);
                        float distFactor = (float) Math.sqrt(distSq) / this.attackRadius;
                        this.mob.performRangedAttack(target, Math.clamp(distFactor, 0.1F, 1.0F));
                    }
                }
                case STATE_SHOOTING -> {
                    this.mob.setCombatState(STATE_SHOOTING);
                    if (this.attackPhaseTicks >= 6) {
                        this.currentPhase = STATE_RELOADING;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_RELOADING);
                        this.mob.playSound(SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), 1.0F, 1.0F);
                    }
                }
                case STATE_RELOADING -> {
                    this.mob.setCombatState(STATE_RELOADING);
                    if (this.attackPhaseTicks >= 18) {
                        this.currentPhase = STATE_AIMING;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_AIMING);
                    }
                }
            }
        }
    }
}
