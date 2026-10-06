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
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * Pillager Marksman — specialized crossbow sniper who attacks with arrows from long range.
 */
public class PillagerMarksmanEntity extends AbstractIllager implements GeoEntity, RangedAttackMob {

    public static final byte STATE_IDLE = 0;
    public static final byte STATE_AIMING = 1;
    public static final byte STATE_SHOOTING = 2;
    public static final byte STATE_RELOADING = 3;

    private static final EntityDataAccessor<Byte> COMBAT_STATE =
            SynchedEntityData.defineId(PillagerMarksmanEntity.class, EntityDataSerializers.BYTE);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walking");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("running");
    private static final RawAnimation AIMING = RawAnimation.begin().thenLoop("aiming");
    private static final RawAnimation SHOOTING = RawAnimation.begin().thenPlay("shooting");
    private static final RawAnimation RELOADING = RawAnimation.begin().thenPlay("reloading");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private byte lastCombatState = STATE_IDLE;

    public PillagerMarksmanEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 8;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 26.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
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
        this.goalSelector.addGoal(2, new MarksmanAttackGoal(this, 1.0D, 18.0F));
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
        if (isMarksmanInLineOfSight(target)) {
            return;
        }
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

    /**
     * Hold Fire check: verifies if another allied Pillager Marksman is directly in this entity's line of fire.
     * Prevents marksmen from shooting fellow marksmen in the back.
     * Note: Only checks fellow Pillager Marksmen — other enemies are ignored, keeping the "don't care about friendly fire" attitude.
     */
    public boolean isMarksmanInLineOfSight(LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }

        Vec3 start = this.getEyePosition();
        Vec3 end = new Vec3(target.getX(), target.getY(0.3333333333333333D), target.getZ());
        Vec3 rayVec = end.subtract(start);
        double distTarget = rayVec.length();
        if (distTarget < 0.2D) {
            return false;
        }

        Vec3 rayDir = rayVec.normalize();
        AABB corridorBox = this.getBoundingBox().expandTowards(rayVec).inflate(1.5D);

        List<PillagerMarksmanEntity> marksmen = this.level().getEntitiesOfClass(
                PillagerMarksmanEntity.class,
                corridorBox,
                other -> other != this && other.isAlive()
        );

        for (PillagerMarksmanEntity other : marksmen) {
            Vec3 toOther = other.getBoundingBox().getCenter().subtract(start);
            double projection = toOther.dot(rayDir);

            // Marksman must be in front of shooter and before the target
            if (projection <= 0.2D || projection >= distTarget - 0.4D) {
                continue;
            }

            // Check if arrow line segment intersects other marksman's bounding box (inflated for safety margin)
            AABB inflatedBox = other.getBoundingBox().inflate(0.35D, 0.25D, 0.35D);
            if (inflatedBox.clip(start, end).isPresent() || inflatedBox.contains(start)) {
                return true;
            }

            // Also check perpendicular distance to the ray for safety corridor clearance
            Vec3 closestOnRay = start.add(rayDir.scale(projection));
            double perpDistSq = other.getBoundingBox().getCenter().distanceToSqr(closestOnRay);
            if (perpDistSq < 0.65D * 0.65D) {
                return true;
            }
        }

