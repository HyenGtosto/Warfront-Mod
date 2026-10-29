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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
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

/**
 * Pillager Cannoneer — heavy base-defense sentry wielding a handheld heavy culverin / bombard.
 * Stationed on parapets and wall bastions; fires weighted ballistic cannon bombs with a seamless
 * chained combat loop: Aim (30t / 1.5s) -> Shoot (17t / 0.833s, fires at tick 3) -> Reload (55t / 2.75s) -> Idle Cooldown (60t / 3.0s).
 */
public class PillagerCannoneerEntity extends AbstractIllager implements GeoEntity {

    public static final byte STATE_IDLE = 0;
    public static final byte STATE_AIMING = 1;
    public static final byte STATE_SHOOTING = 2;
    public static final byte STATE_RELOADING = 3;
    public static final byte STATE_COOLDOWN = 4;

    private static final EntityDataAccessor<Byte> COMBAT_STATE =
            SynchedEntityData.defineId(PillagerCannoneerEntity.class, EntityDataSerializers.BYTE);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation AIMING = RawAnimation.begin().thenPlay("aiming");
    private static final RawAnimation SHOOTING = RawAnimation.begin().thenPlay("shooting");
    private static final RawAnimation RELOADING = RawAnimation.begin().thenPlay("reloading");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private byte lastCombatState = STATE_IDLE;

