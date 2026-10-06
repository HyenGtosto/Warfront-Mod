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
import net.minecraft.world.entity.ai.goal.Goal;
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
 * Pillager Commander — general and tactician.
 * Holds backline position behind frontline troops, rallying them with banner swings
 * and sword raises until personally threatened, where he draws his sword for fierce melee defense.
 */
public class PillagerCommanderEntity extends AbstractIllager implements GeoEntity {

    public static final byte ACTION_NONE = 0;
    public static final byte ACTION_UNSHEATHE = 1;
    public static final byte ACTION_SHEATHE = 2;
    public static final byte ACTION_ATTACK1 = 3;
    public static final byte ACTION_ATTACK2 = 4;
    public static final byte ACTION_SWORD_RAISE = 5;
    public static final byte ACTION_BANNER_SWING = 6;

    private static final EntityDataAccessor<Byte> ACTION_STATE =
            SynchedEntityData.defineId(PillagerCommanderEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Boolean> SWORD_DRAWN =
            SynchedEntityData.defineId(PillagerCommanderEntity.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation UNSHEATHE = RawAnimation.begin().thenPlay("unsheathe");
    private static final RawAnimation SHEATHE = RawAnimation.begin().thenPlay("sheathe");
    private static final RawAnimation ATTACK1 = RawAnimation.begin().thenPlay("attack1");
    private static final RawAnimation ATTACK2 = RawAnimation.begin().thenPlay("attack2");
    private static final RawAnimation SWORD_RAISE = RawAnimation.begin().thenPlay("sword_raise");
    private static final RawAnimation BANNER_SWING = RawAnimation.begin().thenPlay("banner_swing");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int actionDurationTicks = 0;
    private int attackCooldownTicks = 0;
    private boolean alternateAttack = false;
    private LivingEntity pendingAttackTarget = null;
    private int attackImpactTicks = -1;
    private byte lastActionState = ACTION_NONE;

    public PillagerCommanderEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 12;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 36.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.30D)
                .add(Attributes.ATTACK_DAMAGE, 8.5D)
                .add(Attributes.ARMOR, 7.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 36.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ACTION_STATE, ACTION_NONE);
        builder.define(SWORD_DRAWN, false);
    }

    public byte getActionState() {
        return this.entityData.get(ACTION_STATE);
    }

    public void setActionState(byte state, int durationTicks) {
        this.entityData.set(ACTION_STATE, state);
        this.actionDurationTicks = durationTicks;
    }

    public boolean isSwordDrawn() {
        return this.entityData.get(SWORD_DRAWN);
    }

