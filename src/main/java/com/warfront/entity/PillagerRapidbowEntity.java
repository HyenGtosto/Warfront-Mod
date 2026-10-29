package com.warfront.entity;

import net.minecraft.core.Holder;
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
import net.minecraft.world.entity.ai.attributes.Attribute;
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
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
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
 * Pillager Rapidbow — stationary mounted repeating crossbow emplacement (Tachanka-style).
 * Deploys a tripod ballista in corridors/chokepoints, unleashing rapid whistling arrow barrages
 * with a constrained +-40 degree traverse arc, capped 3.5 deg/tick turn rate, and 10-arrow bursts.
 */
public class PillagerRapidbowEntity extends AbstractIllager implements GeoEntity {

    public static final byte STATE_MOBILE = 0;
    public static final byte STATE_DEPLOYING = 1;
    public static final byte STATE_AIMING = 2;
    public static final byte STATE_SHOOTING = 3;
    public static final byte STATE_UNDEPLOYING = 4;
    public static final byte STATE_RELOAD = 5;

    public static final float MAX_TRAVERSE_OFFSET = 40.0F; // +-40 degrees each side
    public static final float MAX_TURN_SPEED = 3.5F;       // 3.5 deg/tick capped turn rate (matching Ironclad)

    private static final EntityDataAccessor<Byte> COMBAT_STATE =
            SynchedEntityData.defineId(PillagerRapidbowEntity.class, EntityDataSerializers.BYTE);

    private static final EntityDataAccessor<Float> DEPLOYED_YAW =
            SynchedEntityData.defineId(PillagerRapidbowEntity.class, EntityDataSerializers.FLOAT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation DEPLOY = RawAnimation.begin().thenPlay("deploy");
    private static final RawAnimation AIMING = RawAnimation.begin().thenLoop("aiming");
    private static final RawAnimation SHOOTING = RawAnimation.begin().thenPlay("shooting");
    private static final RawAnimation RELOAD = RawAnimation.begin().thenPlay("reload");
    private static final RawAnimation PACK_UP = RawAnimation.begin().thenPlay("pack_up");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private byte lastCombatState = STATE_MOBILE;

    public PillagerRapidbowEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 10;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 36.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.22D)
                .add(Attributes.ARMOR, 3.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.10D)
                .add(Attributes.FOLLOW_RANGE, 36.0D);
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                if (isDeployed()) {
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
        builder.define(COMBAT_STATE, STATE_MOBILE);
        builder.define(DEPLOYED_YAW, 0.0F);
    }

    public byte getCombatState() {
        return this.entityData.get(COMBAT_STATE);
    }

    public void setCombatState(byte state) {
        this.entityData.set(COMBAT_STATE, state);
    }

    public float getDeployedYaw() {
        return this.entityData.get(DEPLOYED_YAW);
    }

    public void setDeployedYaw(float yaw) {
        this.entityData.set(DEPLOYED_YAW, yaw);
    }

