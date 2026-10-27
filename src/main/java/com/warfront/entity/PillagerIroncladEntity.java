package com.warfront.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.MoveControl;
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
 * Has no handheld weapons; completely blocks incoming frontal projectiles and attacks with its massive iron slab shield.
 * Actively positions itself between approaching players and allied ranged gunners (Marksman, Cannoneer, Rapidbow),
 * prioritizing the ally currently under attack by the player.
 * Features custom MoveControl with decoupled strafe movement, 3.5 deg/tick capped turn rate,
 * and concussive horizontal-knockback shield bashes (6 damage, 4s Nausea + Slowness II daze).
 */
public class PillagerIroncladEntity extends AbstractIllager implements GeoEntity {

    public static final int STATE_IDLE = 0;
    public static final int STATE_SHIELD_UP = 1;
    public static final int STATE_SHIELDED = 2;
    public static final int STATE_SHIELD_DOWN = 3;

    private static final EntityDataAccessor<Integer> SHIELD_STATE =
            SynchedEntityData.defineId(PillagerIroncladEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Boolean> IS_ENRAGED =
            SynchedEntityData.defineId(PillagerIroncladEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Boolean> SHIELD_BASHING =
            SynchedEntityData.defineId(PillagerIroncladEntity.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation SHIELD_UP = RawAnimation.begin().thenPlay("shield_up").thenLoop("shield_hold");
    private static final RawAnimation SHIELD_HOLD = RawAnimation.begin().thenLoop("shield_hold");
    private static final RawAnimation SHIELD_DOWN = RawAnimation.begin().thenPlay("shield_down");
    private static final RawAnimation BASH = RawAnimation.begin().thenPlay("shield_bash");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int lastCombatAction = -1;
    private int shieldTransitionTicks = 0;
    private int outOfCombatTicks = 0;
    private boolean guardingAllyThreat = false;

    public PillagerIroncladEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 16;
        this.moveControl = new IroncladMoveControl(this);
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                yBodyRot = getYRot();
                yHeadRot = getYRot();
            }
        };
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 80.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.20D)
                .add(Attributes.ATTACK_DAMAGE, 6.0D)
                .add(Attributes.ARMOR, 10.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.90D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SHIELD_STATE, STATE_IDLE);
        builder.define(IS_ENRAGED, false);
        builder.define(SHIELD_BASHING, false);
    }

    public int getShieldState() {
        return this.entityData.get(SHIELD_STATE);
    }

    public void setShieldState(int state) {
        this.entityData.set(SHIELD_STATE, state);
    }

    public boolean isShielded() {
        int state = getShieldState();
        return state == STATE_SHIELDED || state == STATE_SHIELD_UP;
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

    public boolean isGuardingAllyThreat() {
        return this.guardingAllyThreat;
    }

    public void setGuardingAllyThreat(boolean threat) {
        this.guardingAllyThreat = threat;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide) {
            LivingEntity target = getTarget();
            boolean hasTargetThreat = (target != null && target.isAlive())
                    || (this.getLastHurtByMob() != null && (this.tickCount - this.getLastHurtByMobTimestamp()) < 100);

            // In combat if directly threatened or if bodyguard goal has detected an ally under threat
            boolean inCombat = hasTargetThreat || this.guardingAllyThreat;

            int state = getShieldState();

            // Transition countdown management:
            if (state == STATE_SHIELD_UP) {
                if (this.shieldTransitionTicks > 0) {
                    this.shieldTransitionTicks--;
                    if (this.shieldTransitionTicks == 0) {
                        setShieldState(STATE_SHIELDED);
                    }
                } else {
                    setShieldState(STATE_SHIELDED);
                }
            } else if (state == STATE_SHIELD_DOWN) {
                if (this.shieldTransitionTicks > 0) {
                    this.shieldTransitionTicks--;
                    if (this.shieldTransitionTicks == 0) {
                        setShieldState(STATE_IDLE);
                    }
                } else {
                    setShieldState(STATE_IDLE);
                }
            }

            // Stance management:
            if (inCombat) {
                this.outOfCombatTicks = 0;
                if (state == STATE_IDLE) {
                    setShieldState(STATE_SHIELD_UP);
                    this.shieldTransitionTicks = 15; // 0.75s
                } else if (state == STATE_SHIELD_DOWN) {
                    // Threat reappeared while lowering shield: re-raise immediately
                    setShieldState(STATE_SHIELD_UP);
                    this.shieldTransitionTicks = 15;
                }
            } else {
                this.outOfCombatTicks++;
                if (this.outOfCombatTicks >= 100) { // 5.0s of continuous peace
                    if (state == STATE_SHIELDED) {
                        setShieldState(STATE_SHIELD_DOWN);
                        this.shieldTransitionTicks = 15; // 0.75s
                    }
                }
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putInt("ShieldState", getShieldState());
        compound.putBoolean("Enraged", isEnraged());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.contains("ShieldState")) {
            setShieldState(compound.getInt("ShieldState"));
        }
        if (compound.contains("Enraged")) {
            setEnraged(compound.getBoolean("Enraged"));
        }
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
     * Absorbs 100% of damage and projectiles within a 160-degree frontal cone while shield is raised (SHIELDED or SHIELD_UP).
     * When shield is lowered (IDLE), attacks deal normal damage.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Shield-bypassing damage (piercing, fall, void, drowning, magic, starvation) penetrates shield
        if (source.is(DamageTypeTags.BYPASSES_SHIELD)
                || source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypeTags.IS_DROWNING)
                || source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.STARVE)) {
            return super.hurt(source, amount);
        }

        Entity attacker = source.getDirectEntity() != null ? source.getDirectEntity() : source.getEntity();
        if (isShielded() && attacker != null) {
            Vec3 look = getLookAngle();
            Vec3 toAttacker = new Vec3(attacker.getX() - getX(), 0.0D, attacker.getZ() - getZ()).normalize();
            double dot = look.x * toAttacker.x + look.z * toAttacker.z;

            // Frontal hemisphere (dot > 0.15 = ~160 degree block arc)
            if (dot > 0.15D) {
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
        // Controller 1: Movement (full body walk, run, idle out of combat)
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        // Controller 2: Combat & Shield Stance (manages shield hold, raise/lower, and shield bash)
        controllers.add(new AnimationController<>(this, "combat", 2, this::combatPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerIroncladEntity> state) {
        if (state.isMoving()) {
            if (this.isSprinting() || this.isEnraged()) {
                return state.setAndContinue(RUN);
            }
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState combatPredicate(AnimationState<PillagerIroncladEntity> state) {
        boolean bashing = isShieldBashing();
        int shieldState = getShieldState();

        // Distinct composite action key: high bit (0x100) indicates active shield bash
        int currentAction = (bashing ? 0x100 : 0) | shieldState;
        if (currentAction != this.lastCombatAction) {
            this.lastCombatAction = currentAction;
            state.getController().forceAnimationReset();
        }

        if (bashing) {
            return state.setAndContinue(BASH);
        }

        if (shieldState == STATE_SHIELDED) {
            return state.setAndContinue(SHIELD_HOLD);
        } else if (shieldState == STATE_SHIELD_UP) {
            return state.setAndContinue(SHIELD_UP);
        } else if (shieldState == STATE_SHIELD_DOWN) {
            return state.setAndContinue(SHIELD_DOWN);
        }

        // When out of combat (STATE_IDLE): stop combat controller and let movement controller drive upper body
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
        return AbstractIllager.IllagerArmPose.NEUTRAL;
    }

    /**
     * Standardized spatial melee arc hit-check matching PillagerEliteEntity.
     * Evaluates all non-allied LivingEntities within a 3.8m, 120-degree frontal cone.
     */
    public void executeShieldBashDamage() {
        double reach = 3.8D;
        float arcDegrees = 120.0F;
        Vec3 look = this.getLookAngle();
        Vec3 origin = this.position().add(0, 1.4, 0);

        AABB attackBox = this.getBoundingBox().inflate(reach, 1.5D, reach);
        List<LivingEntity> potentialTargets = this.level().getEntitiesOfClass(LivingEntity.class, attackBox,
                e -> e != this && e.isAlive() && !this.isAlliedTo(e));

        double minDot = Math.cos(Math.toRadians(arcDegrees * 0.5F));
        boolean hitAny = false;

        for (LivingEntity victim : potentialTargets) {
            Vec3 toTarget = victim.position().add(0, victim.getEyeHeight() * 0.5, 0).subtract(origin);
            if (toTarget.lengthSqr() <= reach * reach) {
                Vec3 dir = toTarget.normalize();
                double dot = look.x * dir.x + look.z * dir.z;
                if (dot > minDot) {
                    DamageSource source = this.damageSources().mobAttack(this);
                    if (victim.hurt(source, 6.0F)) {
                        hitAny = true;

                        // Pure horizontal knockback: 1.8 m/s along strike direction with minimal lift
                        double dx = victim.getX() - this.getX();
                        double dz = victim.getZ() - this.getZ();
                        double len = Math.sqrt(dx * dx + dz * dz);
                        if (len > 0.001D) {
                            victim.push((dx / len) * 1.8D, 0.08D, (dz / len) * 1.8D);
                            victim.hurtMarked = true;
                        }

                        // Concussive daze: Slowness II and Nausea for 4 seconds (80 ticks)
                        victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 1), this);
                        victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 160, 4), this);

                        if (this.level() instanceof ServerLevel sl) {
                            sl.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + 1.2D, victim.getZ(),
                                    12, 0.3D, 0.3D, 0.3D, 0.15D);
                        }
                    }
                }
            }
        }

        if (this.level() instanceof ServerLevel serverLevel) {
            double px = this.getX() + look.x * 1.6D;
            double py = this.getY() + 1.4D;
            double pz = this.getZ() + look.z * 1.6D;
            serverLevel.sendParticles(ParticleTypes.SWEEP_ATTACK, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
        }

        if (hitAny) {
            this.playSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.4F, 0.7F);
            this.playSound(SoundEvents.ANVIL_LAND, 0.9F, 1.2F);
        } else {
            this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.2F, 0.7F);
        }
    }

    /**
     * Custom MoveControl:
     * - Enforces the strict 3.5 deg/tick turn rate cap during movement.
     * - Keeps the Ironclad's face and shield locked toward the target player.
     * - Decomposes movement into forward/backward and strafe vectors so the Ironclad
     *   can backpedal or strafe to intercept positions without turning away from the player.
     */
    public static class IroncladMoveControl extends MoveControl {
        private final PillagerIroncladEntity ironclad;

        public IroncladMoveControl(PillagerIroncladEntity mob) {
            super(mob);
            this.ironclad = mob;
        }

        @Override
        public void tick() {
            // Freeze movement during shield bash so the strike commits
            if (this.ironclad.isShieldBashing()) {
                this.mob.setZza(0.0F);
                this.mob.setXxa(0.0F);
                return;
            }

            if (this.operation == MoveControl.Operation.MOVE_TO) {
                this.operation = MoveControl.Operation.WAIT;
                double dx = this.wantedX - this.mob.getX();
                double dz = this.wantedZ - this.mob.getZ();
                double dy = this.wantedY - this.mob.getY();
                double distSqr = dx * dx + dy * dy + dz * dz;
                if (distSqr < 2.5E-7) {
                    this.mob.setZza(0.0F);
                    this.mob.setXxa(0.0F);
                    return;
                }

                // Direction towards the movement destination
                float moveAngle = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;

                // Face target player if in combat, otherwise face movement direction
                LivingEntity target = this.ironclad.getTarget();
                float targetFacingYaw;
                if (target != null && target.isAlive()) {
                    double tdx = target.getX() - this.mob.getX();
                    double tdz = target.getZ() - this.mob.getZ();
                    targetFacingYaw = (float) (Mth.atan2(tdz, tdx) * (180.0D / Math.PI)) - 90.0F;
                } else {
                    targetFacingYaw = moveAngle;
                }

                // Strictly clamp angular turn speed to 3.5 degrees per tick max
                float currentYaw = this.mob.getYRot();
                float yawDiff = Mth.wrapDegrees(targetFacingYaw - currentYaw);
                float turn = Mth.clamp(yawDiff, -3.5F, 3.5F);
                float newYaw = currentYaw + turn;

                this.mob.setYRot(newYaw);
                this.mob.yBodyRot = newYaw;
                this.mob.yHeadRot = newYaw;

                // Decompose movement vector relative to new facing yaw
                float angleDiff = Mth.wrapDegrees(moveAngle - newYaw);
                double angleRad = Math.toRadians(angleDiff);
                float forward = (float) Math.cos(angleRad);
                float strafe = (float) -Math.sin(angleRad);

                float speed = (float) (this.speedModifier * this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
                this.mob.setSpeed(speed);
                this.mob.setZza(forward * speed);
                this.mob.setXxa(strafe * speed);
            } else {
                // When stationary, smoothly track target at 3.5 deg/tick
                LivingEntity target = this.ironclad.getTarget();
                if (target != null && target.isAlive()) {
                    double tdx = target.getX() - this.mob.getX();
                    double tdz = target.getZ() - this.mob.getZ();
                    float targetFacingYaw = (float) (Mth.atan2(tdz, tdx) * (180.0D / Math.PI)) - 90.0F;
                    float currentYaw = this.mob.getYRot();
                    float yawDiff = Mth.wrapDegrees(targetFacingYaw - currentYaw);
                    float turn = Mth.clamp(yawDiff, -3.5F, 3.5F);
                    float newYaw = currentYaw + turn;
                    this.mob.setYRot(newYaw);
                    this.mob.yBodyRot = newYaw;
                    this.mob.yHeadRot = newYaw;
                }
                this.mob.setZza(0.0F);
                this.mob.setXxa(0.0F);
            }
        }
    }

    /**
     * AI Goal coordinating bodyguard line-of-fire interception, slow turn rate, concussive shield bashes,
     * and last-stand aggressive switch.
     */
    static class IroncladBodyguardGoal extends Goal {
        private final PillagerIroncladEntity ironclad;
        private boolean isBashing = false;
        private int bashTicks = 0;
        private int bashCooldown = 0;

        public IroncladBodyguardGoal(PillagerIroncladEntity ironclad) {
            this.ironclad = ironclad;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.ironclad.getTarget();
            if (target != null && target.isAlive()) {
                return true;
            }
            AABB allySearchArea = this.ironclad.getBoundingBox().inflate(32.0D);
            List<LivingEntity> rangedAllies = this.ironclad.level().getEntitiesOfClass(LivingEntity.class, allySearchArea,
                    e -> e != this.ironclad && e.isAlive() && AlliedFactionHelper.isPillager(e) && isRangedTroop(e));
            for (LivingEntity ally : rangedAllies) {
                if (ally instanceof Mob mob && mob.getTarget() != null && mob.getTarget().isAlive()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            // NEVER abort or interrupt goal while mid-swing in an active shield bash!
            if (this.isBashing) {
                return true;
            }
            return canUse();
        }

        @Override
        public void start() {
            super.start();
            if (!this.isBashing) {
                this.bashTicks = 0;
            }
        }

        @Override
        public void stop() {
            super.stop();
            this.isBashing = false;
            this.bashTicks = 0;
            this.ironclad.setShieldBashing(false);
            this.ironclad.setGuardingAllyThreat(false);
        }

        @Override
        public void tick() {
            AABB allySearchArea = this.ironclad.getBoundingBox().inflate(32.0D);
            List<LivingEntity> rangedAllies = this.ironclad.level().getEntitiesOfClass(LivingEntity.class, allySearchArea,
                    e -> e != this.ironclad && e.isAlive() && AlliedFactionHelper.isPillager(e) && isRangedTroop(e));

            LivingEntity target = this.ironclad.getTarget();
            if (target == null || !target.isAlive()) {
                // If the Ironclad doesn't have an active target, adopt the target of any guarded ranged ally
                for (LivingEntity ally : rangedAllies) {
                    if (ally instanceof Mob mob && mob.getTarget() != null && mob.getTarget().isAlive()) {
                        this.ironclad.setTarget(mob.getTarget());
                        target = mob.getTarget();
                        break;
                    }
                }
                if (target == null || !target.isAlive()) {
                    this.ironclad.setGuardingAllyThreat(false);
                    if (!this.isBashing) {
                        stop();
                        return;
                    }
                }
            }

            if (this.bashCooldown > 0) this.bashCooldown--;

            // Handle active shield bash execution
            if (this.isBashing) {
                this.bashTicks++;
                // Halt movement completely so entity remains planted during bash
                this.ironclad.getNavigation().stop();
                this.ironclad.setDeltaMovement(0.0D, this.ironclad.getDeltaMovement().y, 0.0D);

                // Exactly at Tick 13 (0.65s of the 1.50s animation): visual shield impact connects!
                if (this.bashTicks == 13) {
                    this.ironclad.executeShieldBashDamage();
                }

                // At Tick 30 (1.50s): animation completes
                if (this.bashTicks >= 30) {
                    this.isBashing = false;
                    this.ironclad.setShieldBashing(false);
                    this.bashCooldown = this.ironclad.isEnraged() ? 30 : 45;
                }
                return; // Return immediately so pathfinding doesn't move entity while bashing
            }

            double distToPlayer = target != null ? this.ironclad.distanceTo(target) : Double.MAX_VALUE;

            // 1. Close melee defense: execute concussive shield bash
            if (distToPlayer <= 2.8D && this.bashCooldown <= 0 && !this.isBashing) {
                startShieldBash();
                return;
            }

            // 2. Interception vs Last Stand
            if (!rangedAllies.isEmpty()) {
                // Protecting allies: position directly between player and prioritized ranged ally
                this.ironclad.setGuardingAllyThreat(true);
                this.ironclad.setEnraged(false);
                if (target != null) {
                    LivingEntity priorityAlly = selectPriorityAlly(rangedAllies, target);

                    Vec3 allyPos = priorityAlly.position();
                    Vec3 playerPos = target.position();
                    Vec3 lineOfFire = playerPos.subtract(allyPos);
                    double allyToPlayerDist = lineOfFire.length();

                    if (allyToPlayerDist > 0.001D) {
                        Vec3 dirToPlayer = lineOfFire.scale(1.0D / allyToPlayerDist);
                        // Stand 4.0 blocks ahead of the ally along the line-of-fire (capped at 45% of total distance)
                        double offsetDist = Math.min(4.0D, allyToPlayerDist * 0.45D);
                        // Project intercept point onto the ally's ground elevation to prevent mid-air waypoints
                        double targetX = allyPos.x + dirToPlayer.x * offsetDist;
                        double targetY = allyPos.y;
                        double targetZ = allyPos.z + dirToPlayer.z * offsetDist;

                        if (this.ironclad.distanceToSqr(targetX, targetY, targetZ) > 1.5D * 1.5D) {
                            this.ironclad.getNavigation().moveTo(targetX, targetY, targetZ, 1.15D);
                        } else {
                            this.ironclad.getNavigation().stop();
                        }
                    }
                }
            } else {
                // Last Stand: No ranged allies left to cover! Switch to aggressive advance and bash repeatedly!
                this.ironclad.setGuardingAllyThreat(false);
                this.ironclad.setEnraged(true);
                if (target != null) {
                    this.ironclad.getNavigation().moveTo(target, 1.35D);
                }
            }
        }

        private boolean isRangedTroop(LivingEntity e) {
            return e instanceof PillagerMarksmanEntity
                    || e instanceof PillagerCannoneerEntity
                    || e instanceof PillagerRapidbowEntity;
        }

        /**
         * Prioritizes who the player is attacking:
         * 1. Any ranged ally hurt by the player in the last 100 ticks (5s), picking lowest health if multiple.
         * 2. The ranged ally the player is actively aiming at (closest to player look vector).
         * 3. Nearest ranged ally to the player.
         */
        private LivingEntity selectPriorityAlly(List<LivingEntity> allies, LivingEntity player) {
            long gameTime = this.ironclad.level().getGameTime();

            // Tier 1: Recently hurt by player
            LivingEntity lowestHpHurt = null;
            float lowestHp = Float.MAX_VALUE;
            for (LivingEntity a : allies) {
                if (a.getLastHurtByMob() == player && (gameTime - a.getLastHurtByMobTimestamp()) < 100) {
                    if (a.getHealth() < lowestHp) {
                        lowestHp = a.getHealth();
                        lowestHpHurt = a;
                    }
                }
            }
            if (lowestHpHurt != null) {
                return lowestHpHurt;
            }

            // Tier 2: Player aiming / crosshair focus
            Vec3 playerLook = player.getViewVector(1.0F).normalize();
            LivingEntity aimedAlly = null;
            double highestDot = 0.85D; // Within ~30 degrees of player gaze
            for (LivingEntity a : allies) {
                Vec3 toAlly = a.getEyePosition().subtract(player.getEyePosition()).normalize();
                double dot = playerLook.dot(toAlly);
                if (dot > highestDot) {
                    highestDot = dot;
                    aimedAlly = a;
                }
            }
            if (aimedAlly != null) {
                return aimedAlly;
            }

            // Tier 3: Proximity to player
            LivingEntity nearest = allies.get(0);
            double minAllyDist = Double.MAX_VALUE;
            for (LivingEntity a : allies) {
                double d = a.distanceToSqr(player);
                if (d < minAllyDist) {
                    minAllyDist = d;
                    nearest = a;
                }
            }
            return nearest;
        }

        private void startShieldBash() {
            this.isBashing = true;
            this.bashTicks = 0;
            this.ironclad.setShieldBashing(true);
            this.ironclad.getNavigation().stop();
            this.ironclad.setDeltaMovement(0.0D, this.ironclad.getDeltaMovement().y, 0.0D);
        }
    }
}