    public void setSwordDrawn(boolean drawn) {
        this.entityData.set(SWORD_DRAWN, drawn);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new CommanderTacticsGoal(this));
        this.goalSelector.addGoal(2, new CommanderMeleeGoal(this, 1.25D));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Raider.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Zombie.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractSkeleton.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Creeper.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Spider.class, true));
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide) {
            LivingEntity target = this.getTarget();
            boolean hasTarget = target != null && target.isAlive();
            this.setAggressive(hasTarget);
            this.setSprinting(hasTarget && isDirectlyThreatened() && (this.getDeltaMovement().horizontalDistanceSqr() > 0.005D));

            if (this.actionDurationTicks > 0) {
                this.actionDurationTicks--;
                if (this.actionDurationTicks <= 0) {
                    this.entityData.set(ACTION_STATE, ACTION_NONE);
                }
            }

            if (this.attackCooldownTicks > 0) {
                this.attackCooldownTicks--;
            }

            // Damage impact frame calculation (contact frame at 8 ticks)
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
                            this.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 0.85F);
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

    public boolean isDirectlyThreatened() {
        LivingEntity target = getTarget();
        if (target == null) return false;

        // Player is right in face (< 4 blocks)
        if (distanceToSqr(target) < 16.0D) {
            return true;
        }

        // Commander took recent damage
        if (this.getLastHurtByMob() != null && (this.tickCount - this.getLastHurtByMobTimestamp() < 120)) {
            return true;
        }

        // No allies nearby to hide behind
        List<AbstractIllager> allies = this.level().getEntitiesOfClass(
                AbstractIllager.class,
                this.getBoundingBox().inflate(18.0D),
                e -> e != this && e.isAlive()
        );
        return allies.isEmpty();
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
    public boolean doHurtTarget(Entity target) {
        if (!(target instanceof LivingEntity livingTarget)) return false;
        if (this.attackImpactTicks > 0 || this.actionDurationTicks > 0 || this.attackCooldownTicks > 0) return false;

        if (!this.level().isClientSide) {
            this.pendingAttackTarget = livingTarget;
            this.attackImpactTicks = 8; // Contact frame at 8 ticks (~0.4s)
            this.attackCooldownTicks = 24; // 18 ticks animation + 6 ticks recovery
            byte attackType = alternateAttack ? ACTION_ATTACK2 : ACTION_ATTACK1;
            alternateAttack = !alternateAttack;
            setActionState(attackType, 18);
            this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.9F);
        }
        return true;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        controllers.add(new AnimationController<>(this, "action", 2, this::actionPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerCommanderEntity> state) {
        boolean isMoving = state.isMoving()
                || this.walkAnimation.isMoving()
                || this.walkAnimation.speed() > 0.001F
                || state.getLimbSwingAmount() > 0.001F
                || (this.getDeltaMovement().horizontalDistanceSqr() > 0.00005D)
                || (this.getX() != this.xo || this.getZ() != this.zo);
        if (isMoving) {
            if (this.isSprinting() || this.isAggressive() || (this.getTarget() != null && isDirectlyThreatened())) {
                return state.setAndContinue(RUN);
            }
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState actionPredicate(AnimationState<PillagerCommanderEntity> state) {
        byte action = getActionState();
        if (action != this.lastActionState) {
            state.getController().forceAnimationReset();
            this.lastActionState = action;
        }

        return switch (action) {
            case ACTION_BANNER_SWING -> state.setAndContinue(BANNER_SWING);
            case ACTION_SWORD_RAISE -> state.setAndContinue(SWORD_RAISE);
            case ACTION_UNSHEATHE -> state.setAndContinue(UNSHEATHE);
            case ACTION_SHEATHE -> state.setAndContinue(SHEATHE);
            case ACTION_ATTACK1 -> state.setAndContinue(ATTACK1);
            case ACTION_ATTACK2 -> state.setAndContinue(ATTACK2);
            default -> {
                state.getController().forceAnimationReset();
                yield PlayState.STOP;
            }
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
        if (isSwordDrawn() || isAggressive()) {
            return AbstractIllager.IllagerArmPose.ATTACKING;
        }
        return AbstractIllager.IllagerArmPose.NEUTRAL;
    }

    /**
     * AI Goal: Commander stands behind allies and periodically casts sword raise or banner swing to rally troops.
     */
    static class CommanderTacticsGoal extends Goal {
        private final PillagerCommanderEntity commander;
        private int buffCooldownTicks = 100;
        private boolean nextBuffIsBanner = true;

        public CommanderTacticsGoal(PillagerCommanderEntity commander) {
            this.commander = commander;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.commander.getTarget();
            return target != null && target.isAlive() && !this.commander.isDirectlyThreatened();
        }

        @Override
        public void tick() {
            LivingEntity target = this.commander.getTarget();
            if (target == null) return;

            this.commander.getLookControl().setLookAt(target, 30.0F, 30.0F);

            // Find nearby allies to form frontline
            List<AbstractIllager> allies = this.commander.level().getEntitiesOfClass(
                    AbstractIllager.class,
                    this.commander.getBoundingBox().inflate(20.0D),
                    e -> e != this.commander && e.isAlive()
            );

            if (!allies.isEmpty()) {
                double avgX = 0, avgZ = 0;
                for (AbstractIllager ally : allies) {
                    avgX += ally.getX();
                    avgZ += ally.getZ();
                }
                avgX /= allies.size();
                avgZ /= allies.size();

                // Position behind allies away from target
                double dirX = avgX - target.getX();
                double dirZ = avgZ - target.getZ();
                double dist = Math.sqrt(dirX * dirX + dirZ * dirZ);
                if (dist > 0.01) {
                    dirX /= dist;
                    dirZ /= dist;
                    double destX = avgX + dirX * 6.0D;
                    double destZ = avgZ + dirZ * 6.0D;
                    this.commander.getNavigation().moveTo(destX, this.commander.getY(), destZ, 1.0D);
                }
            }

            // Periodic buff animation cycle (banner swing or sword raise)
            this.buffCooldownTicks--;
            if (this.buffCooldownTicks <= 0 && this.commander.getActionState() == ACTION_NONE) {
                this.buffCooldownTicks = 160 + this.commander.getRandom().nextInt(80); // ~8-12 seconds
                if (nextBuffIsBanner) {
                    this.commander.setActionState(ACTION_BANNER_SWING, 44);
                    this.commander.playSound(SoundEvents.RAID_HORN.value(), 1.2F, 1.0F);
                } else {
                    this.commander.setActionState(ACTION_SWORD_RAISE, 28);
                    this.commander.playSound(SoundEvents.PILLAGER_CELEBRATE, 1.0F, 1.0F);
                }
                nextBuffIsBanner = !nextBuffIsBanner;
                this.commander.getNavigation().stop();
            }
        }
    }

    /**
     * Melee Goal active only when commander is directly threatened / engaged.
     */
    static class CommanderMeleeGoal extends MeleeAttackGoal {
        private final PillagerCommanderEntity commander;

        public CommanderMeleeGoal(PillagerCommanderEntity commander, double speedModifier) {
            super(commander, speedModifier, false);
            this.commander = commander;
        }

        @Override
        public boolean canUse() {
            return super.canUse() && this.commander.isDirectlyThreatened();
        }

        @Override
        public void start() {
            super.start();
            if (!this.commander.isSwordDrawn()) {
                this.commander.setSwordDrawn(true);
                this.commander.setActionState(ACTION_UNSHEATHE, 20);
            }
        }

        @Override
        public void stop() {
            super.stop();
            LivingEntity target = this.commander.getTarget();
            if ((target == null || !target.isAlive() || this.commander.distanceToSqr(target) > 256.0D)
                    && this.commander.isSwordDrawn()
                    && this.commander.getActionState() == ACTION_NONE) {
                this.commander.setSwordDrawn(false);
                this.commander.setActionState(ACTION_SHEATHE, 20);
            }
        }
    }
}
