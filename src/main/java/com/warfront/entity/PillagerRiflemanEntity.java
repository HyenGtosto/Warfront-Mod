package com.warfront.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Spider;
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
 * Pillager Rifleman — Elite marksman armed with a bolt-action Mauser 98 rifle and bayonet.
 * Features an internal 5-round magazine, bolt cycling between shots, tactical reloading,
 * close-quarters bayonet strikes, and high-velocity armor-piercing ballistics.
 */
public class PillagerRiflemanEntity extends AbstractIllager implements GeoEntity, RangedAttackMob {

    public static final byte STATE_IDLE = 0;
    public static final byte STATE_IDLE_TO_AIM = 1;
    public static final byte STATE_AIMING = 2;
    public static final byte STATE_SHOOTING = 3;
    public static final byte STATE_BOLT_CYCLE = 4;
    public static final byte STATE_RELOADING = 5;
    public static final byte STATE_BAYONET_STAB = 6;
    public static final byte STATE_AIM_TO_IDLE = 7;

    public static final int MAX_AMMO = 5;

    private static final EntityDataAccessor<Byte> COMBAT_STATE =
            SynchedEntityData.defineId(PillagerRiflemanEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> CURRENT_AMMO =
            SynchedEntityData.defineId(PillagerRiflemanEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation IDLE_TO_AIM = RawAnimation.begin().thenPlayAndHold("idle_to_aim");
    private static final RawAnimation AIMING = RawAnimation.begin().thenLoop("aiming");
    private static final RawAnimation SHOOT = RawAnimation.begin().thenPlayAndHold("shoot");
    private static final RawAnimation COCK_BOLT = RawAnimation.begin().thenPlayAndHold("cock_bolt");
    private static final RawAnimation RELOAD = RawAnimation.begin().thenPlayAndHold("reload");
    private static final RawAnimation BAYONET_STAB = RawAnimation.begin().thenPlayAndHold("bayonet_stab");
    private static final RawAnimation AIM_TO_IDLE = RawAnimation.begin().thenPlayAndHold("aim_to_idle");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private byte lastCombatState = STATE_IDLE;
    private RiflemanAttackGoal attackGoal;

    public PillagerRiflemanEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 12;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 30.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.22D)
                .add(Attributes.ATTACK_DAMAGE, 7.0D) // Bayonet strike damage
                .add(Attributes.ARMOR, 6.0D)
                .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                if (getCombatState() != STATE_IDLE) {
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
        builder.define(CURRENT_AMMO, MAX_AMMO);
    }

    public byte getCombatState() {
        return this.entityData.get(COMBAT_STATE);
    }

    public void setCombatState(byte state) {
        this.entityData.set(COMBAT_STATE, state);
    }

    public int getAmmo() {
        return this.entityData.get(CURRENT_AMMO);
    }

    public void setAmmo(int ammo) {
        this.entityData.set(CURRENT_AMMO, Math.clamp(ammo, 0, MAX_AMMO));
    }

    /**
     * Governs bone visibility in PillagerRiflemanModel:
     * When true (during bolt cocking & reloading), real arm parts are hidden and dummy_right_arm is visible.
     */
    public boolean isUsingDummyArm() {
        byte s = getCombatState();
        return s == STATE_BOLT_CYCLE || s == STATE_RELOADING;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putInt("CurrentAmmo", getAmmo());
        compound.putByte("CombatState", getCombatState());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.contains("CurrentAmmo")) {
            setAmmo(compound.getInt("CurrentAmmo"));
        }
        if (compound.contains("CombatState")) {
            setCombatState(compound.getByte("CombatState"));
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurtResult = super.hurt(source, amount);
        if (hurtResult && amount > 0.0F && !this.level().isClientSide) {
            Entity attacker = source.getEntity();
            if (this.attackGoal != null) {
                this.attackGoal.onHurt(source, amount, attacker);
            }
        }
        return hurtResult;
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.attackGoal = new RiflemanAttackGoal(this, 1.0D, 44.0F);
        this.goalSelector.addGoal(2, this.attackGoal);
        this.goalSelector.addGoal(3, new Raider.HoldGroundAttackGoal(this, 10.0F));
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
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        if (isRiflemanInLineOfSight(target)) {
            return;
        }

        RifleBulletEntity bullet = new RifleBulletEntity(this.level(), this);
        double dX = target.getX() - this.getX();
        double dY = target.getY(0.5D) - bullet.getY();
        double dZ = target.getZ() - this.getZ();

        bullet.shoot(dX, dY, dZ, 4.8F, 0.15F);

        // Punchy gunshot audio: deep thunder + explosive pop
        this.level().playSound(null, this.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.8F, 1.8F);
        this.level().playSound(null, this.blockPosition(), SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.0F, 0.6F);

        if (this.level() instanceof ServerLevel sl) {
            Vec3 muzzlePos = this.getEyePosition().add(this.getLookAngle().scale(0.8D));
            sl.sendParticles(ParticleTypes.SMALL_FLAME, muzzlePos.x, muzzlePos.y, muzzlePos.z, 2, 0.05, 0.05, 0.05, 0.02);
            sl.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, muzzlePos.x, muzzlePos.y, muzzlePos.z, 3, 0.05, 0.05, 0.05, 0.01);
        }

        this.level().addFreshEntity(bullet);
    }

