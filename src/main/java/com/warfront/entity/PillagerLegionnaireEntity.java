package com.warfront.entity;

import com.warfront.ai.MeleeEngagementManager;
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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
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
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.DifficultyInstance;
import net.neoforged.neoforge.entity.PartEntity;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Pillager Legionnaire — disciplined upgraded Pillager spearman with a 4-stance combat state machine:
 *   1. Idle (back-slung shield) -> First aggro plays {@code idle_to_twohanded}.
 *   2. Two-Handed Spear (back-slung shield, long-reach thrust & sweeping cleave).
 *   3. Shield & Spear (front shield guard + thrust; shifts on <=50% HP or player projectile hit).
 *   4. Spearless Shield (triggered after 50% disengage spear throw; retrieves thrown javelin).
 *
 * Physical multipart shield ({@link LegionnaireShieldPart}) tracks cleanly between back and front,
 * carries 10 durability HP (sword=1, sword crit=2, axe=3, axe crit=4), and permanently shatters
 * into a locked Two-Handed state upon depletion.
 */
public class PillagerLegionnaireEntity extends AbstractIllager implements GeoEntity {

    // --- 4 Core Stances ---
    public static final byte STANCE_IDLE = 0;
    public static final byte STANCE_TWO_HANDED = 1;
    public static final byte STANCE_SHIELDED = 2;
    public static final byte STANCE_SPEARLESS_SHIELD = 3;

    // --- Discrete Action Overlays ---
    public static final byte ACTION_NONE = 0;
    public static final byte ACTION_IDLE_TO_TWOHANDED = 1;
    public static final byte ACTION_TWOHANDED_TO_SHIELDED = 2;
    public static final byte ACTION_SHIELDED_TO_TWOHANDED = 3;
    public static final byte ACTION_TWO_HANDED_THRUST = 4;
    public static final byte ACTION_TWO_HANDED_SWEEP = 5;
    public static final byte ACTION_SHIELDED_THRUST = 6;
    public static final byte ACTION_SPEAR_THROW = 7;
    public static final byte ACTION_SPEAR_PICK_UP = 8;

    public static final int MAX_SHIELD_HP = 10;

    private static final double MELEE_ENGAGE_RANGE_SQ = 3.0D * 3.0D;
    private static final double THROW_TRIGGER_RANGE_SQ = 10.0D * 10.0D;
    private static final double TWO_HANDED_REACH = 3.6D;
    private static final double SHIELDED_REACH = 2.7D;
    private static final double PICKUP_REACH_SQ = 2.1D * 2.1D;
    private static final int MAX_SPEARLESS_TICKS = 160; // 8s unreachable fallback

