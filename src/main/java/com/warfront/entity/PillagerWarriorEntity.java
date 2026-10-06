package com.warfront.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
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
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;

/**
 * Pillager Warrior — front-line shocktrooper equipped with a heavy war axe and a protective shield.
 *
 * Multi-Part Physical Shield Mechanics:
 *   - The shield possesses its own physical NeoForge PartEntity sub-hitbox (0.65 x 0.85).
 *   - When passively lowered, the shield hitbox stays strapped along the left forearm.
 *   - When actively raised, the shield hitbox covers the torso/chest in front of the warrior.
 *   - Direct hits to the shield hitbox block 100% of damage and deflect projectiles.
 *   - Headshots (Y > 1.50) and leg shots (Y < 0.65) bypass the shield and inflict full damage!
 *   - Striking the shield with an axe disables the guard for 10.0 seconds (200 ticks).
 */
public class PillagerWarriorEntity extends AbstractIllager implements GeoEntity {

    public static final byte ACTION_NONE = 0;
    public static final byte ACTION_ATTACK = 1;
    public static final byte ACTION_SHIELD_RAISE = 2;
    public static final byte ACTION_SHIELD_HOLD = 3;
    public static final byte ACTION_SHIELD_LOWER = 4;

    private static final EntityDataAccessor<Byte> ACTION_STATE =
            SynchedEntityData.defineId(PillagerWarriorEntity.class, EntityDataSerializers.BYTE);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walking");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("running");
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("attack");
    private static final RawAnimation SHIELD_RAISE = RawAnimation.begin().thenPlay("shield_raise");
    private static final RawAnimation SHIELD_HOLD = RawAnimation.begin().thenLoop("shield_hold");
    private static final RawAnimation SHIELD_LOWER = RawAnimation.begin().thenPlay("shield_lower");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final PillagerWarriorShieldPart shieldPart;
    private final PartEntity<?>[] subEntities;

    private int actionTicks = 0;
    private int shieldDurationTicks = 0;
    private int shieldDisableTicks = 0;
    private int attackCooldownTicks = 0;
    private LivingEntity pendingAttackTarget = null;
    private int attackImpactTicks = -1;
    private byte lastActionState = ACTION_NONE;