    public boolean isDeployed() {
        byte s = getCombatState();
        return s == STATE_DEPLOYING || s == STATE_AIMING || s == STATE_SHOOTING || s == STATE_RELOAD || s == STATE_UNDEPLOYING;
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new RapidbowEmplacementGoal(this, 30.0F));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.8D) {
            @Override
            public boolean canUse() {
                return !PillagerRapidbowEntity.this.isDeployed() && PillagerRapidbowEntity.this.getTarget() == null && super.canUse();
            }
        });
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 10.0F) {
            @Override
            public boolean canUse() {
                return !PillagerRapidbowEntity.this.isDeployed() && PillagerRapidbowEntity.this.getTarget() == null && super.canUse();
            }
        });
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this) {
            @Override
            public boolean canUse() {
                return !PillagerRapidbowEntity.this.isDeployed() && PillagerRapidbowEntity.this.getTarget() == null && super.canUse();
            }
        });

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Raider.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Zombie.class, true));
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        controllers.add(new AnimationController<>(this, "action", 2, this::actionPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerRapidbowEntity> state) {
        if (isDeployed()) {
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

    private PlayState actionPredicate(AnimationState<PillagerRapidbowEntity> state) {
        byte s = getCombatState();
        if (s != this.lastCombatState) {
            state.getController().forceAnimationReset();
            this.lastCombatState = s;
        }

        return switch (s) {
            case STATE_DEPLOYING -> state.setAndContinue(DEPLOY);
            case STATE_AIMING -> state.setAndContinue(AIMING);
            case STATE_SHOOTING -> state.setAndContinue(SHOOTING);
            case STATE_RELOAD -> state.setAndContinue(RELOAD);
            case STATE_UNDEPLOYING -> state.setAndContinue(PACK_UP);
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
    public SoundEvent getAmbientSound() {
        return SoundEvents.PILLAGER_AMBIENT;
    }

    @Override
    public SoundEvent getHurtSound(DamageSource damageSource) {
        return SoundEvents.PILLAGER_HURT;
    }

    @Override
    public SoundEvent getDeathSound() {
        return SoundEvents.PILLAGER_DEATH;
    }

    @Override
    public AbstractIllager.IllagerArmPose getArmPose() {
        if (isDeployed()) {
            return AbstractIllager.IllagerArmPose.CROSSBOW_HOLD;
        }
        return AbstractIllager.IllagerArmPose.NEUTRAL;
    }

    @Override
    public void knockback(double strength, double x, double z) {
        if (this.isDeployed()) {
            return; // Absolute knockback immunity: anchored into ground
        }
        super.knockback(strength, x, z);
    }

    @Override
    public void push(Entity entity) {
        if (this.isDeployed()) {
            return; // Immovable when anchored
        }
        super.push(entity);
    }

    @Override
    public void push(double x, double y, double z) {
        if (this.isDeployed()) {
            return; // Immovable when anchored
        }
        super.push(x, y, z);
    }

    @Override
    public double getAttributeValue(Holder<Attribute> attribute) {
        if (attribute.is(Attributes.KNOCKBACK_RESISTANCE) && this.isDeployed()) {
            return 1.0D; // 100% knockback resistance while deployed
        }
        return super.getAttributeValue(attribute);
    }

    /**
     * AI Goal coordinating stationary tripod deployment, +-40 degree traverse aiming,
     * capped 3.5 deg/tick turn rate, multi-target switching, 1.5s grace timer, and 10-arrow volleys.
     */
    static class RapidbowEmplacementGoal extends Goal {
        private final PillagerRapidbowEntity rapidbow;
        private final float maxAttackDist;
        private int phaseTicks = 0;
        private int currentPhase = STATE_MOBILE;
        private int boltsFiredInVolley = 0;
        private int noTargetTicks = 0;

        public RapidbowEmplacementGoal(PillagerRapidbowEntity rapidbow, float maxAttackDist) {
            this.rapidbow = rapidbow;
            this.maxAttackDist = maxAttackDist;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.rapidbow.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            if (this.rapidbow.isDeployed()) {
                return true; // Deployed state machine must finish clean pack_up!
            }
            LivingEntity target = this.rapidbow.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public void start() {
            super.start();
            this.currentPhase = STATE_MOBILE;
            this.phaseTicks = 0;
            this.boltsFiredInVolley = 0;
            this.noTargetTicks = 0;
        }

        @Override
        public void stop() {
            super.stop();
            this.currentPhase = STATE_MOBILE;
            this.rapidbow.setCombatState(STATE_MOBILE);
            this.rapidbow.setSprinting(false);
            this.phaseTicks = 0;
            this.boltsFiredInVolley = 0;
            this.noTargetTicks = 0;
        }

        @Override
        public void tick() {
            LivingEntity target = this.rapidbow.getTarget();

            // 1. Mobile navigation & corridor spacing
            if (this.currentPhase == STATE_MOBILE) {
                if (target == null || !target.isAlive()) {
                    stop();
                    return;
                }

                double distSq = this.rapidbow.distanceToSqr(target);
                boolean canSee = this.rapidbow.getSensing().hasLineOfSight(target);

                // If too close while mobile (< 8.0m), sprint back to gain spacing
                if (distSq < 8.0D * 8.0D) {
                    this.rapidbow.setSprinting(true);
                    double diffX = this.rapidbow.getX() - target.getX();
                    double diffZ = this.rapidbow.getZ() - target.getZ();
                    double len = Math.sqrt(diffX * diffX + diffZ * diffZ);
                    if (len > 0.001D) {
                        diffX /= len;
                        diffZ /= len;
                    }
                    this.rapidbow.getNavigation().moveTo(this.rapidbow.getX() + diffX * 10.0D, this.rapidbow.getY(), this.rapidbow.getZ() + diffZ * 10.0D, 1.35D);
                    return;
                }

                // If in good corridor engagement range (8.0m - 30m) with line of sight: deploy!
                if (distSq >= 8.0D * 8.0D && distSq <= this.maxAttackDist * this.maxAttackDist && canSee) {
                    this.rapidbow.setSprinting(false);
                    this.rapidbow.getNavigation().stop();

                    double dX = target.getX() - this.rapidbow.getX();
                    double dZ = target.getZ() - this.rapidbow.getZ();
                    float targetFacingYaw = (float) (Mth.atan2(dZ, dX) * (180.0D / Math.PI)) - 90.0F;

                    this.rapidbow.setYRot(targetFacingYaw);
                    this.rapidbow.yBodyRot = targetFacingYaw;
                    this.rapidbow.yHeadRot = targetFacingYaw;
                    this.rapidbow.setDeployedYaw(targetFacingYaw);

                    this.currentPhase = STATE_DEPLOYING;
                    this.phaseTicks = 0;
                    this.noTargetTicks = 0;
                    this.rapidbow.setCombatState(STATE_DEPLOYING);
                    this.rapidbow.playSound(SoundEvents.CROSSBOW_LOADING_START.value(), 1.0F, 0.8F);
                    this.rapidbow.playSound(SoundEvents.ANVIL_USE, 0.6F, 1.4F);
                } else {
                    // Advance toward target until within corridor engagement distance
                    this.rapidbow.setSprinting(false);
                    this.rapidbow.getNavigation().moveTo(target, 1.0D);
                }
                return;
            }

            // 2. While deploying (43 ticks / 2.16s)
            if (this.currentPhase == STATE_DEPLOYING) {
                this.rapidbow.getNavigation().stop();
                phaseTicks++;
                if (phaseTicks >= 43) {
                    this.currentPhase = STATE_AIMING;
                    this.phaseTicks = 0;
                    this.noTargetTicks = 0;
                    this.rapidbow.setCombatState(STATE_AIMING);
                }
                return;
            }

            // 3. While undeploying (packing up for 43 ticks / 2.16s)
            if (this.currentPhase == STATE_UNDEPLOYING) {
                this.rapidbow.getNavigation().stop();
                phaseTicks++;
                if (phaseTicks >= 43) {
                    this.currentPhase = STATE_MOBILE;
                    this.phaseTicks = 0;
                    this.boltsFiredInVolley = 0;
                    this.noTargetTicks = 0;
                    this.rapidbow.setCombatState(STATE_MOBILE);

                    // Post-packup running tactical spacing: sprint away from close hostiles (< 8m)
                    LivingEntity t = this.rapidbow.getTarget();
                    if (t != null && t.isAlive() && this.rapidbow.distanceToSqr(t) < 64.0D) {
                        this.rapidbow.setSprinting(true);
                        double diffX = this.rapidbow.getX() - t.getX();
                        double diffZ = this.rapidbow.getZ() - t.getZ();
                        double len = Math.sqrt(diffX * diffX + diffZ * diffZ);
                        if (len > 0.001D) {
                            diffX /= len;
                            diffZ /= len;
                        }
                        this.rapidbow.getNavigation().moveTo(this.rapidbow.getX() + diffX * 12.0D, this.rapidbow.getY(), this.rapidbow.getZ() + diffZ * 12.0D, 1.35D);
                    }
                }
                return;
            }

            // 4. Deployed combat states (AIMING, SHOOTING, RELOAD)
            phaseTicks++;
            this.rapidbow.getNavigation().stop();

            // Evaluate if current target is valid inside active +-40 degree traverse arc
            boolean targetValidInArc = false;
            float deployedYaw = this.rapidbow.getDeployedYaw();
            float targetOffset = 0.0F;

            if (target != null && target.isAlive() && this.rapidbow.getSensing().hasLineOfSight(target)) {
                double distSq = this.rapidbow.distanceToSqr(target);
                if (distSq <= this.maxAttackDist * this.maxAttackDist) {
                    double dX = target.getX() - this.rapidbow.getX();
                    double dZ = target.getZ() - this.rapidbow.getZ();
                    float targetFacingYaw = (float) (Mth.atan2(dZ, dX) * (180.0D / Math.PI)) - 90.0F;
                    targetOffset = Mth.wrapDegrees(targetFacingYaw - deployedYaw);
                    if (Math.abs(targetOffset) <= MAX_TRAVERSE_OFFSET) {
                        targetValidInArc = true;
                    }
                }
            }

            // Target switching: if current target is lost, dead, rushed, or flanked, search in arc
            if (!targetValidInArc) {
                LivingEntity alternate = findBestTargetInArc();
                if (alternate != null) {
                    this.rapidbow.setTarget(alternate);
                    target = alternate;
                    double dX = target.getX() - this.rapidbow.getX();
                    double dZ = target.getZ() - this.rapidbow.getZ();
                    float targetFacingYaw = (float) (Mth.atan2(dZ, dX) * (180.0D / Math.PI)) - 90.0F;
                    targetOffset = Mth.wrapDegrees(targetFacingYaw - deployedYaw);
                    targetValidInArc = true;
                    noTargetTicks = 0;
                } else {
                    // If target is dead or null (and no alternate in arc), immediately pack up
                    if (target == null || !target.isAlive()) {
                        this.currentPhase = STATE_UNDEPLOYING;
                        this.phaseTicks = 0;
                        this.boltsFiredInVolley = 0;
                        this.rapidbow.setCombatState(STATE_UNDEPLOYING);
                        this.rapidbow.playSound(SoundEvents.ANVIL_STEP, 0.8F, 1.1F);
                        return;
                    }

                    // Otherwise target is alive but out of FOV or behind cover: count 1.5-second (30-tick) grace timer
                    noTargetTicks++;
                    if (noTargetTicks >= 30) {
                        this.currentPhase = STATE_UNDEPLOYING;
                        this.phaseTicks = 0;
                        this.boltsFiredInVolley = 0;
                        this.rapidbow.setCombatState(STATE_UNDEPLOYING);
                        this.rapidbow.playSound(SoundEvents.ANVIL_STEP, 0.8F, 1.1F);
                        return;
                    }
                }
            } else {
                noTargetTicks = 0;
            }

            // Smooth 3.5 deg/tick tracking toward the active target in arc
            if (targetValidInArc) {
                float clampedTargetYaw = deployedYaw + Mth.clamp(targetOffset, -MAX_TRAVERSE_OFFSET, MAX_TRAVERSE_OFFSET);
                float currentYaw = this.rapidbow.getYRot();
                float yawDiff = Mth.wrapDegrees(clampedTargetYaw - currentYaw);
                float turn = Mth.clamp(yawDiff, -MAX_TURN_SPEED, MAX_TURN_SPEED);
                float newYaw = currentYaw + turn;

                this.rapidbow.setYRot(newYaw);
                this.rapidbow.yBodyRot = newYaw;
                this.rapidbow.yHeadRot = newYaw;
            }

            switch (this.currentPhase) {
                case STATE_AIMING -> {
                    // When target is in FOV and aligned (within 6 degrees) or after 15 ticks of aiming: shoot!
                    if (targetValidInArc) {
                        float currentYaw = this.rapidbow.getYRot();
                        float clampedTargetYaw = deployedYaw + Mth.clamp(targetOffset, -MAX_TRAVERSE_OFFSET, MAX_TRAVERSE_OFFSET);
                        float yawDiff = Mth.wrapDegrees(clampedTargetYaw - currentYaw);

                        if (Math.abs(yawDiff) <= 6.0F || phaseTicks >= 15) {
                            this.currentPhase = STATE_SHOOTING;
                            this.phaseTicks = 0;
                            this.boltsFiredInVolley = 0;
                            this.rapidbow.setCombatState(STATE_SHOOTING);
                        }
                    }
                }
                case STATE_SHOOTING -> {
                    // Fire 10 bolts over 60 ticks: 1 bolt every 6 ticks (ticks 0, 6, 12, 18, 24, 30, 36, 42, 48, 54)
                    if (phaseTicks % 6 == 0 && boltsFiredInVolley < 10) {
                        boltsFiredInVolley++;
                        if (!this.rapidbow.level().isClientSide && target != null) {
                            double dX = target.getX() - this.rapidbow.getX();
                            double dZ = target.getZ() - this.rapidbow.getZ();
                            double horiz = Math.sqrt(dX * dX + dZ * dZ);

                            double dirX = horiz > 0.001D ? (dX / horiz) : 0.0D;
                            double dirZ = horiz > 0.001D ? (dZ / horiz) : 0.0D;

                            // Spawn 2 blocks forward along the firing vector to prevent self-collision
                            double spawnX = this.rapidbow.getX() + dirX * 2.0D;
                            double spawnY = this.rapidbow.getEyeY() - 0.1D;
                            double spawnZ = this.rapidbow.getZ() + dirZ * 2.0D;

                            Arrow arrow = new Arrow(this.rapidbow.level(), this.rapidbow, new ItemStack(Items.ARROW), null) {
                                @Override
                                protected boolean canHitEntity(Entity targetEntity) {
                                    if (targetEntity instanceof PillagerIroncladEntity && AlliedFactionHelper.isAllied(this.getOwner(), targetEntity)) {
                                        return false;
                                    }
                                    return super.canHitEntity(targetEntity);
                                }
                            };
                            arrow.setPos(spawnX, spawnY, spawnZ);

                            double aimX = target.getX() - spawnX;
                            double aimY = target.getY(0.33D) - spawnY;
                            double aimZ = target.getZ() - spawnZ;

                            arrow.setBaseDamage(3.5D);
                            arrow.shoot(aimX, aimY + horiz * 0.05D, aimZ, 2.8F, 2.5F);
                            this.rapidbow.level().addFreshEntity(arrow);
                            this.rapidbow.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0F, 1.2F + (this.rapidbow.getRandom().nextFloat() * 0.2F));
                        }
                    }

                    // After 60 ticks (all 10 bolts fired): evaluate tactical retreat vs reload
                    if (phaseTicks >= 60 && boltsFiredInVolley >= 10) {
                        double distSq = (target != null && target.isAlive()) ? this.rapidbow.distanceToSqr(target) : Double.MAX_VALUE;
                        if (distSq < 3.0D * 3.0D) {
                            // Target is closer than 3 blocks: retreat instead of reload!
                            this.currentPhase = STATE_UNDEPLOYING;
                            this.phaseTicks = 0;
                            this.boltsFiredInVolley = 0;
                            this.rapidbow.setCombatState(STATE_UNDEPLOYING);
                            this.rapidbow.playSound(SoundEvents.ANVIL_STEP, 0.8F, 1.1F);
                        } else {
                            // Target is at safe distance (>= 3 blocks): reload
                            this.currentPhase = STATE_RELOAD;
                            this.phaseTicks = 0;
                            this.boltsFiredInVolley = 0;
                            this.rapidbow.setCombatState(STATE_RELOAD);
                            this.rapidbow.playSound(SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), 1.0F, 0.9F);
                        }
                    }
                }
                case STATE_RELOAD -> {
                    // Reload takes 67 ticks (3.33s)
                    if (phaseTicks >= 67) {
                        if (targetValidInArc) {
                            this.currentPhase = STATE_AIMING;
                            this.phaseTicks = 0;
                            this.boltsFiredInVolley = 0;
                            this.rapidbow.setCombatState(STATE_AIMING);
                        }
                    }
                }
            }
        }

        /**
         * Scans for the nearest living hostile within the deployed +-40 degree traverse arc.
         */
        private LivingEntity findBestTargetInArc() {
            float deployedYaw = this.rapidbow.getDeployedYaw();
            AABB searchBox = this.rapidbow.getBoundingBox().inflate(30.0D);
            List<LivingEntity> candidates = this.rapidbow.level().getEntitiesOfClass(
                    LivingEntity.class, searchBox,
                    e -> e.isAlive()
                            && !AlliedFactionHelper.isAllied(this.rapidbow, e)
                            && this.rapidbow.canAttack(e)
                            && this.rapidbow.getSensing().hasLineOfSight(e)
            );

            LivingEntity best = null;
            double bestDistSq = Double.MAX_VALUE;

            for (LivingEntity c : candidates) {
                double dX = c.getX() - this.rapidbow.getX();
                double dZ = c.getZ() - this.rapidbow.getZ();
                double dSq = dX * dX + dZ * dZ;
                if (dSq > 30.0D * 30.0D) continue;

                float facingYaw = (float) (Mth.atan2(dZ, dX) * (180.0D / Math.PI)) - 90.0F;
                float offset = Mth.wrapDegrees(facingYaw - deployedYaw);
                if (Math.abs(offset) <= MAX_TRAVERSE_OFFSET) {
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        best = c;
                    }
                }
            }
            return best;
        }
    }
}
