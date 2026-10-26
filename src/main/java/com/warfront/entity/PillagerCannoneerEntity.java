package com.warfront.entity;

import net.minecraft.core.particles.ParticleTypes;
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
 * Pillager Cannoneer — heavy base-defense sentry wielding a handheld iron culverin.
 * Stationed on parapets and wall bastions; lobs explosive concussive cannonballs with a fuse telegraph and long reload.
 */
public class PillagerCannoneerEntity extends AbstractIllager implements GeoEntity {

    public static final byte STATE_IDLE = 0;
    public static final byte STATE_AIMING = 1;
    public static final byte STATE_SHOOTING = 2;
    public static final byte STATE_RELOADING = 3;

    private static final EntityDataAccessor<Byte> COMBAT_STATE =
            SynchedEntityData.defineId(PillagerCannoneerEntity.class, EntityDataSerializers.BYTE);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walking");
    private static final RawAnimation AIMING = RawAnimation.begin().thenLoop("aiming");
    private static final RawAnimation SHOOTING = RawAnimation.begin().thenPlay("shooting");
    private static final RawAnimation RELOADING = RawAnimation.begin().thenPlay("reloading");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private byte lastCombatState = STATE_IDLE;

    public PillagerCannoneerEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 12;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 45.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.18D)
                .add(Attributes.ARMOR, 4.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.40D)
                .add(Attributes.FOLLOW_RANGE, 36.0D);
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
        controllers.add(new AnimationController<>(this, "attack", 2, this::attackPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerCannoneerEntity> state) {
        boolean isMoving = state.isMoving()
                || this.walkAnimation.isMoving()
                || this.walkAnimation.speed() > 0.001F
                || state.getLimbSwingAmount() > 0.001F
                || (this.getDeltaMovement().horizontalDistanceSqr() > 0.00005D)
                || (this.getX() != this.xo || this.getZ() != this.zo);

        if (isMoving && getCombatState() == STATE_IDLE) {
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState attackPredicate(AnimationState<PillagerCannoneerEntity> state) {
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
        if (getCombatState() == STATE_AIMING || getCombatState() == STATE_SHOOTING) {
            return AbstractIllager.IllagerArmPose.CROSSBOW_HOLD;
        }
        if (getCombatState() == STATE_RELOADING) {
            return AbstractIllager.IllagerArmPose.CROSSBOW_CHARGE;
        }
        return AbstractIllager.IllagerArmPose.NEUTRAL;
    }

    /**
     * AI Goal coordinating stationary parapet aiming, fuse ignition, explosive cannonball firing, and reloading.
     */
    static class CannoneerAttackGoal extends Goal {
        private final PillagerCannoneerEntity cannoneer;
        private final float maxAttackDist;
        private int phaseTicks = 0;
        private int currentPhase = STATE_IDLE;
        private int targetSeeTicks = 0;

        public CannoneerAttackGoal(PillagerCannoneerEntity cannoneer, float maxAttackDist) {
            this.cannoneer = cannoneer;
            this.maxAttackDist = maxAttackDist;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.cannoneer.getTarget();
            return target != null && target.isAlive();
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
            this.targetSeeTicks = 0;
        }

        @Override
        public void tick() {
            LivingEntity target = this.cannoneer.getTarget();
            if (target == null || !target.isAlive()) {
                stop();
                return;
            }

            boolean canSee = this.cannoneer.getSensing().hasLineOfSight(target);
            if (canSee) {
                this.targetSeeTicks++;
            } else {
                this.targetSeeTicks = 0;
            }

            double distSq = this.cannoneer.distanceToSqr(target);
            double maxDistSq = this.maxAttackDist * this.maxAttackDist;

            // Always orient toward target
            this.cannoneer.getLookControl().setLookAt(target, 20.0F, 20.0F);

            phaseTicks++;

            switch (this.currentPhase) {
                case STATE_AIMING -> {
                    this.cannoneer.getNavigation().stop();

                    // Fuse sizzle telegraph particles and sound
                    if (phaseTicks % 5 == 0 && this.cannoneer.level() instanceof ServerLevel sl) {
                        sl.sendParticles(ParticleTypes.SMALL_FLAME, this.cannoneer.getX(), this.cannoneer.getEyeY() - 0.1D, this.cannoneer.getZ(), 2, 0.1D, 0.1D, 0.1D, 0.01D);
                        sl.sendParticles(ParticleTypes.SMOKE, this.cannoneer.getX(), this.cannoneer.getEyeY() - 0.1D, this.cannoneer.getZ(), 2, 0.1D, 0.1D, 0.1D, 0.01D);
                        this.cannoneer.playSound(SoundEvents.FIRECHARGE_USE, 0.8F, 1.6F);
                    }

                    // 25 ticks (1.25s) fuse burning telegraph
                    if (phaseTicks >= 25) {
                        this.currentPhase = STATE_SHOOTING;
                        this.phaseTicks = 0;
                        this.cannoneer.setCombatState(STATE_SHOOTING);
                    }
                }
                case STATE_SHOOTING -> {
                    // Fire cannonball
                    if (!this.cannoneer.level().isClientSide) {
                        CannonballEntity ball = new CannonballEntity(this.cannoneer.level(), this.cannoneer);
                        double dX = target.getX() - this.cannoneer.getX();
                        double dY = target.getY(0.5D) - ball.getY();
                        double dZ = target.getZ() - this.cannoneer.getZ();
                        double horizDist = Math.sqrt(dX * dX + dZ * dZ);

                        // Ballistic trajectory with elevation pitch
                        ball.shoot(dX, dY + horizDist * 0.12D, dZ, 2.2F, 2.0F);
                        this.cannoneer.level().addFreshEntity(ball);

                        // Recoil knockback on cannoneer
                        Vec3 recoil = this.cannoneer.getLookAngle().reverse().scale(0.35D);
                        this.cannoneer.push(recoil.x, 0.1D, recoil.z);
                        this.cannoneer.hurtMarked = true;

                        this.cannoneer.playSound(SoundEvents.GENERIC_EXPLODE.value(), 1.6F, 1.4F);
                    }

                    this.currentPhase = STATE_RELOADING;
                    this.phaseTicks = 0;
                    this.cannoneer.setCombatState(STATE_RELOADING);
                }
                case STATE_RELOADING -> {
                    // Powder ramming reload (70 ticks / 3.5 seconds)
                    if (phaseTicks % 20 == 0) {
                        this.cannoneer.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.8F, 0.9F);
                    }

                    if (phaseTicks >= 70) {
                        // Reload complete: re-evaluate or start new aim cycle
                        if (distSq <= maxDistSq && canSee) {
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