    public PillagerWarriorEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 8;
        this.shieldPart = new PillagerWarriorShieldPart(this, 0.65F, 0.85F);
        this.subEntities = new PartEntity<?>[]{this.shieldPart};
        this.setId(ENTITY_COUNTER.getAndAdd(this.subEntities.length + 1) + 1);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 32.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.30D)
                .add(Attributes.ATTACK_DAMAGE, 8.0D)
                .add(Attributes.ARMOR, 6.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.20D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public PartEntity<?>[] getParts() {
        return this.subEntities;
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        if (this.subEntities != null) {
            for (int i = 0; i < this.subEntities.length; i++) {
                this.subEntities[i].setId(id + i + 1);
            }
        }
    }

    public PillagerWarriorShieldPart getShieldPart() {
        return this.shieldPart;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ACTION_STATE, ACTION_NONE);
    }

    public byte getActionState() {
        return this.entityData.get(ACTION_STATE);
    }

    public void setActionState(byte state, int durationTicks) {
        this.entityData.set(ACTION_STATE, state);
        this.actionTicks = durationTicks;
    }

    public boolean isShieldRaised() {
        byte state = getActionState();
        return state == ACTION_SHIELD_RAISE || state == ACTION_SHIELD_HOLD;
    }

    public boolean isAttacking() {
        return getActionState() == ACTION_ATTACK || this.actionTicks > 0 || this.attackImpactTicks > 0;
    }

    public void raiseShield(int durationTicks) {
        if (this.level().isClientSide || this.shieldDisableTicks > 0) return;
        byte state = getActionState();
        if (state == ACTION_SHIELD_HOLD) {
            this.shieldDurationTicks = Math.max(this.shieldDurationTicks, durationTicks);
            return;
        }
        if (state == ACTION_SHIELD_RAISE) {
            this.shieldDurationTicks = Math.max(this.shieldDurationTicks, durationTicks);
            return;
        }

        this.attackImpactTicks = -1;
        this.pendingAttackTarget = null;
        this.shieldDurationTicks = durationTicks;
        setActionState(ACTION_SHIELD_RAISE, 8);
        this.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.2F);
    }

    public void lowerShield() {
        if (this.level().isClientSide) return;
        byte state = getActionState();
        if (state == ACTION_SHIELD_HOLD || state == ACTION_SHIELD_RAISE) {
            this.shieldDurationTicks = 0;
            setActionState(ACTION_SHIELD_LOWER, 8);
        }
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new WarriorShieldDefenseGoal(this));
        this.goalSelector.addGoal(2, new Raider.HoldGroundAttackGoal(this, 10.0F));
        this.goalSelector.addGoal(3, new WarriorMeleeAttackGoal(this, 1.25D));
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

        // 1. Synchronize physical shield PartEntity sub-hitbox positioning on both client and server
        Vec3[] oldPos = new Vec3[this.subEntities.length];
        for (int j = 0; j < this.subEntities.length; j++) {
            oldPos[j] = new Vec3(this.subEntities[j].getX(), this.subEntities[j].getY(), this.subEntities[j].getZ());
        }

        float bodyRot = this.yBodyRot * (float) (Math.PI / 180.0);
        double sinBody = Math.sin(bodyRot);
        double cosBody = Math.cos(bodyRot);
        double fwdX = -sinBody;
        double fwdZ = cosBody;
        double leftX = cosBody;
        double leftZ = sinBody;

        if (isShieldRaised()) {
            // Actively raised: held in front of torso covering chest/abdomen
            // (Y: 0.65 to 1.50 — leaves head > 1.50 and legs < 0.65 exposed)
            double offsetX = fwdX * 0.42D + leftX * 0.12D;
            double offsetY = 0.65D;
            double offsetZ = fwdZ * 0.42D + leftZ * 0.12D;
            tickPart(this.shieldPart, offsetX, offsetY, offsetZ);
        } else {
            // Passively lowered: strapped along the left forearm
            // (Y: 0.45 to 1.30 — covers left flank)
            double offsetX = leftX * 0.40D + fwdX * 0.10D;
            double offsetY = 0.45D;
            double offsetZ = leftZ * 0.40D + fwdZ * 0.10D;
            tickPart(this.shieldPart, offsetX, offsetY, offsetZ);
        }

        for (int l = 0; l < this.subEntities.length; l++) {
            this.subEntities[l].xo = oldPos[l].x;
            this.subEntities[l].yo = oldPos[l].y;
            this.subEntities[l].zo = oldPos[l].z;
            this.subEntities[l].xOld = oldPos[l].x;
            this.subEntities[l].yOld = oldPos[l].y;
            this.subEntities[l].zOld = oldPos[l].z;
        }

        // 2. Server-side AI logic
        if (!this.level().isClientSide) {
            LivingEntity target = this.getTarget();
            boolean hasTarget = target != null && target.isAlive();
            this.setAggressive(hasTarget);
            this.setSprinting(hasTarget && (this.getDeltaMovement().horizontalDistanceSqr() > 0.005D));

            if (this.shieldDisableTicks > 0) {
                this.shieldDisableTicks--;
            }

            if (this.attackCooldownTicks > 0) {
                this.attackCooldownTicks--;
            }

            // Periodic threat evaluation: check for nearby players aiming with ranged weapons
            if (this.tickCount % 4 == 0 && !isShieldRaised() && this.shieldDisableTicks <= 0) {
                checkForRangedThreats();
            }

            // Action duration countdown & state transition
            if (this.actionTicks > 0) {
                this.actionTicks--;
                if (this.actionTicks <= 0) {
                    byte state = getActionState();
                    if (state == ACTION_SHIELD_RAISE) {
                        setActionState(ACTION_SHIELD_HOLD, this.shieldDurationTicks);
                    } else if (state == ACTION_SHIELD_LOWER || state == ACTION_ATTACK) {
                        setActionState(ACTION_NONE, 0);
                    }
                }
            }

            // Active shield hold duration countdown
            if (getActionState() == ACTION_SHIELD_HOLD) {
                if (this.shieldDurationTicks > 0) {
                    this.shieldDurationTicks--;
                    if (this.shieldDurationTicks <= 0) {
                        setActionState(ACTION_SHIELD_LOWER, 8);
                    }
                }
            }

            // Damage impact frame calculation (contact frame at 9 ticks)
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
                            this.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.2F, 0.85F);
                        }
                    }
                    this.pendingAttackTarget = null;
                }
            }
        }
    }

    private void tickPart(PartEntity<?> part, double offsetX, double offsetY, double offsetZ) {
        part.setPos(this.getX() + offsetX, this.getY() + offsetY, this.getZ() + offsetZ);
    }

    private void checkForRangedThreats() {
        // Detect nearby players actively aiming or using a ranged weapon
        List<Player> players = this.level().getEntitiesOfClass(
                Player.class,
                this.getBoundingBox().inflate(22.0D),
                p -> p.isAlive() && !p.isCreative() && !p.isSpectator()
        );
        for (Player player : players) {
            boolean isUsingBow = player.isUsingItem() && (player.getUseItem().getItem() instanceof BowItem || player.getUseItem().getItem() instanceof CrossbowItem);
            boolean hasChargedCrossbow = CrossbowItem.isCharged(player.getMainHandItem()) || CrossbowItem.isCharged(player.getOffhandItem());
            if (isUsingBow || hasChargedCrossbow) {
                Vec3 playerLook = player.getViewVector(1.0F);
                Vec3 toWarrior = this.getEyePosition().subtract(player.getEyePosition());
                if (toWarrior.lengthSqr() > 0.01D && playerLook.dot(toWarrior.normalize()) > 0.65D) {
                    raiseShield(80);
                    if (this.getTarget() == null) this.setTarget(player);
                    this.getLookControl().setLookAt(player, 45.0F, 45.0F);
                    return;
                }
            }
        }
    }

    /**
     * Handles hits that land on the physical shield PartEntity sub-hitbox.
     */
    public boolean hurtShield(PillagerWarriorShieldPart part, DamageSource source, float amount) {
        // If shield guard is currently disabled by an axe, strikes penetrate and damage the warrior directly
        if (this.shieldDisableTicks > 0) {
            return super.hurt(source, amount);
        }

        // Bypassing damage sources (void, fire, drowning, magic, starvation, etc.)
        if (source.is(DamageTypeTags.BYPASSES_SHIELD)
                || source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypeTags.IS_FIRE)
                || source.is(DamageTypeTags.IS_DROWNING)
                || source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.STARVE)) {
            return super.hurt(source, amount);
        }

        // 1. Play shield block sound with realistic pitch variance
        this.playSound(SoundEvents.SHIELD_BLOCK, 1.0F, 0.9F + this.getRandom().nextFloat() * 0.2F);

        // 2. Spawn spark particles at shield contact position
        if (this.level() instanceof ServerLevel serverLevel) {
            Vec3 partPos = part.position().add(0, part.getBbHeight() * 0.5D, 0);
            serverLevel.sendParticles(ParticleTypes.CRIT, partPos.x, partPos.y, partPos.z, 8, 0.12D, 0.12D, 0.12D, 0.15D);
            serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK, partPos.x, partPos.y, partPos.z, 5, 0.08D, 0.08D, 0.08D, 0.05D);
        }

        // 3. Delete projectile on impact instead of deflecting
        if (source.getDirectEntity() instanceof Projectile projectile) {
            projectile.discard();
        }

        // 4. Axe attack disables shield for 10.0 seconds (200 ticks)
        if (source.getEntity() instanceof LivingEntity attacker && attacker.getMainHandItem().getItem() instanceof AxeItem) {
            this.playSound(SoundEvents.SHIELD_BREAK, 1.0F, 1.0F);
            this.shieldDisableTicks = 200; // 10.0 seconds disable
            lowerShield();
            return false;
        }

        // 5. If shield was down when struck (left forearm block), react and raise shield
        if (!isShieldRaised()) {
            raiseShield(80);
            if (source.getEntity() instanceof LivingEntity attacker && !AlliedFactionHelper.isAllied(this, attacker)) {
                this.setTarget(attacker);
                this.getLookControl().setLookAt(attacker, 45.0F, 45.0F);
            }
        } else {
            this.shieldDurationTicks = Math.max(this.shieldDurationTicks, 60);
        }

        return false; // Attack blocked! No damage taken by warrior.
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Direct hits to the warrior mob body (e.g. headshots, leg shots, flank/rear hits) deal full damage
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide) {
            if (source.getEntity() instanceof LivingEntity attacker && this.getTarget() == null) {
                if (!AlliedFactionHelper.isAllied(this, attacker)) {
                    this.setTarget(attacker);
                }
            }
        }
        return hurt;
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
    public boolean isDamageSourceBlocked(DamageSource damageSource) {
        // Physical blocking is entirely handled by the shield PartEntity sub-hitbox
        return false;
    }

    @Override
    public boolean isBlocking() {
        return false;
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
        // Cannot attack while already attacking or on recovery cooldown
        if (isAttacking() || this.attackCooldownTicks > 0) return false;

        if (!this.level().isClientSide) {
            if (isShieldRaised()) {
                lowerShield();
            }
            this.pendingAttackTarget = livingTarget;
            this.attackImpactTicks = 9; // Contact frame at 9 ticks (~0.45s)
            this.attackCooldownTicks = 28; // 20 ticks full attack animation + 8 ticks recovery
            setActionState(ACTION_ATTACK, 20);
            this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.8F);
        }
        return true;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        controllers.add(new AnimationController<>(this, "combat", 2, this::combatPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerWarriorEntity> state) {
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
        return state.setAndContinue(IDLE);
    }

    private PlayState combatPredicate(AnimationState<PillagerWarriorEntity> state) {
        byte action = getActionState();
        if (action != this.lastActionState) {
            state.getController().forceAnimationReset();
            this.lastActionState = action;
        }

        return switch (action) {
            case ACTION_ATTACK -> state.setAndContinue(ATTACK);
            case ACTION_SHIELD_RAISE -> state.setAndContinue(SHIELD_RAISE);
            case ACTION_SHIELD_HOLD -> state.setAndContinue(SHIELD_HOLD);
            case ACTION_SHIELD_LOWER -> state.setAndContinue(SHIELD_LOWER);
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
        if (isShieldRaised()) {
            return AbstractIllager.IllagerArmPose.CROSSBOW_CHARGE;
        }
        if (this.isAggressive()) {
            return AbstractIllager.IllagerArmPose.ATTACKING;
        }
        return AbstractIllager.IllagerArmPose.NEUTRAL;
    }

    /**
     * Multi-Part Entity sub-hitbox representing the physical shield.
     * Similar to EnderDragonPart, this sub-entity registers in NeoForge's level part map
     * and intercepts raycasts, melee strikes, and projectiles.
     */
    public static class PillagerWarriorShieldPart extends PartEntity<PillagerWarriorEntity> {
        public final PillagerWarriorEntity parentMob;
        private final EntityDimensions size;

        public PillagerWarriorShieldPart(PillagerWarriorEntity parentMob, float width, float height) {
            super(parentMob);
            this.size = EntityDimensions.scalable(width, height);
            this.refreshDimensions();
            this.parentMob = parentMob;
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {
        }

        @Override
        protected void readAdditionalSaveData(CompoundTag compound) {
        }

        @Override
        protected void addAdditionalSaveData(CompoundTag compound) {
        }

        @Override
        public boolean isPickable() {
            return true;
        }

        @Nullable
        @Override
        public ItemStack getPickResult() {
            return this.parentMob.getPickResult();
        }

        @Override
        public boolean hurt(DamageSource source, float amount) {
            return this.isInvulnerableTo(source) ? false : this.parentMob.hurtShield(this, source, amount);
        }

        @Override
        public boolean is(Entity entity) {
            return this == entity || this.parentMob == entity;
        }

        @Override
        public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EntityDimensions getDimensions(Pose pose) {
            return this.size;
        }

        @Override
        public boolean shouldBeSaved() {
            return false;
        }
    }

    /**
     * AI Goal: Controls melee engagement with heavy war axe.
     * Prevents attacking while shield is raised, ensures the 20-tick swing animation
     * finishes completely before initiating another attack, and enforces a deliberate
     * shocktrooper attack cadence (28 ticks / ~1.4s).
     */
    static class WarriorMeleeAttackGoal extends MeleeAttackGoal {
        private final PillagerWarriorEntity warrior;

        public WarriorMeleeAttackGoal(PillagerWarriorEntity warrior, double speedModifier) {
            super(warrior, speedModifier, false);
            this.warrior = warrior;
        }

        @Override
        public boolean canUse() {
            if (this.warrior.isShieldRaised()) {
                return false;
            }
            return super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            if (this.warrior.isShieldRaised()) {
                return false;
            }
            return super.canContinueToUse();
        }

        @Override
        protected void checkAndPerformAttack(LivingEntity target) {
            if (this.canPerformAttack(target)) {
                this.resetAttackCooldown();
                this.warrior.doHurtTarget(target);
            }
        }

        @Override
        protected boolean canPerformAttack(LivingEntity entity) {
            if (this.warrior.isAttacking() || this.warrior.attackCooldownTicks > 0) {
                return false;
            }
            return super.canPerformAttack(entity);
        }
    }

    /**
     * AI Goal: Controls movement and facing while shield is raised.
     * Keeps warrior angled directly at the target threat while steadily advancing forward.
     */
    static class WarriorShieldDefenseGoal extends Goal {
        private final PillagerWarriorEntity warrior;

        public WarriorShieldDefenseGoal(PillagerWarriorEntity warrior) {
            this.warrior = warrior;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return this.warrior.isShieldRaised();
        }

        @Override
        public boolean canContinueToUse() {
            return this.warrior.isShieldRaised();
        }

        @Override
        public void tick() {
            LivingEntity target = this.warrior.getTarget();
            if (target != null && target.isAlive()) {
                this.warrior.getLookControl().setLookAt(target, 35.0F, 35.0F);
                this.warrior.getNavigation().moveTo(target, 0.85D);
            } else {
                Player closest = this.warrior.level().getNearestPlayer(this.warrior, 20.0D);
                if (closest != null) {
                    this.warrior.getLookControl().setLookAt(closest, 35.0F, 35.0F);
                }
            }
        }
    }
}
