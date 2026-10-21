package com.warfront.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Pillager Scout — fast, evasive skirmisher dual-wielding daggers.
 */
public class PillagerScoutEntity extends AbstractIllager implements GeoEntity {

    private static final EntityDataAccessor<Boolean> DAGGERS_DRAWN =
            SynchedEntityData.defineId(PillagerScoutEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Byte> ACTION_STATE =
            SynchedEntityData.defineId(PillagerScoutEntity.class, EntityDataSerializers.BYTE);

    public static final byte ACTION_NONE = 0;
    public static final byte ACTION_UNSHEATHE = 1;
    public static final byte ACTION_SHEATHE = 2;
    public static final byte ACTION_ATTACK1 = 3;
    public static final byte ACTION_ATTACK2 = 4;
    public static final byte ACTION_LOOK = 5;
    public static final byte ACTION_ALERT = 6;

    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation UNSHEATHE = RawAnimation.begin().thenPlay("unsheathe");
    private static final RawAnimation SHEATHE = RawAnimation.begin().thenPlay("sheathe");
    private static final RawAnimation ATTACK1 = RawAnimation.begin().thenPlay("attack1");
    private static final RawAnimation ATTACK2 = RawAnimation.begin().thenPlay("attack2");
    private static final RawAnimation LOOK = RawAnimation.begin().thenPlay("look");
    private static final RawAnimation ALERT = RawAnimation.begin().thenLoop("alert");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private boolean alternateAttack = false;
    private int actionTicks = 0;
    private LivingEntity pendingAttackTarget = null;
    private int attackImpactTicks = -1;
    private int leapCooldownTicks = 0;
    private byte lastActionState = ACTION_NONE;

    public PillagerScoutEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 6;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 22.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
                .add(Attributes.ATTACK_DAMAGE, 5.0D)
                .add(Attributes.ARMOR, 3.0D)
                .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DAGGERS_DRAWN, false);
        builder.define(ACTION_STATE, ACTION_NONE);
    }

    public boolean areDaggersDrawn() {
        return this.entityData.get(DAGGERS_DRAWN);
    }

    public void setDaggersDrawn(boolean drawn) {
        this.entityData.set(DAGGERS_DRAWN, drawn);
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
        this.goalSelector.addGoal(1, new com.warfront.ai.goal.ScoutReconGoal(this));
        this.goalSelector.addGoal(2, new Raider.HoldGroundAttackGoal(this, 10.0F));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.20D, false));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Raider.class).setAlertOthers());
        // Condition player targeting so random passive sightings don't bypass the spyglass look mechanic
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                living -> this.areDaggersDrawn() || this.getTarget() != null));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Zombie.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Creeper.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Spider.class, true));
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide) {
            byte currentAction = getActionState();
            if (currentAction == ACTION_LOOK) {
                // Option 2A: Interrupted by player attack before scan finishes!
                // Cancel look state without blowing horn, immediately draw daggers to defend
                setActionState(ACTION_NONE, 0);
                setDaggersDrawn(true);
                if (source.getEntity() instanceof LivingEntity attacker) {
                    this.setTarget(attacker);
                }
            }
        }
        return super.hurt(source, amount);
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

            if (this.leapCooldownTicks > 0) {
                this.leapCooldownTicks--;
            }

            // Evasive retreat leap when wounded below 50% HP (stalls the player)
            if (hasTarget && this.getHealth() <= 11.0F && this.leapCooldownTicks <= 0) {
                double distSq = this.distanceToSqr(target);
                if (distSq < 12.25D) { // Within 3.5 blocks
                    Vec3 jumpVec = this.position().subtract(target.position()).normalize().scale(0.75D).add(0.0D, 0.35D, 0.0D);
                    this.setDeltaMovement(jumpVec);
                    this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.8F);
                    this.leapCooldownTicks = 140; // 7 seconds cooldown
                }
            }

            // Damage impact frame calculation (contact frame at 5 ticks)
            if (this.attackImpactTicks > 0) {
                this.attackImpactTicks--;
                if (this.pendingAttackTarget != null && this.pendingAttackTarget.isAlive()) {
                    this.getLookControl().setLookAt(this.pendingAttackTarget, 30.0F, 30.0F);
                }
                if (this.attackImpactTicks == 0) {
                    this.attackImpactTicks = -1;
                    if (this.pendingAttackTarget != null && this.pendingAttackTarget.isAlive()) {
                        double reach = this.getBbWidth() * 2.0F + this.pendingAttackTarget.getBbWidth() + 1.0D;
                        if (this.distanceToSqr(this.pendingAttackTarget) <= reach * reach) {
                            super.doHurtTarget(this.pendingAttackTarget);
                            this.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 1.2F);
                        }
                    }
                    this.pendingAttackTarget = null;
                }
            }

            // Stance management: unsheathe when target acquired, sheathe when peaceful
            if (hasTarget && !areDaggersDrawn()) {
                setDaggersDrawn(true);
                setActionState(ACTION_UNSHEATHE, 18);
            } else if (!hasTarget && areDaggersDrawn() && this.tickCount % 40 == 0 && this.actionTicks <= 0) {
                setDaggersDrawn(false);
                setActionState(ACTION_SHEATHE, 18);
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
            this.attackImpactTicks = 5; // Contact frame at 5 ticks (~0.25s)
            byte attackType = alternateAttack ? ACTION_ATTACK2 : ACTION_ATTACK1;
            alternateAttack = !alternateAttack;
            setActionState(attackType, 10);
            this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.3F);
        }
        return true;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        controllers.add(new AnimationController<>(this, "action", 2, this::actionPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerScoutEntity> state) {
        if (getActionState() == ACTION_LOOK || getActionState() == ACTION_ALERT) {
            return PlayState.STOP;
        }
        boolean isMoving = state.isMoving()
                || this.walkAnimation.isMoving()
                || this.walkAnimation.speed() > 0.001F
                || state.getLimbSwingAmount() > 0.001F
                || (this.getDeltaMovement().horizontalDistanceSqr() > 0.00005D)
                || (this.getX() != this.xo || this.getZ() != this.zo);
        if (isMoving) {
            if (this.isSprinting() || this.isAggressive() || (this.getTarget() != null && this.getTarget().isAlive())) {
                return state.setAndContinue(RUN);
            }
            return state.setAndContinue(WALK);
        }
        return PlayState.STOP;
    }

    private PlayState actionPredicate(AnimationState<PillagerScoutEntity> state) {
        byte action = getActionState();
        if (action != this.lastActionState) {
            state.getController().forceAnimationReset();
            this.lastActionState = action;
        }

        return switch (action) {
            case ACTION_LOOK -> state.setAndContinue(LOOK);
            case ACTION_ALERT -> state.setAndContinue(ALERT);
            case ACTION_UNSHEATHE -> state.setAndContinue(UNSHEATHE);
            case ACTION_SHEATHE -> state.setAndContinue(SHEATHE);
            case ACTION_ATTACK1 -> state.setAndContinue(ATTACK1);
            case ACTION_ATTACK2 -> state.setAndContinue(ATTACK2);
            default -> {
                if (this.swinging && state.getController().getAnimationState() == AnimationController.State.STOPPED) {
                    state.getController().forceAnimationReset();
                    yield state.setAndContinue(alternateAttack ? ATTACK2 : ATTACK1);
                }
                yield PlayState.STOP;
            }
        };
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