    public PillagerCannoneerEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 10;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.20D)
                .add(Attributes.ARMOR, 4.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.50D)
                .add(Attributes.FOLLOW_RANGE, 36.0D);
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                if (getCombatState() != STATE_IDLE && getCombatState() != STATE_COOLDOWN) {
                    yBodyRot = getYRot();
                    yHeadRot = getYRot();
                } else {
                    super.clientTick();
                }
            }
        };
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
        this.goalSelector.addGoal(1, new CannoneerAttackGoal(this, 36.0F));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Raider.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Zombie.class, true));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        controllers.add(new AnimationController<>(this, "combat", 2, this::combatPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerCannoneerEntity> state) {
        // Cede bone control completely during action combat animations (Aiming, Shooting, Reloading)
        if (getCombatState() != STATE_IDLE && getCombatState() != STATE_COOLDOWN) {
            return PlayState.STOP;
        }

        boolean isMoving = state.isMoving()
                || this.walkAnimation.isMoving()
                || this.walkAnimation.speed() > 0.001F
                || state.getLimbSwingAmount() > 0.001F
                || (this.getDeltaMovement().horizontalDistanceSqr() > 0.00005D)
                || (this.getX() != this.xo || this.getZ() != this.zo);

        if (isMoving) {
            if (this.isSprinting() || this.walkAnimation.speed() > 0.35F) {
                return state.setAndContinue(RUN);
            }
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState combatPredicate(AnimationState<PillagerCannoneerEntity> state) {
        byte s = getCombatState();
        if (s != this.lastCombatState) {
            state.getController().forceAnimationReset();
            this.lastCombatState = s;
        }

        return switch (s) {
            case STATE_AIMING -> state.setAndContinue(AIMING);
            case STATE_SHOOTING -> state.setAndContinue(SHOOTING);
            case STATE_RELOADING -> state.setAndContinue(RELOADING);
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
        return AbstractIllager.IllagerArmPose.NEUTRAL;
    }

    /**
     * AI Goal coordinating chained stationary parapet aiming, firing weighted cannon bombs, reloading, and post-shot idle cooldown.
     */
    static class CannoneerAttackGoal extends Goal {
        private final PillagerCannoneerEntity cannoneer;
        private final float maxAttackDist;
        private int phaseTicks = 0;
        private int currentPhase = STATE_IDLE;

        public CannoneerAttackGoal(PillagerCannoneerEntity cannoneer, float maxAttackDist) {
            this.cannoneer = cannoneer;
            this.maxAttackDist = maxAttackDist;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.cannoneer.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity target = this.cannoneer.getTarget();
            return (target != null && target.isAlive()) || this.currentPhase != STATE_IDLE;
        }

        @Override
        public void start() {
            super.start();
            this.currentPhase = STATE_AIMING;
            this.phaseTicks = 0;
            this.cannoneer.setCombatState(STATE_AIMING);
            this.cannoneer.getNavigation().stop();
        }

        @Override
        public void stop() {
            super.stop();
            this.currentPhase = STATE_IDLE;
            this.cannoneer.setCombatState(STATE_IDLE);
            this.phaseTicks = 0;
        }

        @Override
        public void tick() {
            LivingEntity target = this.cannoneer.getTarget();
            if ((target == null || !target.isAlive()) && (this.currentPhase == STATE_IDLE || this.currentPhase == STATE_COOLDOWN)) {
                stop();
                return;
            }

            boolean canSee = target != null && this.cannoneer.getSensing().hasLineOfSight(target);
            double distSq = target != null ? this.cannoneer.distanceToSqr(target) : Double.MAX_VALUE;
            double maxDistSq = this.maxAttackDist * this.maxAttackDist;

            // Orient directly toward target during active tracking
            if (target != null && this.currentPhase != STATE_COOLDOWN) {
                double dX = target.getX() - this.cannoneer.getX();
                double dZ = target.getZ() - this.cannoneer.getZ();
                float targetYaw = (float) (Mth.atan2(dZ, dX) * (180.0D / Math.PI)) - 90.0F;
                this.cannoneer.setYRot(targetYaw);
                this.cannoneer.yBodyRot = targetYaw;
                this.cannoneer.yHeadRot = targetYaw;
                this.cannoneer.getLookControl().setLookAt(target, 30.0F, 30.0F);
            }

            this.phaseTicks++;

            switch (this.currentPhase) {
                case STATE_AIMING -> {
                    this.cannoneer.getNavigation().stop();

                    // Fuse sizzle telegraph particles and sound at the cannon vent
                    if (this.phaseTicks % 5 == 0 && this.cannoneer.level() instanceof ServerLevel sl) {
                        sl.sendParticles(ParticleTypes.SMALL_FLAME, this.cannoneer.getX(), this.cannoneer.getEyeY() - 0.1D, this.cannoneer.getZ(), 2, 0.1D, 0.1D, 0.1D, 0.01D);
                        sl.sendParticles(ParticleTypes.SMOKE, this.cannoneer.getX(), this.cannoneer.getEyeY() - 0.1D, this.cannoneer.getZ(), 2, 0.1D, 0.1D, 0.1D, 0.01D);
                        this.cannoneer.playSound(SoundEvents.FIRECHARGE_USE, 0.8F, 1.6F);
                    }

                    // 30 ticks (1.5s) full aiming sequence, ending at braced aim pose
                    if (this.phaseTicks >= 30) {
                        this.currentPhase = STATE_SHOOTING;
                        this.phaseTicks = 0;
                        this.cannoneer.setCombatState(STATE_SHOOTING);
                    }
                }
                case STATE_SHOOTING -> {
                    this.cannoneer.getNavigation().stop();

                    // Firing cannon bomb on tick 3 synchronized with explosive blast frame (t = 0.125s) in animation
                    if (this.phaseTicks == 3 && target != null && !this.cannoneer.level().isClientSide) {
                        CannonBombEntity bomb = new CannonBombEntity(this.cannoneer.level(), this.cannoneer);
                        double dX = target.getX() - this.cannoneer.getX();
                        double dY = target.getY(0.5D) - bomb.getY();
                        double dZ = target.getZ() - this.cannoneer.getZ();
                        double horizDist = Math.sqrt(dX * dX + dZ * dZ);

                        // Distance-adaptive high-gravity ballistic lob preserving 1.722 blocks/tick horizontal speed
                        double flightTicks = Math.max(1.0D, horizDist / 1.722D);
                        double aimY = dY + 0.5D * 0.085D * flightTicks * flightTicks;
                        float launchSpeed = (float) (Math.sqrt(horizDist * horizDist + aimY * aimY) / flightTicks);
                        bomb.shoot(dX, aimY, dZ, launchSpeed, 1.2F);
                        this.cannoneer.level().addFreshEntity(bomb);

                        // Recoil knockback impulse on cannoneer
                        Vec3 recoil = this.cannoneer.getLookAngle().reverse().scale(0.20D);
                        this.cannoneer.push(recoil.x, 0.05D, recoil.z);
                        this.cannoneer.hurtMarked = true;

                        this.cannoneer.playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.6F, 1.4F);
                    }

                    // 17 ticks (0.833s) full recoil recovery, returning to rest pose
                    if (this.phaseTicks >= 17) {
                        this.currentPhase = STATE_RELOADING;
                        this.phaseTicks = 0;
                        this.cannoneer.setCombatState(STATE_RELOADING);
                    }
                }
                case STATE_RELOADING -> {
                    this.cannoneer.getNavigation().stop();

                    // Synchronized sound cues matching the 2.75s reload animation
                    if (this.phaseTicks == 27) {
                        // Left hand reaches belt pouch (t = 1.33s)
                        this.cannoneer.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.8F, 1.0F);
                    } else if (this.phaseTicks == 40) {
                        // Bomb slides into muzzle (t = 2.0s)
                        this.cannoneer.playSound(SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), 0.9F, 0.9F);
                    } else if (this.phaseTicks == 43) {
                        // Seating inside barrel (t = 2.125s)
                        this.cannoneer.playSound(SoundEvents.PISTON_CONTRACT, 0.7F, 1.2F);
                    } else if (this.phaseTicks == 50) {
                        // Rising up from kneeling crouch (t = 2.5s)
                        this.cannoneer.playSound(SoundEvents.ARMOR_EQUIP_CHAIN.value(), 0.7F, 1.1F);
                    }

                    // 55 ticks (2.75s) complete reload sequence, returning to rest pose
                    if (this.phaseTicks >= 55) {
                        this.currentPhase = STATE_COOLDOWN;
                        this.phaseTicks = 0;
                        this.cannoneer.setCombatState(STATE_COOLDOWN);
                    }
                }
                case STATE_COOLDOWN -> {
                    // Entity is in idle state for 3 seconds (60 ticks) after shooting/reloading
                    if (this.phaseTicks >= 40) {
                        if (target != null && target.isAlive() && distSq <= maxDistSq && canSee) {
                            this.currentPhase = STATE_AIMING;
                            this.phaseTicks = 0;
                            this.cannoneer.setCombatState(STATE_AIMING);
                        } else {
                            stop();
                        }
                    }
                }
            }
        }
    }
}