    private static final EntityDataAccessor<Byte> STANCE =
            SynchedEntityData.defineId(PillagerLegionnaireEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> ACTION_STATE =
            SynchedEntityData.defineId(PillagerLegionnaireEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Integer> SHIELD_HP =
            SynchedEntityData.defineId(PillagerLegionnaireEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> SHIELD_BROKEN =
            SynchedEntityData.defineId(PillagerLegionnaireEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SHIELD_IN_FRONT =
            SynchedEntityData.defineId(PillagerLegionnaireEntity.class, EntityDataSerializers.BOOLEAN);

    // --- GeckoLib Animations (14 exported sequences) ---
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation IDLE_TO_TWOHANDED = RawAnimation.begin().thenPlayAndHold("idle_to_twohanded");
    private static final RawAnimation TWO_HANDED_STATE = RawAnimation.begin().thenLoop("two_handed_state");
    private static final RawAnimation TWOHANDED_TO_SHIELDED = RawAnimation.begin().thenPlayAndHold("twohanded_to_shielded");
    private static final RawAnimation SHIELDED_STATE = RawAnimation.begin().thenLoop("shielded_state");
    private static final RawAnimation SHIELDED_TO_TWOHANDED = RawAnimation.begin().thenPlayAndHold("shielded_to_twohanded");
    private static final RawAnimation TWO_HANDED_THRUST = RawAnimation.begin().thenPlay("two_handed_thrust");
    private static final RawAnimation TWO_HANDED_SWEEP = RawAnimation.begin().thenPlay("two_handed_sweep");
    private static final RawAnimation SHIELDED_THRUST = RawAnimation.begin().thenPlay("shielded_thrust");
    private static final RawAnimation SPEAR_THROW = RawAnimation.begin().thenPlay("spear_throw");
    private static final RawAnimation SPEARLESS_SHIELD_STATE = RawAnimation.begin().thenLoop("spearless_shield_state");
    private static final RawAnimation SPEAR_PICK_UP = RawAnimation.begin().thenPlay("spear_pick_up");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final LegionnaireShieldPart shieldPart;
    private final PartEntity<?>[] subEntities;

    private boolean firstAggroDone = false;
    private boolean meleeEngaged = false;
    @Nullable
    private UUID engagedPlayerUuid = null;
    @Nullable
    private UUID trackedJavelinUuid = null;
    private int spearlessTicks = 0;

    private int actionTicksRemaining = 0;
    private int actionElapsedTicks = 0;
    private int attackCooldownTicks = 0;
    private int shoveCooldownTicks = 0;
    private boolean preferSweepNext = false;
    @Nullable
    private LivingEntity pendingTarget = null;

    // Pre-attack player crit snapshot captured via AttackEntityEvent before Vanilla PartEntity handling
    @Nullable
    private UUID lastAttackingPlayerUuid = null;
    private boolean lastAttackWasCrit = false;
    private int lastAttackSnapshotTick = -100;

    // Client controller reset tracking
    private byte lastAnimAction = ACTION_NONE;
    private byte lastAnimStance = STANCE_IDLE;

    public PillagerLegionnaireEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 12;
        this.shieldPart = new LegionnaireShieldPart(this, 0.72F, 1.25F);
        this.subEntities = new PartEntity<?>[]{this.shieldPart};
        this.setId(ENTITY_COUNTER.getAndAdd(this.subEntities.length + 1) + 1);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 34.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.22D)
                .add(Attributes.ATTACK_DAMAGE, 9.0D)
                .add(Attributes.ARMOR, 6.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.35D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STANCE, STANCE_IDLE);
        builder.define(ACTION_STATE, ACTION_NONE);
        builder.define(SHIELD_HP, MAX_SHIELD_HP);
        builder.define(SHIELD_BROKEN, false);
        builder.define(SHIELD_IN_FRONT, false);
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

    public LegionnaireShieldPart getShieldPart() {
        return this.shieldPart;
    }

    public byte getStance() {
        return this.entityData.get(STANCE);
    }

    public void setStance(byte stance) {
        this.entityData.set(STANCE, stance);
    }

    public byte getActionState() {
        return this.entityData.get(ACTION_STATE);
    }

    public int getShieldHp() {
        return this.entityData.get(SHIELD_HP);
    }

    public boolean isShieldBroken() {
        return this.entityData.get(SHIELD_BROKEN);
    }

    public boolean isShieldInFront() {
        return this.entityData.get(SHIELD_IN_FRONT);
    }

    public boolean isBusyInAction() {
        return getActionState() != ACTION_NONE;
    }

    private void startAction(byte action, int durationTicks) {
        this.entityData.set(ACTION_STATE, action);
        this.actionTicksRemaining = durationTicks;
        this.actionElapsedTicks = 0;
    }

    /**
     * Captures exact vanilla player critical-hit eligibility before {@code Player.attack(Entity)}
     * bypasses crits on non-LivingEntity {@link PartEntity} targets or resets the attack ticker.
     */
    public void recordPlayerAttackAttempt(Player player) {
        if (player == null) return;
        boolean fullCharge = player.getAttackStrengthScale(0.5F) > 0.9F;
        boolean isCrit = fullCharge
                && player.fallDistance > 0.0F
                && !player.onGround()
                && !player.onClimbable()
                && !player.isInWater()
                && !player.hasEffect(MobEffects.BLINDNESS)
                && !player.isPassenger()
                && !player.isSprinting();
        this.lastAttackingPlayerUuid = player.getUUID();
        this.lastAttackWasCrit = isCrit;
        this.lastAttackSnapshotTick = this.tickCount;
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LegionnaireSpearRetrievalGoal(this));
        this.goalSelector.addGoal(2, new LegionnaireCombatGoal(this));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Raider.class).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
                target -> !AlliedFactionHelper.isAllied(this, target)));
    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        syncShieldPartPosition();

        if (!this.level().isClientSide) {
            LivingEntity target = this.getTarget();
            boolean hasTarget = target != null && target.isAlive();
            this.setAggressive(hasTarget);

            if (this.attackCooldownTicks > 0) this.attackCooldownTicks--;
            if (this.shoveCooldownTicks > 0) this.shoveCooldownTicks--;

            if (hasTarget) {
                MeleeEngagementManager.requestSlot(target, this, false);
                if (getStance() == STANCE_IDLE && !this.firstAggroDone && getActionState() == ACTION_NONE) {
                    this.firstAggroDone = true;
                    startAction(ACTION_IDLE_TO_TWOHANDED, 10);
                    this.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 0.9F, 1.05F);
                }
            }

            // Safeguard: ensure <=50% HP triggers shift even from non-attack sources (fall/poison/etc.)
            if (!isShieldBroken()
                    && (getStance() == STANCE_IDLE || getStance() == STANCE_TWO_HANDED)
                    && this.getHealth() <= this.getMaxHealth() * 0.5F) {
                requestShiftToShielded(target);
            }

            // Advance active discrete action frames
            if (getActionState() != ACTION_NONE) {
                tickActiveAction(target);
            } else if (getStance() == STANCE_SHIELDED && hasTarget) {
                evaluateSpearThrowTrigger(target);
            }