        return false;
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
        boolean isMoving = state.isMoving()
                || this.walkAnimation.isMoving()
                || this.walkAnimation.speed() > 0.001F
                || state.getLimbSwingAmount() > 0.001F
                || (this.getDeltaMovement().horizontalDistanceSqr() > 0.00005D)
                || (this.getX() != this.xo || this.getZ() != this.zo);
        if (isMoving) {
            if (this.isSprinting() || (this.isAggressive() && getCombatState() == STATE_IDLE) || (this.getTarget() != null && this.getTarget().isAlive() && getCombatState() == STATE_IDLE)) {
                return state.setAndContinue(RUN);
            }
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState attackPredicate(AnimationState<PillagerMarksmanEntity> state) {
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
     * AI Goal coordinating sniper spacing (firing squad formation), aiming, arrow release, hold fire, and reloading.
     */
    static class MarksmanAttackGoal extends Goal {
        private final PillagerMarksmanEntity mob;
        private final double speedModifier;
        private final float attackRadius;
        private int attackPhaseTicks = 0;
        private int currentPhase = STATE_IDLE;
        private int seeTime = 0;
        private int repositionCooldown = 0;

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
            if (target == null) return;

            double distSq = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
            boolean canSee = this.mob.getSensing().hasLineOfSight(target);

            if (canSee) {
                this.seeTime++;
            } else {
                this.seeTime = 0;
            }

            this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

            if (this.repositionCooldown > 0) {
                this.repositionCooldown--;
            }

            // Apply tactical spacing: spread out in a line 90 degrees towards target like a firing squad
            applyFiringSquadSpacing(target, distSq, canSee);

            this.attackPhaseTicks++;

            switch (this.currentPhase) {
                case STATE_AIMING -> {
                    this.mob.setCombatState(STATE_AIMING);
                    boolean blockedByMarksman = this.mob.isMarksmanInLineOfSight(target);

                    if (this.attackPhaseTicks >= 20 && canSee) {
                        if (blockedByMarksman) {
                            // Hold fire! Keep crossbow aimed and ready, waiting for a clear line of sight
                            this.attackPhaseTicks = 20;
                            // Prompt immediate spacing/sidestepping to clear the firing lane
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
                    // Firing arrow on tick 1 synchronized with crossbow release and recoil jerk
                    if (this.attackPhaseTicks == 1) {
                        if (this.mob.isMarksmanInLineOfSight(target)) {
                            // Another marksman crossed the firing lane: hold fire and reset to aiming
                            this.currentPhase = STATE_AIMING;
                            this.attackPhaseTicks = 20;
                            this.mob.setCombatState(STATE_AIMING);
                            this.repositionCooldown = 0;
                        } else {
                            float distFactor = (float) Math.sqrt(distSq) / this.attackRadius;
                            this.mob.performRangedAttack(target, Math.clamp(distFactor, 0.1F, 1.0F));
                        }
                    }
                    // Full shooting animation length (0.5s = 10 ticks)
                    if (this.attackPhaseTicks >= 10) {
                        this.currentPhase = STATE_RELOADING;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_RELOADING);
                    }
                }
                case STATE_RELOADING -> {
                    this.mob.setCombatState(STATE_RELOADING);
                    // Audio cues synchronized with 1.6s reloading stages
                    if (this.attackPhaseTicks == 1) {
                        this.mob.playSound(SoundEvents.CROSSBOW_LOADING_START.value(), 1.0F, 1.0F);
                    } else if (this.attackPhaseTicks == 16) {
                        this.mob.playSound(SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), 1.0F, 1.0F);
                    } else if (this.attackPhaseTicks == 28) {
                        this.mob.playSound(SoundEvents.CROSSBOW_LOADING_END.value(), 1.0F, 1.0F);
                    }

                    // Full reloading animation length (1.6s = 32 ticks)
                    if (this.attackPhaseTicks >= 32) {
                        this.currentPhase = STATE_AIMING;
                        this.attackPhaseTicks = 0;
                        this.mob.setCombatState(STATE_AIMING);
                    }
                }
            }
        }

        /**
         * Spacing function: forces marksmen to spread out in a line 90 degrees towards the target
         * like a disciplined firing squad. Prevents bunching up and clears firing lanes.
         */
        private void applyFiringSquadSpacing(LivingEntity target, double distSq, boolean canSee) {
            // Emergency retreat if target gets into melee range (< 6 blocks)
            if (distSq < 36.0D) {
                double backX = this.mob.getX() - (target.getX() - this.mob.getX());
                double backZ = this.mob.getZ() - (target.getZ() - this.mob.getZ());
                this.mob.getNavigation().moveTo(backX, this.mob.getY(), backZ, this.speedModifier * 1.15D);
                return;
            }

            double dX = target.getX() - this.mob.getX();
            double dZ = target.getZ() - this.mob.getZ();
            double horizontalDist = Math.sqrt(dX * dX + dZ * dZ);
            if (horizontalDist < 0.001D) return;

            // Unit vector towards target (forward axis)
            double fX = dX / horizontalDist;
            double fZ = dZ / horizontalDist;

            // Unit vector perpendicular to target line (90 degrees - lateral firing squad axis)
            double pX = -fZ;
            double pZ = fX;

            // Find fellow marksmen in vicinity to maintain formation
            List<PillagerMarksmanEntity> nearbyMarksmen = this.mob.level().getEntitiesOfClass(
                    PillagerMarksmanEntity.class,
                    this.mob.getBoundingBox().inflate(14.0D, 6.0D, 14.0D),
                    other -> other != this.mob && other.isAlive()
            );

            if (nearbyMarksmen.isEmpty()) {
                // Solo marksman behavior
                if (distSq > (double) (this.attackRadius * this.attackRadius) || !canSee) {
                    this.mob.getNavigation().moveTo(target, this.speedModifier);
                } else {
                    this.mob.getNavigation().stop();
                }
                return;
            }

            // Multiple marksmen: spread out in a line 90 degrees towards target
            final double DESIRED_SPACING = 3.5D; // blocks between adjacent marksmen in firing line
            double lateralPush = 0.0D;
            boolean blockedByMarksman = this.mob.isMarksmanInLineOfSight(target);

            for (PillagerMarksmanEntity other : nearbyMarksmen) {
                double relX = this.mob.getX() - other.getX();
                double relZ = this.mob.getZ() - other.getZ();
                double sepDist = Math.sqrt(relX * relX + relZ * relZ);
                if (sepDist > 14.0D || sepDist < 0.0001D) continue;

                // Project separation onto lateral 90-degree line and forward line
                double latSep = relX * pX + relZ * pZ;
                double longSep = relX * fX + relZ * fZ;

                if (Math.abs(latSep) < DESIRED_SPACING) {
                    double overlap = DESIRED_SPACING - Math.abs(latSep);
                    double dir;
                    if (latSep > 0.08D) {
                        dir = 1.0D;
                    } else if (latSep < -0.08D) {
                        dir = -1.0D;
                    } else {
                        // Directly aligned in front/behind: break tie using entity IDs
                        dir = (this.mob.getId() > other.getId()) ? 1.0D : -1.0D;
                    }

                    double weight = Math.max(0.3D, 1.0D - (sepDist / 14.0D));
                    lateralPush += dir * overlap * weight;

                    // Extra urgency if the other marksman is in front and blocking line of fire
                    if (longSep < 0.0D && Math.abs(latSep) < 1.4D) {
                        lateralPush += dir * 2.2D * (1.4D - Math.abs(latSep));
                    }
                }
            }

            // Longitudinal combat positioning: align along optimal firing distance (~13.5 blocks)
            double forwardMove = 0.0D;
            if (horizontalDist > 16.0D || !canSee) {
                forwardMove = Math.min(horizontalDist - 13.5D, 3.5D);
            } else if (horizontalDist < 9.0D) {
                forwardMove = -(9.0D - horizontalDist);
            }

            // Clamp lateral displacement per step
            lateralPush = Math.clamp(lateralPush, -6.0D, 6.0D);

            double deltaX = lateralPush * pX + forwardMove * fX;
            double deltaZ = lateralPush * pZ + forwardMove * fZ;
            double moveDist = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

            if (moveDist < 0.45D && canSee && !blockedByMarksman) {
                // In position with clear line of sight: hold firing stance
                this.mob.getNavigation().stop();
            } else {
                // Move into firing squad formation or clear blocked lane
                if (this.repositionCooldown <= 0 || this.mob.getNavigation().isDone() || blockedByMarksman) {
                    double destX = this.mob.getX() + deltaX;
                    double destZ = this.mob.getZ() + deltaZ;
                    this.mob.getNavigation().moveTo(destX, this.mob.getY(), destZ, this.speedModifier);
                    this.repositionCooldown = 8 + this.mob.getRandom().nextInt(5);
                }
            }
        }
    }
}
