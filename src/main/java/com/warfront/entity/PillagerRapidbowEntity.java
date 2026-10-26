package com.warfront.entity;

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
 * Pillager Rapidbow — stationary mounted repeating crossbow emplacement (Tachanka-style).
 * Deploys a tripod ballista in corridors/chokepoints, unleashing rapid whistling arrow barrages
 * with a constrained +-70 degree traverse arc and winch reload opening.
 */
public class PillagerRapidbowEntity extends AbstractIllager implements GeoEntity {

    public static final byte STATE_MOBILE = 0;
    public static final byte STATE_DEPLOYING = 1;
    public static final byte STATE_BARRAGE = 2;
    public static final byte STATE_WINCH_RELOAD = 3;
    public static final byte STATE_UNDEPLOYING = 4;

    private static final EntityDataAccessor<Byte> COMBAT_STATE =
            SynchedEntityData.defineId(PillagerRapidbowEntity.class, EntityDataSerializers.BYTE);

    private static final EntityDataAccessor<Float> DEPLOYED_YAW =
            SynchedEntityData.defineId(PillagerRapidbowEntity.class, EntityDataSerializers.FLOAT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walking");
    private static final RawAnimation DEPLOY = RawAnimation.begin().thenPlay("deploy");
    private static final RawAnimation BARRAGE = RawAnimation.begin().thenLoop("barrage");
    private static final RawAnimation RELOAD = RawAnimation.begin().thenPlay("reloading");
    private static final RawAnimation UNDEPLOY = RawAnimation.begin().thenPlay("undeploy");

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
        return s == STATE_DEPLOYING || s == STATE_BARRAGE || s == STATE_WINCH_RELOAD || s == STATE_UNDEPLOYING;
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new RapidbowEmplacementGoal(this, 32.0F));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 10.0F));
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
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState attackPredicate(AnimationState<PillagerRapidbowEntity> state) {
        byte s = getCombatState();
        if (s != this.lastCombatState) {
            state.getController().forceAnimationReset();
            this.lastCombatState = s;
        }

        return switch (s) {
            case STATE_DEPLOYING -> state.setAndContinue(DEPLOY);
            case STATE_BARRAGE -> state.setAndContinue(BARRAGE);
            case STATE_WINCH_RELOAD -> state.setAndContinue(RELOAD);
            case STATE_UNDEPLOYING -> state.setAndContinue(UNDEPLOY);
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
        if (isDeployed()) {
            return AbstractIllager.IllagerArmPose.CROSSBOW_HOLD;
        }
        return AbstractIllager.IllagerArmPose.NEUTRAL;
    }

    /**
     * AI Goal coordinating stationary tripod deployment, +-70 degree traverse aiming, rapid volley suppression,
     * and winch recock reload opening.
     */
    static class RapidbowEmplacementGoal extends Goal {
        private final PillagerRapidbowEntity rapidbow;
        private final float maxAttackDist;
        private int phaseTicks = 0;
        private int currentPhase = STATE_MOBILE;
        private int boltsFiredInVolley = 0;

        public RapidbowEmplacementGoal(PillagerRapidbowEntity rapidbow, float maxAttackDist) {
            this.rapidbow = rapidbow;
            this.maxAttackDist = maxAttackDist;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.rapidbow.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public void start() {
            super.start();
            this.currentPhase = STATE_MOBILE;
            this.phaseTicks = 0;
            this.boltsFiredInVolley = 0;
        }

        @Override
        public void stop() {
            super.stop();
            this.currentPhase = STATE_MOBILE;
            this.rapidbow.setCombatState(STATE_MOBILE);
            this.phaseTicks = 0;
            this.boltsFiredInVolley = 0;
        }

        @Override
        public void tick() {
            LivingEntity target = this.rapidbow.getTarget();
            if (target == null || !target.isAlive()) {
                if (this.currentPhase != STATE_MOBILE) {
                    this.currentPhase = STATE_UNDEPLOYING;
                    this.phaseTicks = 0;
                    this.rapidbow.setCombatState(STATE_UNDEPLOYING);
                } else {
                    stop();
                }
                return;
            }

            double distSq = this.rapidbow.distanceToSqr(target);
            boolean canSee = this.rapidbow.getSensing().hasLineOfSight(target);

            phaseTicks++;

            switch (this.currentPhase) {
                case STATE_MOBILE -> {
                    // Mobile navigation: advance towards corridor engagement distance (14-30 blocks)
                    if (distSq <= this.maxAttackDist * this.maxAttackDist && distSq >= 10.0D * 10.0D && canSee) {
                        // In good defensive position: commit to deploy!
                        this.currentPhase = STATE_DEPLOYING;
                        this.phaseTicks = 0;
                        this.rapidbow.setCombatState(STATE_DEPLOYING);
                        this.rapidbow.setDeployedYaw(this.rapidbow.getYRot());
                        this.rapidbow.getNavigation().stop();
                        this.rapidbow.playSound(SoundEvents.CROSSBOW_LOADING_START.value(), 1.0F, 0.8F);
                        this.rapidbow.playSound(SoundEvents.ANVIL_USE, 0.6F, 1.4F);
                    } else {
                        this.rapidbow.getNavigation().moveTo(target, 1.0D);
                    }
                }
                case STATE_DEPLOYING -> {
                    this.rapidbow.getNavigation().stop();

                    // Takes 35 ticks (1.75s) to anchor tripod into stone
                    if (phaseTicks >= 35) {
                        this.currentPhase = STATE_BARRAGE;
                        this.phaseTicks = 0;
                        this.boltsFiredInVolley = 0;
                        this.rapidbow.setCombatState(STATE_BARRAGE);
                    }
                }
                case STATE_BARRAGE -> {
                    this.rapidbow.getNavigation().stop();

                    // Clamp traverse yaw to +-70 degrees from deployed anchor yaw
                    float anchorYaw = this.rapidbow.getDeployedYaw();
                    double dX = target.getX() - this.rapidbow.getX();
                    double dZ = target.getZ() - this.rapidbow.getZ();
                    float targetYaw = (float) (Mth.atan2(dZ, dX) * (180.0D / Math.PI)) - 90.0F;
                    float angleDiff = Mth.wrapDegrees(targetYaw - anchorYaw);

                    // Check if player is outside +-70 degree arc or inside close melee (< 3.5m)
                    if (Math.abs(angleDiff) > 70.0F || distSq < 3.5D * 3.5D) {
                        // Flanked or rushed: must undeploy!
                        this.currentPhase = STATE_UNDEPLOYING;
                        this.phaseTicks = 0;
                        this.rapidbow.setCombatState(STATE_UNDEPLOYING);
                        this.rapidbow.playSound(SoundEvents.ANVIL_STEP, 0.8F, 1.1F);
                        return;
                    }

                    // Face target within traverse arc
                    float clampedYaw = anchorYaw + Math.clamp(angleDiff, -70.0F, 70.0F);
                    this.rapidbow.setYRot(clampedYaw);
                    this.rapidbow.yBodyRot = clampedYaw;
                    this.rapidbow.yHeadRot = clampedYaw;

                    // Volley fire: 8 bolts, 1 bolt every 6 ticks (ticks 0, 6, 12, 18, 24, 30, 36, 42)
                    if (phaseTicks % 6 == 0 && boltsFiredInVolley < 8) {
                        boltsFiredInVolley++;
                        if (!this.rapidbow.level().isClientSide) {
                            Arrow arrow = new Arrow(this.rapidbow.level(), this.rapidbow, new ItemStack(Items.ARROW), null);
                            double dY = target.getY(0.33D) - arrow.getY();
                            double horiz = Math.sqrt(dX * dX + dZ * dZ);
                            arrow.setBaseDamage(3.5D);
                            arrow.shoot(dX, dY + horiz * 0.05D, dZ, 2.8F, 3.0F);
                            this.rapidbow.level().addFreshEntity(arrow);
                            this.rapidbow.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0F, 1.2F + (this.rapidbow.getRandom().nextFloat() * 0.2F));
                        }
                    }

                    // After 8 bolts: enters winch reload
                    if (boltsFiredInVolley >= 8 && phaseTicks >= 48) {
                        this.currentPhase = STATE_WINCH_RELOAD;
                        this.phaseTicks = 0;
                        this.rapidbow.setCombatState(STATE_WINCH_RELOAD);
                        this.rapidbow.playSound(SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), 1.0F, 0.9F);
                    }
                }
                case STATE_WINCH_RELOAD -> {
                    this.rapidbow.getNavigation().stop();

                    // 55 ticks (2.75s) manual winch cranking reload
                    if (phaseTicks == 25) {
                        this.rapidbow.playSound(SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), 1.0F, 1.1F);
                    }
                    if (phaseTicks >= 55) {
                        this.rapidbow.playSound(SoundEvents.CROSSBOW_LOADING_END.value(), 1.0F, 1.0F);

                        // Check if target is still valid and in traverse arc
                        float anchorYaw = this.rapidbow.getDeployedYaw();
                        double dX = target.getX() - this.rapidbow.getX();
                        double dZ = target.getZ() - this.rapidbow.getZ();
                        float targetYaw = (float) (Mth.atan2(dZ, dX) * (180.0D / Math.PI)) - 90.0F;
                        float angleDiff = Mth.wrapDegrees(targetYaw - anchorYaw);

                        if (Math.abs(angleDiff) <= 70.0F && distSq >= 3.5D * 3.5D && distSq <= this.maxAttackDist * this.maxAttackDist && canSee) {
                            this.currentPhase = STATE_BARRAGE;
                            this.phaseTicks = 0;
                            this.boltsFiredInVolley = 0;
                            this.rapidbow.setCombatState(STATE_BARRAGE);
                        } else {
                            this.currentPhase = STATE_UNDEPLOYING;
                            this.phaseTicks = 0;
                            this.rapidbow.setCombatState(STATE_UNDEPLOYING);
                        }
                    }
                }
                case STATE_UNDEPLOYING -> {
                    this.rapidbow.getNavigation().stop();

                    // Takes 30 ticks (1.5s) to pack up ballista onto back
                    if (phaseTicks >= 30) {
                        this.currentPhase = STATE_MOBILE;
                        this.phaseTicks = 0;
                        this.boltsFiredInVolley = 0;
                        this.rapidbow.setCombatState(STATE_MOBILE);
                    }
                }
            }
        }
    }
}