            if (getStance() == STANCE_SPEARLESS_SHIELD && getActionState() == ACTION_NONE) {
                this.spearlessTicks++;
            }
        }
    }

    private void syncShieldPartPosition() {
        Vec3 oldPos = new Vec3(this.shieldPart.getX(), this.shieldPart.getY(), this.shieldPart.getZ());

        if (isShieldBroken()) {
            // Park inside center body (non-pickable) so disabled hitbox cannot interfere
            this.shieldPart.setPos(this.getX(), this.getY() + 0.5D, this.getZ());
        } else {
            double bodyRad = this.yBodyRot * (Math.PI / 180.0D);
            double fwdX = -Math.sin(bodyRad);
            double fwdZ = Math.cos(bodyRad);
            double leftX = Math.cos(bodyRad);
            double leftZ = Math.sin(bodyRad);

            double offsetX;
            double offsetY;
            double offsetZ;
            if (isShieldInFront()) {
                offsetX = fwdX * 0.48D + leftX * 0.14D;
                offsetY = 0.22D;
                offsetZ = fwdZ * 0.48D + leftZ * 0.14D;
            } else {
                // Mounted cleanly on back behind torso until equipped
                offsetX = -fwdX * 0.42D;
                offsetY = 0.50D;
                offsetZ = -fwdZ * 0.42D;
            }
            this.shieldPart.setPos(this.getX() + offsetX, this.getY() + offsetY, this.getZ() + offsetZ);
        }

        this.shieldPart.xo = oldPos.x;
        this.shieldPart.yo = oldPos.y;
        this.shieldPart.zo = oldPos.z;
        this.shieldPart.xOld = oldPos.x;
        this.shieldPart.yOld = oldPos.y;
        this.shieldPart.zOld = oldPos.z;
    }

    private void tickActiveAction(@Nullable LivingEntity currentTarget) {
        byte action = getActionState();
        this.actionElapsedTicks++;
        this.actionTicksRemaining--;

        LivingEntity focus = (this.pendingTarget != null && this.pendingTarget.isAlive()) ? this.pendingTarget : currentTarget;
        if (focus != null && action != ACTION_SPEAR_PICK_UP) {
            this.getLookControl().setLookAt(focus, 45.0F, 45.0F);
        }

        switch (action) {
            case ACTION_TWOHANDED_TO_SHIELDED -> {
                if (this.actionElapsedTicks == 7 && !isShieldBroken()) {
                    this.entityData.set(SHIELD_IN_FRONT, true);
                    this.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.15F);
                }
            }
            case ACTION_SHIELDED_TO_TWOHANDED -> {
                if (this.actionElapsedTicks == 8) {
                    this.entityData.set(SHIELD_IN_FRONT, false);
                    this.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 0.90F);
                }
            }
            case ACTION_TWO_HANDED_THRUST -> {
                if (this.actionElapsedTicks == 9) {
                    performMeleeStrike(focus, TWO_HANDED_REACH, 9.0F, 75.0D, false);
                }
            }
            case ACTION_TWO_HANDED_SWEEP -> {
                if (this.actionElapsedTicks == 10) {
                    performMeleeStrike(focus, TWO_HANDED_REACH - 0.15D, 9.0F, 120.0D, true);
                }
            }
            case ACTION_SHIELDED_THRUST -> {
                if (this.actionElapsedTicks == 5) {
                    performMeleeStrike(focus, SHIELDED_REACH, 6.5F, 75.0D, false);
                }
            }
            case ACTION_SPEAR_THROW -> {
                this.getNavigation().stop();
                if (this.actionElapsedTicks == 16) {
                    launchJavelinProjectile(focus);
                }
            }
            case ACTION_SPEAR_PICK_UP -> {
                this.getNavigation().stop();
                if (this.actionElapsedTicks == 8) {
                    consumeTrackedJavelin();
                    this.playSound(SoundEvents.ITEM_PICKUP, 0.9F, 1.0F);
                }
            }
            default -> {}
        }

        if (this.actionTicksRemaining <= 0) {
            this.entityData.set(ACTION_STATE, ACTION_NONE);
            this.pendingTarget = null;
            this.actionElapsedTicks = 0;

            switch (action) {
                case ACTION_IDLE_TO_TWOHANDED, ACTION_SHIELDED_TO_TWOHANDED -> {
                    setStance(STANCE_TWO_HANDED);
                    this.entityData.set(SHIELD_IN_FRONT, false);
                }
                case ACTION_TWOHANDED_TO_SHIELDED -> {
                    if (!isShieldBroken()) {
                        setStance(STANCE_SHIELDED);
                        this.entityData.set(SHIELD_IN_FRONT, true);
                    } else {
                        setStance(STANCE_TWO_HANDED);
                        this.entityData.set(SHIELD_IN_FRONT, false);
                    }
                }
                case ACTION_SPEAR_THROW -> {
                    setStance(STANCE_SPEARLESS_SHIELD);
                    this.spearlessTicks = 0;
                    if (!isShieldBroken()) {
                        this.entityData.set(SHIELD_IN_FRONT, true);
                    }
                }
                case ACTION_SPEAR_PICK_UP -> {
                    if (isShieldBroken()) {
                        setStance(STANCE_TWO_HANDED);
                        this.entityData.set(SHIELD_IN_FRONT, false);
                    } else {
                        setStance(STANCE_SHIELDED);
                        this.entityData.set(SHIELD_IN_FRONT, true);
                    }
                }
                default -> {}
            }
        }
    }

    private void performMeleeStrike(@Nullable LivingEntity primaryTarget, double reach, float damage, double arcDegrees, boolean sweeping) {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        Vec3 fwd = Vec3.directionFromRotation(0.0F, this.getYHeadRot()).normalize();
        double minCos = Math.cos(Math.toRadians(arcDegrees * 0.5D));

        if (!sweeping) {
            if (primaryTarget != null && primaryTarget.isAlive() && this.distanceToSqr(primaryTarget) <= reach * reach) {
                Vec3 toTarget = primaryTarget.position().subtract(this.position());
                Vec3 horiz = new Vec3(toTarget.x, 0.0D, toTarget.z);
                if (horiz.lengthSqr() < 1.0E-4D || horiz.normalize().dot(fwd) >= minCos) {
                    if (primaryTarget.hurt(this.damageSources().mobAttack(this), damage)) {
                        this.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 1.0F, 0.95F);
                    }
                }
            }
            return;
        }

        // Sweep cleave hits hostile/non-allied targets across a frontal arc
        List<LivingEntity> candidates = serverLevel.getEntitiesOfClass(
                LivingEntity.class,
                this.getBoundingBox().inflate(reach + 0.5D),
                e -> e.isAlive() && e != this && !AlliedFactionHelper.isAllied(this, e)
        );
        boolean hitAny = false;
        for (LivingEntity candidate : candidates) {
            if (this.distanceToSqr(candidate) > reach * reach) continue;
            Vec3 toTarget = candidate.position().subtract(this.position());
            Vec3 horiz = new Vec3(toTarget.x, 0.0D, toTarget.z);
            if (horiz.lengthSqr() < 1.0E-4D || horiz.normalize().dot(fwd) >= minCos) {
                if (candidate.hurt(this.damageSources().mobAttack(this), damage)) {
                    candidate.knockback(0.45D, -fwd.x, -fwd.z);
                    hitAny = true;
                }
            }
        }
        if (hitAny) {
            this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.90F);
        }
    }

    private void evaluateSpearThrowTrigger(LivingEntity target) {
        if (!this.meleeEngaged || this.engagedPlayerUuid == null) return;
        if (!(target instanceof Player player) || !player.getUUID().equals(this.engagedPlayerUuid)) return;
        if (this.distanceToSqr(player) <= THROW_TRIGGER_RANGE_SQ) return;
        if (!this.getSensing().hasLineOfSight(player)) return;

        // Single roll per qualified melee engagement
        this.meleeEngaged = false;
        this.engagedPlayerUuid = null;

        if (this.random.nextFloat() < 0.50F) {
            this.pendingTarget = player;
            this.getNavigation().stop();
            this.attackCooldownTicks = 28;
            startAction(ACTION_SPEAR_THROW, 25);
        }
    }

    private void launchJavelinProjectile(@Nullable LivingEntity target) {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        double spawnX = this.getX();
        double spawnY = this.getY() + 1.45D;
        double spawnZ = this.getZ();

        Vec3 aimDir;
        double speed = 1.45D;
        if (target != null && target.isAlive()) {
            Vec3 targetCenter = target.getEyePosition().subtract(0.0D, 0.25D, 0.0D);
            double dist = targetCenter.distanceTo(new Vec3(spawnX, spawnY, spawnZ));
            double flightTicks = dist / speed;
            double gravityComp = 0.5D * JavelinProjectileEntity.GRAVITY * flightTicks * flightTicks;
            Vec3 correctedTarget = targetCenter.add(0.0D, Math.min(gravityComp, 6.0D), 0.0D);
            aimDir = correctedTarget.subtract(spawnX, spawnY, spawnZ).normalize();
        } else {
            aimDir = this.getLookAngle().normalize();
        }

        JavelinProjectileEntity javelin = new JavelinProjectileEntity(serverLevel, this, spawnX, spawnY, spawnZ);
        Vec3 velocity = aimDir.scale(speed);
        javelin.setDeltaMovement(velocity);
        javelin.syncOrientationFromVelocity(velocity);
        serverLevel.addFreshEntity(javelin);

        this.trackedJavelinUuid = javelin.getUUID();
        this.playSound(SoundEvents.TRIDENT_THROW.value(), 1.1F, 1.0F);
    }

    private void consumeTrackedJavelin() {
        if (this.level() instanceof ServerLevel serverLevel && this.trackedJavelinUuid != null) {
            Entity e = serverLevel.getEntity(this.trackedJavelinUuid);
            if (e != null) {
                e.discard();
            }
        }
        this.trackedJavelinUuid = null;
    }

    @Nullable
    private JavelinProjectileEntity getTrackedJavelin() {
        if (this.trackedJavelinUuid != null && this.level() instanceof ServerLevel serverLevel) {
            Entity e = serverLevel.getEntity(this.trackedJavelinUuid);
            if (e instanceof JavelinProjectileEntity javelin && javelin.isAlive()) {
                return javelin;
            }
        }
        return null;
    }

    private void requestShiftToShielded(@Nullable LivingEntity attacker) {
        if (isShieldBroken()) return;
        byte stance = getStance();
        byte action = getActionState();
        if (stance == STANCE_SHIELDED
                || stance == STANCE_SPEARLESS_SHIELD
                || action == ACTION_TWOHANDED_TO_SHIELDED
                || action == ACTION_SHIELDED_TO_TWOHANDED) {
            return;
        }
        this.firstAggroDone = true;
        if (attacker != null && attacker.isAlive() && !AlliedFactionHelper.isAllied(this, attacker) && this.getTarget() == null) {
            this.setTarget(attacker);
        }
        this.pendingTarget = attacker;
        startAction(ACTION_TWOHANDED_TO_SHIELDED, 15);
    }

    private void maybeRecordMeleeEngagement(DamageSource source) {
        if (!source.isDirect()) return;
        if (source.getEntity() instanceof Player player && !player.isSpectator()) {
            if (this.distanceToSqr(player) <= MELEE_ENGAGE_RANGE_SQ) {
                this.meleeEngaged = true;
                this.engagedPlayerUuid = player.getUUID();
            }
        }
    }

    private static boolean isPlayerProjectile(DamageSource source) {
        return source.getDirectEntity() instanceof Projectile projectile && projectile.getOwner() instanceof Player;
    }

    private int computeShieldChipDamage(Player attacker) {
        ItemStack stack = attacker.getMainHandItem();
        boolean isAxe = stack.getItem() instanceof AxeItem || stack.is(ItemTags.AXES);
        boolean isSword = stack.getItem() instanceof SwordItem || stack.is(ItemTags.SWORDS);
        if (!isAxe && !isSword) {
            return 0;
        }

        boolean isCrit;
        if (this.lastAttackingPlayerUuid != null
                && this.lastAttackingPlayerUuid.equals(attacker.getUUID())
                && (this.tickCount - this.lastAttackSnapshotTick) <= 1) {
            isCrit = this.lastAttackWasCrit;
            this.lastAttackingPlayerUuid = null;
        } else {
            isCrit = attacker.fallDistance > 0.0F
                    && !attacker.onGround()
                    && !attacker.onClimbable()
                    && !attacker.isInWater()
                    && !attacker.hasEffect(MobEffects.BLINDNESS)
                    && !attacker.isPassenger()
                    && !attacker.isSprinting();
        }

        if (isSword) {
            return isCrit ? 2 : 1;
        }
        return isCrit ? 4 : 3;
    }

    private void breakShield() {
        if (isShieldBroken()) return;
        this.entityData.set(SHIELD_HP, 0);
        this.entityData.set(SHIELD_BROKEN, true);
        this.entityData.set(SHIELD_IN_FRONT, false);
        this.meleeEngaged = false;
        this.engagedPlayerUuid = null;

        this.playSound(SoundEvents.SHIELD_BREAK, 1.2F, 0.85F);
        if (this.level() instanceof ServerLevel sl) {
            Vec3 p = this.shieldPart.position().add(0.0D, this.shieldPart.getBbHeight() * 0.5D, 0.0D);
            sl.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 12, 0.25D, 0.35D, 0.25D, 0.2D);
            sl.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 8, 0.2D, 0.3D, 0.2D, 0.05D);
        }

        // Discard any stray ground javelin so the forced Two-Handed Legionnaire holds a spear cleanly
        consumeTrackedJavelin();

        byte curStance = getStance();
        if (curStance == STANCE_SHIELDED || curStance == STANCE_SPEARLESS_SHIELD
                || getActionState() == ACTION_SPEAR_THROW || getActionState() == ACTION_SPEAR_PICK_UP) {
            startAction(ACTION_SHIELDED_TO_TWOHANDED, 15);
        } else {
            this.entityData.set(ACTION_STATE, ACTION_NONE);
            this.actionTicksRemaining = 0;
            this.actionElapsedTicks = 0;
            setStance(STANCE_TWO_HANDED);
        }
    }

    /**
     * Intercepts attacks against the physical shield {@link PartEntity}.
     */
    public boolean hurtShield(LegionnaireShieldPart part, DamageSource source, float amount) {
        if (isShieldBroken()) {
            return super.hurt(source, amount);
        }

        if (source.is(DamageTypeTags.BYPASSES_SHIELD)
                || source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypeTags.IS_FIRE)
                || source.is(DamageTypeTags.IS_DROWNING)
                || source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.STARVE)) {
            return super.hurt(source, amount);
        }

        this.playSound(SoundEvents.SHIELD_BLOCK, 1.0F, 0.9F + this.getRandom().nextFloat() * 0.2F);
        if (this.level() instanceof ServerLevel serverLevel) {
            Vec3 partPos = part.position().add(0.0D, part.getBbHeight() * 0.5D, 0.0D);
            serverLevel.sendParticles(ParticleTypes.CRIT, partPos.x, partPos.y, partPos.z, 6, 0.12D, 0.12D, 0.12D, 0.12D);
            serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK, partPos.x, partPos.y, partPos.z, 4, 0.08D, 0.08D, 0.08D, 0.05D);
        }

        if (!this.level().isClientSide) {
            if (source.getDirectEntity() instanceof Projectile projectile) {
                if (projectile.getOwner() instanceof Player shooter) {
                    requestShiftToShielded(shooter);
                }
                if (!(projectile instanceof JavelinProjectileEntity)) {
                    projectile.discard();
                }
                return false;
            }

            maybeRecordMeleeEngagement(source);

            if (source.getEntity() instanceof LivingEntity attacker && !AlliedFactionHelper.isAllied(this, attacker)) {
                if (this.getTarget() == null) {
                    this.setTarget(attacker);
                }
                this.getLookControl().setLookAt(attacker, 45.0F, 45.0F);
            }

            if (source.getEntity() instanceof Player player) {
                int chip = computeShieldChipDamage(player);
                if (chip > 0) {
                    if (chip == 2 || chip == 4) {
                        if (this.level() instanceof ServerLevel sl) {
                            Vec3 partPos = part.position().add(0.0D, part.getBbHeight() * 0.5D, 0.0D);
                            sl.sendParticles(ParticleTypes.CRIT, partPos.x, partPos.y, partPos.z, 10, 0.2D, 0.25D, 0.2D, 0.18D);
                        }
                    }
                    int nextHp = getShieldHp() - chip;
                    if (nextHp <= 0) {
                        breakShield();
                    } else {
                        this.entityData.set(SHIELD_HP, nextHp);
                    }
                }
            }
        }

        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean playerProj = !this.level().isClientSide && isPlayerProjectile(source);
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide) {
            maybeRecordMeleeEngagement(source);

            LivingEntity attacker = source.getEntity() instanceof LivingEntity le ? le : null;
            if (attacker != null && this.getTarget() == null && !AlliedFactionHelper.isAllied(this, attacker)) {
                this.setTarget(attacker);
            }

            if ((playerProj || this.getHealth() <= this.getMaxHealth() * 0.5F) && !isShieldBroken()) {
                requestShiftToShielded(attacker);
            }
        }
        return hurt;
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        if (!(target instanceof LivingEntity livingTarget)) return false;
        if (isBusyInAction() || this.attackCooldownTicks > 0) return false;

        if (!this.level().isClientSide) {
            byte stance = getStance();
            if (stance == STANCE_TWO_HANDED) {
                this.pendingTarget = livingTarget;
                if (this.preferSweepNext) {
                    startAction(ACTION_TWO_HANDED_SWEEP, 20);
                } else {
                    startAction(ACTION_TWO_HANDED_THRUST, 24);
                }
                this.preferSweepNext = !this.preferSweepNext;
                this.attackCooldownTicks = 28;
                return true;
            } else if (stance == STANCE_SHIELDED) {
                this.pendingTarget = livingTarget;
                startAction(ACTION_SHIELDED_THRUST, 13);
                this.attackCooldownTicks = 22;
                return true;
            }
        }
        return false;
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
    public void setTarget(@Nullable LivingEntity target) {
        if (target != null && AlliedFactionHelper.isAllied(this, target)) {
            return;
        }
        LivingEntity oldTarget = this.getTarget();
        if (oldTarget != null && !oldTarget.equals(target)) {
            MeleeEngagementManager.releaseSlot(oldTarget, this);
        }
        super.setTarget(target);
    }

    /**
     * Returns true while the Legionnaire is physically holding its spear (including pre-release throw frames
     * and post-grab pick-up frames), used to gate guaranteed Javelin drops on death.
     */
    public boolean hasSpearInHand() {
        byte action = getActionState();
        if (action == ACTION_SPEAR_THROW) {
            return this.actionElapsedTicks < 16;
        }
        if (action == ACTION_SPEAR_PICK_UP) {
            return this.actionElapsedTicks >= 8;
        }
        return getStance() != STANCE_SPEARLESS_SHIELD;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        if (this.hasSpearInHand()) {
            this.spawnAtLocation(new ItemStack(com.warfront.block.WarfrontBlocks.PILLAGER_JAVELIN.get()));
        }
    }

    @Override
    public void die(DamageSource damageSource) {
        super.die(damageSource);
        this.pendingTarget = null;
        consumeTrackedJavelin();
        MeleeEngagementManager.onMobDied(this.getTarget(), this);
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        super.remove(reason);
        MeleeEngagementManager.releaseAll(this);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putByte("Stance", getStance());
        compound.putBoolean("FirstAggroDone", this.firstAggroDone);
        compound.putInt("ShieldHp", getShieldHp());
        compound.putBoolean("ShieldBroken", isShieldBroken());
        compound.putBoolean("ShieldInFront", isShieldInFront());
        compound.putBoolean("MeleeEngaged", this.meleeEngaged);
        compound.putInt("SpearlessTicks", this.spearlessTicks);
        if (this.engagedPlayerUuid != null) {
            compound.putUUID("EngagedPlayerUuid", this.engagedPlayerUuid);
        }
        if (this.trackedJavelinUuid != null) {
            compound.putUUID("TrackedJavelinUuid", this.trackedJavelinUuid);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.contains("Stance")) setStance(compound.getByte("Stance"));
        this.firstAggroDone = compound.getBoolean("FirstAggroDone");
        if (compound.contains("ShieldHp")) this.entityData.set(SHIELD_HP, compound.getInt("ShieldHp"));
        if (compound.contains("ShieldBroken")) this.entityData.set(SHIELD_BROKEN, compound.getBoolean("ShieldBroken"));
        if (compound.contains("ShieldInFront")) this.entityData.set(SHIELD_IN_FRONT, compound.getBoolean("ShieldInFront"));
        this.meleeEngaged = compound.getBoolean("MeleeEngaged");
        this.spearlessTicks = compound.getInt("SpearlessTicks");
        this.engagedPlayerUuid = compound.hasUUID("EngagedPlayerUuid") ? compound.getUUID("EngagedPlayerUuid") : null;
        this.trackedJavelinUuid = compound.hasUUID("TrackedJavelinUuid") ? compound.getUUID("TrackedJavelinUuid") : null;
    }

    // --- GeckoLib 4.7.4 Animation Controllers ---

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 3, this::movementPredicate));
        controllers.add(new AnimationController<>(this, "combat", 2, this::combatPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerLegionnaireEntity> state) {
        byte action = getActionState();
        boolean fullBodyAction = action == ACTION_TWO_HANDED_THRUST
                || action == ACTION_TWO_HANDED_SWEEP
                || action == ACTION_SHIELDED_THRUST
                || action == ACTION_SPEAR_THROW
                || action == ACTION_SPEAR_PICK_UP;
        if (fullBodyAction) {
            state.getController().stop();
            return PlayState.STOP;
        }

        boolean isMoving = state.isMoving()
                || this.walkAnimation.isMoving()
                || this.walkAnimation.speed() > 0.001F
                || state.getLimbSwingAmount() > 0.001F
                || this.getDeltaMovement().horizontalDistanceSqr() > 0.00005D
                || (this.getX() != this.xo || this.getZ() != this.zo);

        if (isMoving) {
            if (getStance() == STANCE_SPEARLESS_SHIELD) {
                return state.setAndContinue(WALK);
            }
            if (this.isSprinting() || this.isAggressive() || (this.getTarget() != null && this.getTarget().isAlive())) {
                return state.setAndContinue(RUN);
            }
            return state.setAndContinue(WALK);
        }

        return state.setAndContinue(IDLE);
    }

    private PlayState combatPredicate(AnimationState<PillagerLegionnaireEntity> state) {
        byte action = getActionState();
        byte stance = getStance();

        if (action != this.lastAnimAction || (action == ACTION_NONE && stance != this.lastAnimStance)) {
            state.getController().forceAnimationReset();
            this.lastAnimAction = action;
            this.lastAnimStance = stance;
        }

        if (action != ACTION_NONE) {
            return switch (action) {
                case ACTION_IDLE_TO_TWOHANDED -> state.setAndContinue(IDLE_TO_TWOHANDED);
                case ACTION_TWOHANDED_TO_SHIELDED -> state.setAndContinue(TWOHANDED_TO_SHIELDED);
                case ACTION_SHIELDED_TO_TWOHANDED -> state.setAndContinue(SHIELDED_TO_TWOHANDED);
                case ACTION_TWO_HANDED_THRUST -> state.setAndContinue(TWO_HANDED_THRUST);
                case ACTION_TWO_HANDED_SWEEP -> state.setAndContinue(TWO_HANDED_SWEEP);
                case ACTION_SHIELDED_THRUST -> state.setAndContinue(SHIELDED_THRUST);
                case ACTION_SPEAR_THROW -> state.setAndContinue(SPEAR_THROW);
                case ACTION_SPEAR_PICK_UP -> state.setAndContinue(SPEAR_PICK_UP);
                default -> PlayState.STOP;
            };
        }

        return switch (stance) {
            case STANCE_TWO_HANDED -> state.setAndContinue(TWO_HANDED_STATE);
            case STANCE_SHIELDED -> state.setAndContinue(SHIELDED_STATE);
            case STANCE_SPEARLESS_SHIELD -> state.setAndContinue(SPEARLESS_SHIELD_STATE);
            default -> {
                state.getController().stop();
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
        if (this.isAggressive()) {
            return AbstractIllager.IllagerArmPose.ATTACKING;
        }
        return AbstractIllager.IllagerArmPose.NEUTRAL;
    }

    /**
     * Multi-Part Entity sub-hitbox representing the Legionnaire's physical shield.
     * Positioned behind torso until shield is equipped, then moved in front.
     */
    public static class LegionnaireShieldPart extends PartEntity<PillagerLegionnaireEntity> {
        public final PillagerLegionnaireEntity parentMob;
        private final EntityDimensions size;

        public LegionnaireShieldPart(PillagerLegionnaireEntity parentMob, float width, float height) {
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
            return !this.parentMob.isShieldBroken();
        }

        @Nullable
        @Override
        public ItemStack getPickResult() {
            return this.parentMob.getPickResult();
        }

        @Override
        public boolean hurt(DamageSource source, float amount) {
            return !this.isInvulnerableTo(source) && this.parentMob.hurtShield(this, source, amount);
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
     * AI Goal for State 4 ({@code STANCE_SPEARLESS_SHIELD}): marches behind raised shield
     * to retrieve the thrown javelin projectile, with unreachable fallback & close-range shove.
     */
    static class LegionnaireSpearRetrievalGoal extends Goal {
        private final PillagerLegionnaireEntity legionnaire;

        public LegionnaireSpearRetrievalGoal(PillagerLegionnaireEntity legionnaire) {
            this.legionnaire = legionnaire;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return this.legionnaire.getStance() == STANCE_SPEARLESS_SHIELD
                    && this.legionnaire.getActionState() == ACTION_NONE;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void tick() {
            JavelinProjectileEntity javelin = this.legionnaire.getTrackedJavelin();
            LivingEntity target = this.legionnaire.getTarget();

            if (javelin == null) {
                if (this.legionnaire.spearlessTicks >= 20) {
                    recoverBackupSpear();
                }
                return;
            }

            if (this.legionnaire.spearlessTicks >= MAX_SPEARLESS_TICKS) {
                this.legionnaire.consumeTrackedJavelin();
                recoverBackupSpear();
                return;
            }

            if (javelin.isInGround() && this.legionnaire.distanceToSqr(javelin) <= PICKUP_REACH_SQ) {
                this.legionnaire.getNavigation().stop();
                this.legionnaire.getLookControl().setLookAt(javelin, 45.0F, 45.0F);
                this.legionnaire.startAction(ACTION_SPEAR_PICK_UP, 18);
                return;
            }

            this.legionnaire.getNavigation().moveTo(javelin.getX(), javelin.getY(), javelin.getZ(), 0.90D);
            if (target != null && target.isAlive()) {
                this.legionnaire.getLookControl().setLookAt(target, 35.0F, 35.0F);
                if (this.legionnaire.shoveCooldownTicks <= 0 && this.legionnaire.distanceToSqr(target) <= 2.6D * 2.6D) {
                    this.legionnaire.shoveCooldownTicks = 24;
                    if (target.hurt(this.legionnaire.damageSources().mobAttack(this.legionnaire), 4.0F)) {
                        Vec3 away = target.position().subtract(this.legionnaire.position());
                        Vec3 h = new Vec3(away.x, 0.0D, away.z);
                        if (h.lengthSqr() > 1.0E-4D) {
                            Vec3 n = h.normalize();
                            target.knockback(0.65D, -n.x, -n.z);
                        }
                        this.legionnaire.playSound(SoundEvents.SHIELD_BLOCK, 1.0F, 0.8F);
                    }
                }
            } else {
                this.legionnaire.getLookControl().setLookAt(javelin, 35.0F, 35.0F);
            }
        }

        private void recoverBackupSpear() {
            this.legionnaire.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 0.9F, 1.0F);
            this.legionnaire.spearlessTicks = 0;
            if (this.legionnaire.isShieldBroken()) {
                this.legionnaire.setStance(STANCE_TWO_HANDED);
                this.legionnaire.entityData.set(SHIELD_IN_FRONT, false);
            } else {
                this.legionnaire.setStance(STANCE_SHIELDED);
                this.legionnaire.entityData.set(SHIELD_IN_FRONT, true);
            }
        }
    }

    /**
     * AI Goal for combat pursuit, reserve perimeter holding behind {@link MeleeEngagementManager},
     * and Two-Handed / Shielded spear strikes.
     */
    static class LegionnaireCombatGoal extends Goal {
        private final PillagerLegionnaireEntity legionnaire;
        private int repathTimer = 0;

        public LegionnaireCombatGoal(PillagerLegionnaireEntity legionnaire) {
            this.legionnaire = legionnaire;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.legionnaire.getTarget();
            return target != null && target.isAlive()
                    && this.legionnaire.getStance() != STANCE_SPEARLESS_SHIELD;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void tick() {
            LivingEntity target = this.legionnaire.getTarget();
            if (target == null || !target.isAlive()) return;

            this.legionnaire.getLookControl().setLookAt(target, 35.0F, 35.0F);

            byte action = this.legionnaire.getActionState();
            if (action != ACTION_NONE) {
                if (action == ACTION_SPEAR_THROW || action == ACTION_TWO_HANDED_THRUST
                        || action == ACTION_TWO_HANDED_SWEEP || action == ACTION_SHIELDED_THRUST) {
                    this.legionnaire.getNavigation().stop();
                }
                return;
            }

            byte stance = this.legionnaire.getStance();
            double distSq = this.legionnaire.distanceToSqr(target);
            boolean isEngaged = MeleeEngagementManager.isEngaged(target, this.legionnaire);

            if (!isEngaged) {
                if (distSq > 36.0D) {
                    if (--this.repathTimer <= 0) {
                        this.repathTimer = 6;
                        this.legionnaire.getNavigation().moveTo(target, 0.95D);
                    }
                } else {
                    this.legionnaire.getNavigation().stop();
                }
                return;
            }

            double reach = (stance == STANCE_TWO_HANDED) ? TWO_HANDED_REACH : SHIELDED_REACH;
            if (distSq <= reach * reach && this.legionnaire.getSensing().hasLineOfSight(target)) {
                this.legionnaire.getNavigation().stop();
                if (this.legionnaire.attackCooldownTicks <= 0) {
                    this.legionnaire.doHurtTarget(target);
                }
                return;
            }

            if (--this.repathTimer <= 0) {
                this.repathTimer = 5;
                int slot = MeleeEngagementManager.getEngagedSlotIndex(target, this.legionnaire);
                double moveSpeed = (stance == STANCE_SHIELDED) ? 0.95D : 1.12D;
                if ((slot == 2 || slot == 3) && distSq > reach * reach && distSq < 144.0D) {
                    double dX = target.getX() - this.legionnaire.getX();
                    double dZ = target.getZ() - this.legionnaire.getZ();
                    double dist = Math.sqrt(dX * dX + dZ * dZ);
                    if (dist > 0.001D) {
                        double fX = dX / dist;
                        double fZ = dZ / dist;
                        double pX = -fZ;
                        double pZ = fX;
                        double side = (slot == 2) ? 1.0D : -1.0D;
                        double flankX = target.getX() - (fX * 2.0D) + (pX * side * 3.5D);
                        double flankZ = target.getZ() - (fZ * 2.0D) + (pZ * side * 3.5D);
                        this.legionnaire.getNavigation().moveTo(flankX, target.getY(), flankZ, moveSpeed);
                        return;
                    }
                }
                this.legionnaire.getNavigation().moveTo(target, moveSpeed);
            }
        }
    }
}