    /**
     * Line of Fire Safety: checks if another allied Rifleman or Pillager is in direct line of fire.
     */
    public boolean isRiflemanInLineOfSight(LivingEntity target) {
        if (target == null || !target.isAlive()) return false;

        Vec3 start = this.getEyePosition();
        Vec3 end = new Vec3(target.getX(), target.getY(0.5D), target.getZ());
        Vec3 rayVec = end.subtract(start);
        double distTarget = rayVec.length();
        if (distTarget < 0.2D) return false;

        Vec3 rayDir = rayVec.normalize();
        AABB corridorBox = this.getBoundingBox().expandTowards(rayVec).inflate(1.5D);

        List<PillagerRiflemanEntity> fellows = this.level().getEntitiesOfClass(
                PillagerRiflemanEntity.class,
                corridorBox,
                other -> other != this && other.isAlive()
        );

        for (PillagerRiflemanEntity other : fellows) {
            Vec3 toOther = other.getBoundingBox().getCenter().subtract(start);
            double projection = toOther.dot(rayDir);
            if (projection <= 0.2D || projection >= distTarget - 0.4D) continue;

            AABB inflatedBox = other.getBoundingBox().inflate(0.35D, 0.25D, 0.35D);
            if (inflatedBox.clip(start, end).isPresent() || inflatedBox.contains(start)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        controllers.add(new AnimationController<>(this, "attack", 2, this::attackPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerRiflemanEntity> state) {
        // Cede bone control completely during combat actions (Aiming, Shooting, Bolt, Reload, Bayonet)
        if (getCombatState() != STATE_IDLE) {
            return PlayState.STOP;
        }

        boolean isMoving = state.isMoving()
                || this.walkAnimation.isMoving()
                || this.walkAnimation.speed() > 0.001F
                || state.getLimbSwingAmount() > 0.001F
                || (this.getDeltaMovement().horizontalDistanceSqr() > 0.00005D)
                || (this.getX() != this.xo || this.getZ() != this.zo);

        if (isMoving) {
            if ((this.isAggressive() && getCombatState() == STATE_IDLE) || (this.getTarget() != null && this.getTarget().isAlive() && getCombatState() == STATE_IDLE)) {
                return state.setAndContinue(RUN);
            }
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState attackPredicate(AnimationState<PillagerRiflemanEntity> state) {
        byte s = getCombatState();

        if (s == STATE_IDLE) {
            if (this.lastCombatState != STATE_IDLE) {
                state.getController().forceAnimationReset();
                this.lastCombatState = s;
            }
            return PlayState.STOP;
        }

        this.lastCombatState = s;

        return switch (s) {
            case STATE_IDLE_TO_AIM -> state.setAndContinue(IDLE_TO_AIM);
            case STATE_AIMING -> state.setAndContinue(AIMING);
            case STATE_SHOOTING -> state.setAndContinue(SHOOT);
            case STATE_BOLT_CYCLE -> state.setAndContinue(COCK_BOLT);
            case STATE_RELOADING -> state.setAndContinue(RELOAD);
            case STATE_BAYONET_STAB -> state.setAndContinue(BAYONET_STAB);
            case STATE_AIM_TO_IDLE -> state.setAndContinue(AIM_TO_IDLE);
            default -> PlayState.STOP;
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
        byte s = getCombatState();
        if (s == STATE_AIMING || s == STATE_SHOOTING || s == STATE_IDLE_TO_AIM) {
            return AbstractIllager.IllagerArmPose.CROSSBOW_HOLD;
        }
        if (s == STATE_RELOADING || s == STATE_BOLT_CYCLE) {
            return AbstractIllager.IllagerArmPose.CROSSBOW_CHARGE;
        }
        return AbstractIllager.IllagerArmPose.NEUTRAL;
    }

    /**
     * AI Goal coordinating sniper spacing, aiming transitions, bolt cycling, reloading increments,
     * bayonet close-quarters defense, and out-of-combat magazine top-offs.
     */
    static class RiflemanAttackGoal extends Goal {
        private final PillagerRiflemanEntity mob;
        private final double speedModifier;
        private final float attackRadius;
        private int attackPhaseTicks = 0;
        private int currentPhase = STATE_IDLE;
        private int seeTime = 0;
        private int repositionCooldown = 0;
        private int outOfCombatTicks = 0;

        public RiflemanAttackGoal(PillagerRiflemanEntity mob, double speedModifier, float attackRadius) {
            this.mob = mob;
            this.speedModifier = speedModifier;
            this.attackRadius = attackRadius;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        public void onHurt(DamageSource source, float amount, Entity attacker) {
            // 1. If taking damage while reloading:
            if (this.currentPhase == STATE_RELOADING) {
                // User directive: "If ammo == 0 interrupt shouldnt work."
                if (this.mob.getAmmo() == 0) {
                    return; // Do not interrupt, stay in reload
                }

                // Ammo > 0: reload IS interrupted!
                // User directive: "the bayonet reflex should work after getting hit/interrupted not just proximity."
                double distSqToAttacker = (attacker != null) ? this.mob.distanceToSqr(attacker) : Double.MAX_VALUE;
                if (attacker instanceof LivingEntity livingAttacker && distSqToAttacker <= 3.0D * 3.0D) {
                    this.mob.setTarget(livingAttacker);
                    this.currentPhase = STATE_BAYONET_STAB;
                    this.attackPhaseTicks = 0;
                    this.mob.setCombatState(STATE_BAYONET_STAB);
                } else {
                    if (attacker instanceof LivingEntity livingAttacker && this.mob.canAttack(livingAttacker)) {
                        this.mob.setTarget(livingAttacker);
                    }
                    this.currentPhase = STATE_AIMING;
                    this.attackPhaseTicks = 0;
                    this.mob.setCombatState(STATE_AIMING);
                }
                return;
            }

            // 2. Reflex bayonet stab when hit during aiming or bolt cycling if attacker is within melee reach (<= 3.0m):
            if (this.currentPhase == STATE_AIMING || this.currentPhase == STATE_BOLT_CYCLE) {
                double distSqToAttacker = (attacker != null) ? this.mob.distanceToSqr(attacker) : Double.MAX_VALUE;
                if (attacker instanceof LivingEntity livingAttacker && distSqToAttacker <= 3.0D * 3.0D) {
                    this.mob.setTarget(livingAttacker);
                    this.currentPhase = STATE_BAYONET_STAB;
                    this.attackPhaseTicks = 0;
                    this.mob.setCombatState(STATE_BAYONET_STAB);
                }
            }
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            // Invariant Pitfall 25: Must tick every game tick to maintain strict sync with animations
            return true;
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.mob.getTarget();
            if (target != null && target.isAlive()) {
                return true;
            }
            // Out of combat top-off: if ammo < 5 and out of combat for >= 300 ticks (15s)
            if (this.outOfCombatTicks >= 300 && this.mob.getAmmo() < MAX_AMMO) {
                return true;
            }
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            // Invariant Pitfall 26: Prevent aborting mid-animation or transitional recovery
            LivingEntity target = this.mob.getTarget();
            if (target != null && target.isAlive()) {
                return true;
            }
            return this.currentPhase == STATE_BOLT_CYCLE
                    || this.currentPhase == STATE_RELOADING
                    || this.currentPhase == STATE_BAYONET_STAB
                    || this.currentPhase == STATE_AIM_TO_IDLE;
        }

        @Override
        public void start() {
            super.start();
            LivingEntity target = this.mob.getTarget();
            if (target != null && target.isAlive()) {
                this.currentPhase = STATE_IDLE_TO_AIM;
                this.attackPhaseTicks = 0;
                this.mob.setCombatState(STATE_IDLE_TO_AIM);
            } else if (this.mob.getAmmo() < MAX_AMMO) {
                // Out of combat reload
                this.currentPhase = STATE_RELOADING;
                this.attackPhaseTicks = 0;
                this.mob.setCombatState(STATE_RELOADING);
            }
            this.repositionCooldown = 0;
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

            if (target == null || !target.isAlive()) {
                this.outOfCombatTicks++;
                handleTargetlessTick();
                return;
            }

            this.outOfCombatTicks = 0;
            double distSq = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
            boolean canSee = this.mob.getSensing().hasLineOfSight(target);

            if (canSee) {
                this.seeTime++;
            } else {
                this.seeTime = 0;
            }

            if (this.repositionCooldown > 0) {
                this.repositionCooldown--;
            }

            // Directional lock during stationary weapon actions
            if (this.currentPhase == STATE_AIMING
                    || this.currentPhase == STATE_SHOOTING
                    || this.currentPhase == STATE_IDLE_TO_AIM
                    || this.currentPhase == STATE_BAYONET_STAB) {
                this.mob.getNavigation().stop();
                double dX = target.getX() - this.mob.getX();
                double dZ = target.getZ() - this.mob.getZ();
                float targetYaw = (float) (Math.atan2(dZ, dX) * (180.0D / Math.PI)) - 90.0F;
                this.mob.setYRot(targetYaw);
                this.mob.yBodyRot = targetYaw;
                this.mob.yHeadRot = targetYaw;
                this.mob.getLookControl().setLookAt(target, 60.0F, 60.0F);
            } else {
                // Tactical spacing during bolt cycle or reloading
                this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
                applyTacticalSpacing(target, distSq, canSee);
            }

            this.attackPhaseTicks++;

            switch (this.currentPhase) {
                case STATE_IDLE_TO_AIM -> {
                    this.mob.setCombatState(STATE_IDLE_TO_AIM);
                    if (this.attackPhaseTicks >= 10) { // 0.5s transition
                        this.currentPhase = STATE_AIMING;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_AIMING);
                    }
                }
                case STATE_AIMING -> {
                    this.mob.setCombatState(STATE_AIMING);

                    // Bayonet close-quarters defense: target within 2.5 blocks (distSq <= 6.25)
                    if (distSq <= 6.25D) {
                        this.currentPhase = STATE_BAYONET_STAB;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_BAYONET_STAB);
                        return;
                    }

                    // If completely empty, transition immediately to reload
                    if (this.mob.getAmmo() <= 0) {
                        this.currentPhase = STATE_RELOADING;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_RELOADING);
                        return;
                    }

                    boolean blockedByFellow = this.mob.isRiflemanInLineOfSight(target);

                    // Aim acquisition: 20 ticks (1.0s)
                    if (this.attackPhaseTicks >= 20 && canSee) {
                        if (blockedByFellow) {
                            this.attackPhaseTicks = 20; // Hold fire!
                            this.repositionCooldown = 0;
                        } else {
                            this.currentPhase = STATE_SHOOTING;
                            this.attackPhaseTicks = 0;
                            this.mob.setCombatState(STATE_SHOOTING);
                        }
                    }
                }
                case STATE_SHOOTING -> {
                    this.mob.setCombatState(STATE_SHOOTING);
                    // Fire high-velocity rifle round on tick 2 synchronized with gunshot recoil jerk
                    if (this.attackPhaseTicks == 2) {
                        if (!this.mob.isRiflemanInLineOfSight(target)) {
                            this.mob.performRangedAttack(target, 1.0F);
                            this.mob.setAmmo(this.mob.getAmmo() - 1);
                        }
                    }
                    // Full shooting animation length (0.5s = 10 ticks)
                    if (this.attackPhaseTicks >= 10) {
                        this.attackPhaseTicks = 0;
                        if (this.mob.getAmmo() > 0) {
                            // Rounds remaining: cycle bolt to chamber next round
                            this.currentPhase = STATE_BOLT_CYCLE;
                            this.mob.setCombatState(STATE_BOLT_CYCLE);
                        } else {
                            // 5th round just fired: skip bolt cocking and start reload directly!
                            this.currentPhase = STATE_RELOADING;
                            this.mob.setCombatState(STATE_RELOADING);
                        }
                    }
                }
                case STATE_BOLT_CYCLE -> {
                    this.mob.setCombatState(STATE_BOLT_CYCLE);
                    // Cocking bolt audio feedback
                    if (this.attackPhaseTicks == 4) {
                        this.mob.playSound(SoundEvents.PILLAGER_AMBIENT, 0.4F, 1.8F);
                    }
                    // Bolt cycle duration: 1.0s = 20 ticks
                    if (this.attackPhaseTicks >= 20) {
                        this.currentPhase = STATE_AIMING;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_AIMING);
                    }
                }
                case STATE_RELOADING -> {
                    this.mob.setCombatState(STATE_RELOADING);
                    // User directive: Increment bullet count at ticks 20, 35, 50, 65, 80
                    if (this.attackPhaseTicks == 20
                            || this.attackPhaseTicks == 35
                            || this.attackPhaseTicks == 50
                            || this.attackPhaseTicks == 65
                            || this.attackPhaseTicks == 80) {
                        this.mob.setAmmo(this.mob.getAmmo() + 1);
                        this.mob.playSound(SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), 0.8F, 1.3F);
                    }

                    // Reload duration: 4.5s = 90 ticks
                    if (this.attackPhaseTicks >= 90) {
                        this.mob.setAmmo(MAX_AMMO);
                        this.attackPhaseTicks = 0;
                        this.currentPhase = STATE_AIMING;
                        this.mob.setCombatState(STATE_AIMING);
                    }
                }
                case STATE_BAYONET_STAB -> {
                    this.mob.setCombatState(STATE_BAYONET_STAB);
                    // Bayonet thrust damage frame: tick 7
                    if (this.attackPhaseTicks == 7) {
                        if (distSq <= 9.0D) { // within 3.0 blocks reach
                            target.hurt(this.mob.damageSources().mobAttack(this.mob), 7.0F);
                            this.mob.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 0.9F);
                        }
                    }
                    // Full bayonet thrust duration: 0.75s = 15 ticks
                    if (this.attackPhaseTicks >= 15) {
                        this.currentPhase = STATE_AIMING;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_AIMING);
                    }
                }
                case STATE_AIM_TO_IDLE -> {
                    this.mob.setCombatState(STATE_AIM_TO_IDLE);
                    if (this.attackPhaseTicks >= 10) {
                        this.currentPhase = STATE_IDLE;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_IDLE);
                    }
                }
            }
        }

        private void handleTargetlessTick() {
            this.attackPhaseTicks++;
            switch (this.currentPhase) {
                case STATE_BOLT_CYCLE -> {
                    if (this.attackPhaseTicks >= 20) {
                        this.currentPhase = STATE_AIM_TO_IDLE;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_AIM_TO_IDLE);
                    }
                }
                case STATE_RELOADING -> {
                    if (this.attackPhaseTicks == 20
                            || this.attackPhaseTicks == 35
                            || this.attackPhaseTicks == 50
                            || this.attackPhaseTicks == 65
                            || this.attackPhaseTicks == 80) {
                        this.mob.setAmmo(this.mob.getAmmo() + 1);
                        this.mob.playSound(SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), 0.8F, 1.3F);
                    }
                    if (this.attackPhaseTicks >= 90) {
                        this.mob.setAmmo(MAX_AMMO);
                        this.currentPhase = STATE_AIM_TO_IDLE;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_AIM_TO_IDLE);
                    }
                }
                case STATE_AIMING, STATE_IDLE_TO_AIM -> {
                    this.currentPhase = STATE_AIM_TO_IDLE;
                    this.attackPhaseTicks = 0;
                    this.mob.setCombatState(STATE_AIM_TO_IDLE);
                }
                case STATE_AIM_TO_IDLE -> {
                    if (this.attackPhaseTicks >= 10) {
                        this.currentPhase = STATE_IDLE;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_IDLE);
                    }
                }
                case STATE_BAYONET_STAB -> {
                    if (this.attackPhaseTicks >= 15) {
                        this.currentPhase = STATE_AIM_TO_IDLE;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_AIM_TO_IDLE);
                    }
                }
                default -> {
                    // Out-of-combat top-off trigger
                    if (this.outOfCombatTicks >= 300 && this.mob.getAmmo() < MAX_AMMO) {
                        this.currentPhase = STATE_RELOADING;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_RELOADING);
                        this.outOfCombatTicks = 0;
                    }
                }
            }
        }

        private void applyTacticalSpacing(LivingEntity target, double distSq, boolean canSee) {
            double desiredMinDistSq = 12.0D * 12.0D; // 144.0
            double desiredMaxDistSq = 32.0D * 32.0D; // 1024.0

            if (distSq < desiredMinDistSq) {
                // Target too close: back away tactically
                Vec3 awayDir = this.mob.position().subtract(target.position()).normalize();
                Vec3 dest = this.mob.position().add(awayDir.scale(6.0D));
                this.mob.getNavigation().moveTo(dest.x, dest.y, dest.z, this.speedModifier * 1.15D);
            } else if (distSq > desiredMaxDistSq || !canSee) {
                // Target too far or out of sight: close distance
                this.mob.getNavigation().moveTo(target, this.speedModifier);
            } else {
                this.mob.getNavigation().stop();
            }
        }
    }
}
