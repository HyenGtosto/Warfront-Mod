package com.warfront.entity;

import com.warfront.ai.MeleeEngagementManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
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
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
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

import java.util.List;

/**
 * Pillager Elite — 1v1 Melee Specialist & Duelist for the Pillager Conqueror faction.
 * Wields a two-handed greatsword, deflects projectiles, and uses a repeating crossbow
 * for distance punishment with a 3-stock randomized depletion mechanic.
 * Scale: 2.5+ blocks tall.
 */
public class PillagerEliteEntity extends AbstractIllager implements GeoEntity {

    // Action state constants
    public static final byte ACTION_IDLE = 0;
    public static final byte ACTION_UNSHEATHE = 1;
    public static final byte ACTION_STANCE_SWORD = 2;
    public static final byte ACTION_ATTACK_1 = 3;
    public static final byte ACTION_SHEATHE = 4;
    public static final byte ACTION_SWORD_TO_XBOW = 5;
    public static final byte ACTION_STANCE_XBOW = 6;
    public static final byte ACTION_SHOOT_XBOW = 7;
    public static final byte ACTION_XBOW_TO_SWORD = 8;
    public static final byte ACTION_BLOCK = 9;
    public static final byte ACTION_ATTACK_2 = 10;
    public static final byte ACTION_ATTACK_3 = 11;
    public static final byte ACTION_ATTACK_4 = 12;
    public static final byte ACTION_KICK = 13;
    public static final byte ACTION_STAB_WINDUP = 14;
    public static final byte ACTION_STAB_CHARGE = 15;
    public static final byte ACTION_STAB_THRUST = 16;

    // Animation durations in ticks (20 TPS)
    public static final int UNSHEATHE_TICKS = 31;         // 1.5417s
    public static final int UNSHEATHE_GRAB_TICK = 10;      // Frame where sword is drawn from back
    public static final int ATTACK_1_TICKS = 16;          // 0.7917s
    public static final int ATTACK_1_DAMAGE_TICK = 8;     // 0.4000s
    public static final int ATTACK_1_LINK_TICK = 12;      // Frame to chain into Attack 2
    public static final int ATTACK_2_TICKS = 20;          // 1.0000s
    public static final int ATTACK_2_DAMAGE_TICK = 9;     // 0.4700s (User spec)
    public static final int ATTACK_2_LINK_TICK = 13;      // Frame to chain into Attack 3
    public static final int ATTACK_3_TICKS = 20;          // 1.0000s
    public static final int ATTACK_3_DAMAGE_TICK = 9;     // 0.4600s (User spec)
    public static final int ATTACK_3_LINK_TICK = 13;      // Frame to chain into Attack 4
    public static final int ATTACK_4_TICKS = 20;          // 1.0000s
    public static final int ATTACK_4_DAMAGE_TICK = 12;    // 0.6000s (User spec)
    public static final int ATTACK_4_RECOVERY_TICKS = 24; // Enforced recovery freeze after finisher
    public static final int KICK_TICKS = 20;              // 1.0000s
    public static final int KICK_DAMAGE_TICK = 12;        // 0.6000s (User spec contact frame)
    public static final int KICK_WHIFF_COOLDOWN = 35;      // 1.7500s vulnerability freeze on whiff
    public static final int KICK_SUCCESS_COOLDOWN = 10;    // 0.5000s recovery on successful shield break
    public static final int STAB_WINDUP_TICKS = 10;       // 0.5000s (Telegraph & tracking)
    public static final int STAB_CHARGE_MAX_TICKS = 25;   // 1.2500s max sprint duration
    public static final int STAB_THRUST_TICKS = 15;       // 0.7500s
    public static final int STAB_THRUST_DAMAGE_TICK = 4;  // 0.2000s (User spec contact frame)
    public static final int SHEATHE_TICKS = 30;           // 1.5000s
    public static final int SHEATHE_INSERT_TICK = 20;     // Frame where sword enters sheathe
    public static final int SWORD_TO_XBOW_TICKS = 21;     // 1.0417s
    public static final int SWORD_TO_XBOW_SWAP_TICK = 10; // Frame where xbow becomes active
    public static final int SHOOT_XBOW_TICKS = 32;        // 1.6000s (Fires at 12, 18, 24)
    public static final int XBOW_TO_SWORD_TICKS = 20;     // 1.0000s
    public static final int XBOW_TO_SWORD_SWAP_TICK = 10; // Frame where xbow holsters back to sword
    public static final int BLOCK_TICKS = 11;             // 0.5417s (Active deflection 2..8)

    // Directional attack arcs in degrees (frontal cone angle)
    public static final float ATTACK_1_ARC_DEGREES = 110.0F;
    public static final float ATTACK_2_ARC_DEGREES = 150.0F;
    public static final float ATTACK_3_ARC_DEGREES = 80.0F;
    public static final float ATTACK_4_ARC_DEGREES = 40.0F;
    public static final float STAB_ARC_DEGREES = 40.0F;

    private static final EntityDataAccessor<Byte> ACTION_STATE =
            SynchedEntityData.defineId(PillagerEliteEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Boolean> SWORD_DRAWN =
            SynchedEntityData.defineId(PillagerEliteEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> CROSSBOW_DRAWN =
            SynchedEntityData.defineId(PillagerEliteEntity.class, EntityDataSerializers.BOOLEAN);

    // Animation definitions
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation UNSHEATHE_SWORD = RawAnimation.begin().thenPlay("unsheathe_sword");
    private static final RawAnimation STANCE_TWO_HANDED = RawAnimation.begin().thenLoop("stance_two_handed");
    private static final RawAnimation ATTACK_1 = RawAnimation.begin().thenPlay("attack_1");
    private static final RawAnimation ATTACK_2 = RawAnimation.begin().thenPlay("attack_2");
    private static final RawAnimation ATTACK_3 = RawAnimation.begin().thenPlay("attack_3");
    private static final RawAnimation ATTACK_4 = RawAnimation.begin().thenPlay("attack_4");
    private static final RawAnimation KICK = RawAnimation.begin().thenPlay("kick");
    private static final RawAnimation STAB_WINDUP = RawAnimation.begin().thenPlay("stab_windup");
    private static final RawAnimation STAB_CHARGE = RawAnimation.begin().thenLoop("stab_charge");
    private static final RawAnimation STAB_THRUST = RawAnimation.begin().thenPlay("stab_thrust");
    private static final RawAnimation SHEATHE_SWORD = RawAnimation.begin().thenPlay("sheathe_sword");
    private static final RawAnimation SWORD_TO_CROSSBOW = RawAnimation.begin().thenPlay("sword_to_crossbow");
    private static final RawAnimation STANCE_CROSSBOW = RawAnimation.begin().thenLoop("stance_crossbow");
    private static final RawAnimation SHOOT_CROSSBOW = RawAnimation.begin().thenPlay("shoot_crossbow");
    private static final RawAnimation CROSSBOW_TO_SWORD = RawAnimation.begin().thenPlay("crossbow_to_sword");
    private static final RawAnimation BLOCK = RawAnimation.begin().thenPlay("block");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // State machine timers & counters
    private int actionTicks = 0;
    private byte lastActionState = ACTION_IDLE;
    private int attackCooldownTicks = 0;
    private int stabCooldownTicks = 0;
    private int crossbowCooldownTicks = 0;
    private int blockCooldownTicks = 0;
    private int outOfCombatTicks = 0;
    private int spacedOutTicks = 0;
    private int aimTicks = 0;
    private boolean kickConnected = false;

    // Crossbow stock (initialized to 3 when switching to crossbow)
    private int crossbowStock = 0;

    public PillagerEliteEntity(EntityType<? extends AbstractIllager> entityType, Level level) {
        super(entityType, level);
        this.xpReward = 20;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 50.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.22D)
                .add(Attributes.ATTACK_DAMAGE, 8.0D) // Base damage 8 as requested
                .add(Attributes.ARMOR, 8.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.75D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ACTION_STATE, ACTION_IDLE);
        builder.define(SWORD_DRAWN, false);
        builder.define(CROSSBOW_DRAWN, false);
    }

    public byte getActionState() {
        return this.entityData.get(ACTION_STATE);
    }

    public void setActionState(byte state) {
        this.entityData.set(ACTION_STATE, state);
    }

    public boolean isSwordDrawn() {
        return this.entityData.get(SWORD_DRAWN);
    }

    public void setSwordDrawn(boolean drawn) {
        this.entityData.set(SWORD_DRAWN, drawn);
    }

    public boolean isCrossbowDrawn() {
        return this.entityData.get(CROSSBOW_DRAWN);
    }

    public void setCrossbowDrawn(boolean drawn) {
        this.entityData.set(CROSSBOW_DRAWN, drawn);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));

        // Idle goals: strictly suppressed during combat so they never hijack rotation or movement
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 1.0D) {
            @Override
            public boolean canUse() {
                return !PillagerEliteEntity.this.isAggressive() && PillagerEliteEntity.this.getTarget() == null && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !PillagerEliteEntity.this.isAggressive() && PillagerEliteEntity.this.getTarget() == null && super.canContinueToUse();
            }
        });
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F) {
            @Override
            public boolean canUse() {
                return !PillagerEliteEntity.this.isAggressive() && PillagerEliteEntity.this.getTarget() == null && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !PillagerEliteEntity.this.isAggressive() && PillagerEliteEntity.this.getTarget() == null && super.canContinueToUse();
            }
        });
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this) {
            @Override
            public boolean canUse() {
                return !PillagerEliteEntity.this.isAggressive() && PillagerEliteEntity.this.getTarget() == null && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !PillagerEliteEntity.this.isAggressive() && PillagerEliteEntity.this.getTarget() == null && super.canContinueToUse();
            }
        });

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

        if (this.attackCooldownTicks > 0) this.attackCooldownTicks--;
        if (this.stabCooldownTicks > 0) this.stabCooldownTicks--;
        if (this.crossbowCooldownTicks > 0) this.crossbowCooldownTicks--;
        if (this.blockCooldownTicks > 0) this.blockCooldownTicks--;

        if (!this.level().isClientSide) {
            LivingEntity target = this.getTarget();
            boolean hasTarget = target != null && target.isAlive();
            this.setAggressive(hasTarget);

            byte state = getActionState();

            // 1. Proactive Projectile Sensing for Sword Deflection
            if (state == ACTION_STANCE_SWORD && isSwordDrawn() && !isCrossbowDrawn() && this.blockCooldownTicks <= 0) {
                checkIncomingProjectilesForBlock();
                state = getActionState(); // update in case block triggered
            }

            // 2. Target presence & combat entry/exit
            if (hasTarget) {
                this.outOfCombatTicks = 0;
                this.getLookControl().setLookAt(target, 30.0F, 30.0F);

                if (!isSwordDrawn() && state == ACTION_IDLE) {
                    // Start unsheathing sword
                    setActionState(ACTION_UNSHEATHE);
                    this.actionTicks = 0;
                }
            } else {
                if (isSwordDrawn() && (state == ACTION_STANCE_SWORD || state == ACTION_IDLE)) {
                    this.outOfCombatTicks++;
                    if (this.outOfCombatTicks >= 60) { // 3 seconds after combat ends
                        setActionState(ACTION_SHEATHE);
                        this.actionTicks = 0;
                    }
                }
            }

            // 3. Action State Machine Execution
            state = getActionState();
            switch (state) {
                case ACTION_UNSHEATHE:
                    this.actionTicks++;
                    if (this.actionTicks >= UNSHEATHE_GRAB_TICK && !isSwordDrawn()) {
                        setSwordDrawn(true);
                        this.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.2F);
                    }
                    if (this.actionTicks >= UNSHEATHE_TICKS) {
                        setSwordDrawn(true);
                        setActionState(ACTION_STANCE_SWORD);
                        this.actionTicks = 0;
                    }
                    break;

                case ACTION_STANCE_SWORD:
                    if (hasTarget) {
                        double distSq = this.distanceToSqr(target);
                        double meleeReach = 3.2D;
                        boolean isEngaged = MeleeEngagementManager.requestSlot(target, this, true);

                        if (!isEngaged) {
                            // Reserve melee mob: hold ground 5-6 blocks away to prevent player lynching
                            if (distSq <= meleeReach * meleeReach && this.attackCooldownTicks <= 0) {
                                // Melee punish on close proximity: player entered striking reach!
                                initiateMeleeAttack(target);
                            } else if (distSq > 36.0D) {
                                // Beyond 6 blocks: move toward target to establish 5-6 block perimeter
                                this.spacedOutTicks = 0;
                                this.getNavigation().moveTo(target, 1.0D);
                            } else {
                                // Within 6 blocks (holding perimeter 5-6m & close buffer 3.2-5m):
                                // Stand ground! Never back away if player advances.
                                this.spacedOutTicks = 0;
                                this.getNavigation().stop();
                                this.getLookControl().setLookAt(target, 35.0F, 35.0F);
                            }
                        } else {
                            // Active duelist engagement
                            if (distSq <= meleeReach * meleeReach && this.attackCooldownTicks <= 0) {
                                // Close range: Start Attack 1 or 50% shield-punish Kick
                                initiateMeleeAttack(target);
                            } else if (distSq >= 4.5D * 4.5D && distSq <= 12.0D * 12.0D && this.stabCooldownTicks <= 0 && this.hasLineOfSight(target)) {
                                // Option 2: Immediate Charged Stab gap-closer!
                                this.spacedOutTicks = 0;
                                setActionState(ACTION_STAB_WINDUP);
                                this.actionTicks = 0;
                                this.getNavigation().stop();
                                this.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.2F);
                            } else if (distSq >= 7.0D * 7.0D && distSq <= 16.0D * 16.0D && this.hasLineOfSight(target)) {
                                // Player spacing at distance (7m - 16m)
                                this.spacedOutTicks++;
                                if (this.spacedOutTicks >= 25 && this.crossbowCooldownTicks <= 0) {
                                    // Trigger repeating crossbow distance punish!
                                    this.spacedOutTicks = 0;
                                    setActionState(ACTION_SWORD_TO_XBOW);
                                    this.actionTicks = 0;
                                    this.getNavigation().stop();
                                } else {
                                    // Continue closing distance
                                    this.getNavigation().moveTo(target, 1.25D);
                                }
                            } else {
                                this.spacedOutTicks = 0;
                                this.getNavigation().moveTo(target, 1.25D);
                            }
                        }
                    }
                    break;

                case ACTION_ATTACK_1:
                    this.actionTicks++;
                    if (target != null) {
                        faceTarget(target, 60.0F, 60.0F);
                    }
                    // Committed slowdown during slash
                    this.setDeltaMovement(this.getDeltaMovement().multiply(0.3D, 1.0D, 0.3D));

                    // Contact / Damage tick at 0.4s (Tick 8) - 110 degree arc
                    if (this.actionTicks == ATTACK_1_DAMAGE_TICK) {
                        executeAttackDamage(1.0F, 0.45D, 0.85F, ATTACK_1_ARC_DEGREES, false);
                    }

                    // Combo Extension check: only distance check as directed by user!
                    if (this.actionTicks >= ATTACK_1_LINK_TICK) {
                        if (hasTarget && this.distanceToSqr(target) <= 3.5D * 3.5D) {
                            faceTarget(target, 60.0F, 60.0F);
                            this.actionTicks = 0;
                            // Option A: 30% chance to punish active shield blocking with a kick
                            if (isTargetActivelyBlocking(target) && this.random.nextFloat() < 0.30F) {
                                this.kickConnected = false;
                                setActionState(ACTION_KICK);
                            } else {
                                setActionState(ACTION_ATTACK_2);
                                Vec3 fwd = this.getLookAngle().multiply(0.18D, 0.0D, 0.18D);
                                this.setDeltaMovement(this.getDeltaMovement().add(fwd));
                            }
                            break;
                        }
                    }

                    // If combo did not extend (target retreated or died), finish full recovery animation
                    if (this.actionTicks >= ATTACK_1_TICKS) {
                        setActionState(ACTION_STANCE_SWORD);
                        this.actionTicks = 0;
                        this.attackCooldownTicks = 16;
                    }
                    break;

                case ACTION_ATTACK_2:
                    this.actionTicks++;
                    if (target != null) {
                        faceTarget(target, 60.0F, 60.0F);
                    }
                    this.setDeltaMovement(this.getDeltaMovement().multiply(0.3D, 1.0D, 0.3D));

                    // Contact / Damage tick at 0.47s (Tick 9) - 150 degree arc
                    if (this.actionTicks == ATTACK_2_DAMAGE_TICK) {
                        executeAttackDamage(1.0F, 0.50D, 0.90F, ATTACK_2_ARC_DEGREES, false);
                    }

                    // Combo Extension check: only distance check!
                    if (this.actionTicks >= ATTACK_2_LINK_TICK) {
                        if (hasTarget && this.distanceToSqr(target) <= 3.5D * 3.5D) {
                            faceTarget(target, 60.0F, 60.0F);
                            this.actionTicks = 0;
                            // Option A: 30% chance to punish active shield blocking with a kick
                            if (isTargetActivelyBlocking(target) && this.random.nextFloat() < 0.30F) {
                                this.kickConnected = false;
                                setActionState(ACTION_KICK);
                            } else {
                                setActionState(ACTION_ATTACK_3);
                                Vec3 fwd = this.getLookAngle().multiply(0.18D, 0.0D, 0.18D);
                                this.setDeltaMovement(this.getDeltaMovement().add(fwd));
                            }
                            break;
                        }
                    }

                    // If combo did not extend, finish full recovery
                    if (this.actionTicks >= ATTACK_2_TICKS) {
                        setActionState(ACTION_STANCE_SWORD);
                        this.actionTicks = 0;
                        this.attackCooldownTicks = 16;
                    }
                    break;

                case ACTION_ATTACK_3:
                    this.actionTicks++;
                    if (target != null) {
                        faceTarget(target, 60.0F, 60.0F);
                    }
                    this.setDeltaMovement(this.getDeltaMovement().multiply(0.3D, 1.0D, 0.3D));

                    // Contact / Damage tick at 0.46s (Tick 9) - 80 degree arc
                    if (this.actionTicks == ATTACK_3_DAMAGE_TICK) {
                        executeAttackDamage(1.125F, 0.55D, 0.95F, ATTACK_3_ARC_DEGREES, false);
                    }

                    // Combo Extension check: only distance check!
                    if (this.actionTicks >= ATTACK_3_LINK_TICK) {
                        if (hasTarget && this.distanceToSqr(target) <= 3.5D * 3.5D) {
                            faceTarget(target, 60.0F, 60.0F);
                            this.actionTicks = 0;
                            // Option A: 30% chance to punish active shield blocking with a kick
                            if (isTargetActivelyBlocking(target) && this.random.nextFloat() < 0.30F) {
                                this.kickConnected = false;
                                setActionState(ACTION_KICK);
                            } else {
                                setActionState(ACTION_ATTACK_4);
                                Vec3 fwd = this.getLookAngle().multiply(0.22D, 0.0D, 0.22D);
                                this.setDeltaMovement(this.getDeltaMovement().add(fwd));
                            }
                            break;
                        }
                    }

                    // If combo did not extend, finish full recovery
                    if (this.actionTicks >= ATTACK_3_TICKS) {
                        setActionState(ACTION_STANCE_SWORD);
                        this.actionTicks = 0;
                        this.attackCooldownTicks = 16;
                    }
                    break;

                case ACTION_ATTACK_4:
                    this.actionTicks++;
                    if (target != null) {
                        faceTarget(target, 60.0F, 60.0F);
                    }
                    this.setDeltaMovement(this.getDeltaMovement().multiply(0.25D, 1.0D, 0.25D));

                    // Contact / Damage tick at 0.60s (Tick 12) - Finisher! - 40 degree arc
                    if (this.actionTicks == ATTACK_4_DAMAGE_TICK) {
                        executeAttackDamage(1.50F, 1.20D, 0.75F, ATTACK_4_ARC_DEGREES, true);
                    }

                    // Attack 4 is the finisher: always finishes full 20 ticks + recovery freeze
                    if (this.actionTicks >= ATTACK_4_TICKS) {
                        setActionState(ACTION_STANCE_SWORD);
                        this.actionTicks = 0;
                        this.attackCooldownTicks = ATTACK_4_RECOVERY_TICKS;
                    }
                    break;

                case ACTION_KICK:
                    this.actionTicks++;
                    if (target != null) {
                        faceTarget(target, 60.0F, 60.0F);
                    }
                    this.setDeltaMovement(this.getDeltaMovement().multiply(0.3D, 1.0D, 0.3D));

                    // Contact / Damage tick at 0.60s (Tick 12)
                    if (this.actionTicks == KICK_DAMAGE_TICK) {
                        executeKickAttack();
                    }

                    if (this.actionTicks >= KICK_TICKS) {
                        setActionState(ACTION_STANCE_SWORD);
                        this.actionTicks = 0;
                        if (this.kickConnected) {
                            this.attackCooldownTicks = KICK_SUCCESS_COOLDOWN; // 10 ticks
                        } else {
                            this.attackCooldownTicks = KICK_WHIFF_COOLDOWN;   // 35 ticks vulnerability freeze!
                        }
                    }
                    break;

                case ACTION_STAB_WINDUP:
                    this.actionTicks++;
                    if (target != null) {
                        faceTarget(target, 45.0F, 45.0F);
                    }
                    // 80% slowdown while telegraphing
                    this.setDeltaMovement(this.getDeltaMovement().multiply(0.2D, 1.0D, 0.2D));

                    if (this.actionTicks >= STAB_WINDUP_TICKS) {
                        setActionState(ACTION_STAB_CHARGE);
                        this.actionTicks = 0;
                        this.playSound(SoundEvents.RAVAGER_ROAR, 0.6F, 1.6F);
                    }
                    break;

                case ACTION_STAB_CHARGE:
                    this.actionTicks++;
                    if (target != null && target.isAlive()) {
                        // Steerable charge: turn rate capped at 6.0 degrees per tick to prevent 30G missile lock-on
                        double dX = target.getX() - this.getX();
                        double dZ = target.getZ() - this.getZ();
                        float desiredYaw = (float) (Math.atan2(dZ, dX) * (180.0D / Math.PI)) - 90.0F;
                        float yawDiff = Mth.wrapDegrees(desiredYaw - this.getYRot());
                        float maxTurn = 6.0F; // degrees per tick max
                        float turn = Mth.clamp(yawDiff, -maxTurn, maxTurn);
                        float newYaw = this.getYRot() + turn;
                        this.setYRot(newYaw);
                        this.yBodyRot = newYaw;
                        this.yHeadRot = newYaw;

                        // Steerable dash in heading direction (0.46 blocks/tick ~ 9.2 blocks/s)
                        Vec3 fwd = Vec3.directionFromRotation(0.0F, newYaw);
                        double speed = 0.46D;
                        this.setDeltaMovement(fwd.x * speed, this.getDeltaMovement().y, fwd.z * speed);

                        // Interception check: within 3.0m and in front cone (dot > 0.60)
                        double distSq = this.distanceToSqr(target);
                        Vec3 look = this.getLookAngle();
                        Vec3 toTarget = target.position().subtract(this.position()).normalize();
                        double dot = look.x * toTarget.x + look.z * toTarget.z;

                        if (distSq <= 3.0D * 3.0D && dot > 0.60D) {
                            // Intercepted! Trigger thrust finisher
                            setActionState(ACTION_STAB_THRUST);
                            this.actionTicks = 0;
                            Vec3 thrustImpulse = this.getLookAngle().multiply(0.25D, 0.0D, 0.25D);
                            this.setDeltaMovement(this.getDeltaMovement().add(thrustImpulse));
                            break;
                        }
                    }

                    // Charge expired without hitting target (player dodged or ran sideways)
                    if (this.actionTicks >= STAB_CHARGE_MAX_TICKS) {
                        setActionState(ACTION_STANCE_SWORD);
                        this.actionTicks = 0;
                        this.attackCooldownTicks = 20; // 1.0s whiff recovery freeze
                        this.stabCooldownTicks = 160;  // 8.0s cooldown
                        this.playSound(SoundEvents.PLAYER_ATTACK_WEAK, 1.0F, 0.8F);
                    }
                    break;

                case ACTION_STAB_THRUST:
                    this.actionTicks++;
                    // Deceleration during thrust follow-through
                    this.setDeltaMovement(this.getDeltaMovement().multiply(0.25D, 1.0D, 0.25D));

                    // Contact / Damage tick at 0.20s (Tick 4)
                    if (this.actionTicks == STAB_THRUST_DAMAGE_TICK) {
                        executeThrustDamage();
                    }

                    if (this.actionTicks >= STAB_THRUST_TICKS) {
                        setActionState(ACTION_STANCE_SWORD);
                        this.actionTicks = 0;
                        this.attackCooldownTicks = 12; // 0.6s endlag
                        this.stabCooldownTicks = 160;  // 8.0s cooldown
                    }
                    break;

                case ACTION_BLOCK:
                    this.actionTicks++;
                    this.setDeltaMovement(this.getDeltaMovement().multiply(0.2D, 1.0D, 0.2D));
                    if (this.actionTicks >= BLOCK_TICKS) {
                        setActionState(ACTION_STANCE_SWORD);
                        this.actionTicks = 0;
                        this.blockCooldownTicks = 40; // 2s cooldown between deflections
                    }
                    break;

                case ACTION_SWORD_TO_XBOW:
                    this.actionTicks++;
                    if (this.actionTicks >= SWORD_TO_XBOW_SWAP_TICK && !isCrossbowDrawn()) {
                        setCrossbowDrawn(true);
                        this.playSound(SoundEvents.CROSSBOW_LOADING_START.value(), 1.0F, 1.3F);
                    }
                    if (this.actionTicks >= SWORD_TO_XBOW_TICKS) {
                        setCrossbowDrawn(true);
                        this.crossbowStock = 3; // Initialize 3-stock upon entering crossbow stance
                        this.aimTicks = 0;
                        setActionState(ACTION_STANCE_XBOW);
                        this.actionTicks = 0;
                    }
                    break;

                case ACTION_STANCE_XBOW:
                    if (hasTarget) {
                        this.getNavigation().stop();
                        faceTarget(target, 60.0F, 60.0F);
                        double distSq = this.distanceToSqr(target);

                        // If player rushes aggressively into close range (< 4.5m), immediately abort to sword
                        if (distSq < 4.5D * 4.5D) {
                            this.crossbowStock = 0;
                            setActionState(ACTION_XBOW_TO_SWORD);
                            this.actionTicks = 0;
                            break;
                        }

                        // Maintain spacing: stand ground or back away if too close
                        if (distSq < 7.0D * 7.0D) {
                            Vec3 away = this.position().subtract(target.position()).normalize();
                            this.setDeltaMovement(this.getDeltaMovement().add(away.x * 0.05D, 0.0D, away.z * 0.05D));
                        }

                        this.aimTicks++;
                        // Aim lock delay (12 ticks / 0.6s) before firing burst
                        if (this.aimTicks >= 12 && this.hasLineOfSight(target)) {
                            this.aimTicks = 0;
                            setActionState(ACTION_SHOOT_XBOW);
                            this.actionTicks = 0;
                        }
                    } else {
                        // Target lost while in crossbow stance
                        this.crossbowStock = 0;
                        setActionState(ACTION_XBOW_TO_SWORD);
                        this.actionTicks = 0;
                    }
                    break;

                case ACTION_SHOOT_XBOW:
                    this.actionTicks++;
                    this.getNavigation().stop();
                    if (target != null) {
                        faceTarget(target, 60.0F, 60.0F);
                    }
                    // Stop retreat while actively firing bolts
                    this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);

                    // 3-bolt burst at ticks 12, 18, and 24
                    if (this.actionTicks == 12 || this.actionTicks == 18 || this.actionTicks == 24) {
                        fireRepeatingBolt(target);
                    }

                    // On completion of shooting animation (Tick 32 / 1.6s)
                    if (this.actionTicks >= SHOOT_XBOW_TICKS) {
                        // User specification: deplete a randint(1, 3) from stock.
                        // If consumed >= stock: stock = 0 and crossbow stance ends -> return to foot chase.
                        int consumed = this.random.nextInt(3) + 1; // 1, 2, or 3
                        if (consumed >= this.crossbowStock) {
                            this.crossbowStock = 0;
                            this.crossbowCooldownTicks = 160; // 8s cooldown before next crossbow switch
                            setActionState(ACTION_XBOW_TO_SWORD);
                            this.actionTicks = 0;
                        } else {
                            this.crossbowStock -= consumed;
                            this.aimTicks = 0;
                            setActionState(ACTION_STANCE_XBOW);
                            this.actionTicks = 0;
                        }
                    }
                    break;

                case ACTION_XBOW_TO_SWORD:
                    this.actionTicks++;
                    if (this.actionTicks >= XBOW_TO_SWORD_SWAP_TICK && isCrossbowDrawn()) {
                        setCrossbowDrawn(false);
                        this.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.1F);
                    }
                    if (this.actionTicks >= XBOW_TO_SWORD_TICKS) {
                        setCrossbowDrawn(false);
                        setActionState(ACTION_STANCE_SWORD);
                        this.actionTicks = 0;
                        // Return immediately to foot chase
                        if (hasTarget) {
                            this.getNavigation().moveTo(target, 1.25D);
                        }
                    }
                    break;

                case ACTION_SHEATHE:
                    this.actionTicks++;
                    if (this.actionTicks >= SHEATHE_INSERT_TICK && isSwordDrawn()) {
                        setSwordDrawn(false);
                        this.playSound(SoundEvents.ARMOR_EQUIP_GENERIC.value(), 1.0F, 0.9F);
                    }
                    if (this.actionTicks >= SHEATHE_TICKS) {
                        setSwordDrawn(false);
                        setActionState(ACTION_IDLE);
                        this.actionTicks = 0;
                    }
                    break;

                default:
                    break;
            }
        }
    }

    /**
     * Proactively scans for incoming projectile trajectories within an 8-block cone.
     * Enters ACTION_BLOCK if an arrow is heading toward the elite.
     */
    private void checkIncomingProjectilesForBlock() {
        AABB scanBox = this.getBoundingBox().inflate(8.0D);
        List<Projectile> projectiles = this.level().getEntitiesOfClass(Projectile.class, scanBox,
                p -> p.getOwner() != this && p.getDeltaMovement().lengthSqr() > 0.08D);

        Vec3 eliteCenter = this.position().add(0, this.getEyeHeight() * 0.5D, 0);

        for (Projectile proj : projectiles) {
            Vec3 toElite = eliteCenter.subtract(proj.position());
            double dist = toElite.length();
            if (dist <= 7.0D) {
                Vec3 projVel = proj.getDeltaMovement().normalize();
                double headingDot = projVel.dot(toElite.normalize());
                // If projectile is heading directly toward elite (within ~40 degrees)
                if (headingDot > 0.70D) {
                    this.getLookControl().setLookAt(proj, 60.0F, 60.0F);
                    setActionState(ACTION_BLOCK);
                    this.actionTicks = 0;
                    this.getNavigation().stop();
                    break;
                }
            }
        }
    }

    /**
     * Intercepts incoming damage. If projectile damage arrives while blocking (or during deflect window),
     * negates 100% of damage, reflects the projectile, and plays metallic sparks.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            Entity projEntity = source.getDirectEntity();
            byte state = getActionState();

            // Deflect if in ACTION_BLOCK or if sword is drawn and facing threat
            boolean isBlocking = (state == ACTION_BLOCK && this.actionTicks >= 2 && this.actionTicks <= 8);

            if (!isBlocking && state == ACTION_STANCE_SWORD && isSwordDrawn() && !isCrossbowDrawn() && this.blockCooldownTicks <= 0) {
                // Reactive block trigger
                isBlocking = true;
                setActionState(ACTION_BLOCK);
                this.actionTicks = 3;
            }

            if (isBlocking && projEntity != null) {
                Vec3 look = this.getLookAngle();
                Vec3 toProj = projEntity.position().subtract(this.position()).normalize();
                double facingDot = look.x * toProj.x + look.z * toProj.z;

                // Within frontal 180-degree arc
                if (facingDot > -0.2D) {
                    // Task 1: Discard projectile on impact matching Warrior shield
                    if (projEntity instanceof Projectile projectile) {
                        projectile.discard();
                    }

                    if (this.level() instanceof ServerLevel serverLevel) {
                        serverLevel.sendParticles(ParticleTypes.CRIT,
                                projEntity.getX(), projEntity.getY(), projEntity.getZ(),
                                10, 0.2, 0.2, 0.2, 0.15);
                    }
                    this.playSound(SoundEvents.ANVIL_LAND, 0.7F, 1.8F);
                    this.playSound(SoundEvents.SHIELD_BLOCK, 1.0F, 1.0F);
                    this.blockCooldownTicks = 40;
                    return false; // Negate damage
                }
            }
        }

        return super.hurt(source, amount);
    }

    /**
     * Fires a rapid repeater bolt from the crossbow towards the target.
     */
    private void fireRepeatingBolt(LivingEntity target) {
        if (target == null) return;

        double dX = target.getX() - this.getX();
        double dY = target.getY(0.3333333333333333D) - (this.getY() + 1.35D);
        double dZ = target.getZ() - this.getZ();
        double dDist = Math.sqrt(dX * dX + dZ * dZ);

        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, new ItemStack(Items.ARROW), 1.0F, null);
        arrow.setPos(this.getX(), this.getY() + 1.35D, this.getZ());
        arrow.shoot(dX, dY + dDist * 0.08D, dZ, 2.4F, 2.0F);
        arrow.setBaseDamage(3.5D); // Rapid repeater bolt damage

        this.level().addFreshEntity(arrow);
        this.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0F, 1.25F);
    }

    /**
     * Executes melee damage in a 3.5m frontal arc for each combo attack stage.
     * The frontal arc check (dot product > 0.15 / 100-degree cone) is strictly preserved
     * so players can side-strafe attacks and make them miss, while combo progression continues
     * based on distance.
     */
    private void executeAttackDamage(float damageMultiplier, double knockbackStrength, float pitch, float arcDegrees, boolean isFinisher) {
        double reach = 3.5D;
        Vec3 look = this.getLookAngle();
        Vec3 origin = this.position().add(0, 1.2, 0);

        AABB attackBox = this.getBoundingBox().inflate(reach, 1.5D, reach);
        List<LivingEntity> potentialTargets = this.level().getEntitiesOfClass(LivingEntity.class, attackBox,
                e -> e != this && e.isAlive() && !this.isAlliedTo(e));

        float baseDamage = (float) this.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float damage = baseDamage * damageMultiplier;
        boolean hitAny = false;
        double minDot = Math.cos(Math.toRadians(arcDegrees * 0.5F));

        for (LivingEntity victim : potentialTargets) {
            Vec3 toTarget = victim.position().add(0, victim.getEyeHeight() * 0.5, 0).subtract(origin);
            if (toTarget.lengthSqr() <= reach * reach) {
                // Frontal arc check: dot product with look vector > minDot for specified arc degrees
                Vec3 dir = toTarget.normalize();
                double dot = look.x * dir.x + look.z * dir.z;
                if (dot > minDot) {
                    DamageSource source = this.damageSources().mobAttack(this);
                    if (victim.hurt(source, damage)) {
                        hitAny = true;
                        victim.knockback(knockbackStrength, -dir.x, -dir.z);
                    }
                }
            }
        }

        if (this.level() instanceof ServerLevel serverLevel) {
            double px = this.getX() + look.x * 1.5D;
            double py = this.getY() + 1.2D;
            double pz = this.getZ() + look.z * 1.5D;
            serverLevel.sendParticles(ParticleTypes.SWEEP_ATTACK, px, py, pz, isFinisher ? 2 : 1, 0.0, 0.0, 0.0, 0.0);
            if (isFinisher && hitAny) {
                serverLevel.sendParticles(ParticleTypes.CRIT, px, py, pz, 12, 0.3, 0.3, 0.3, 0.2);
            }
        }

        if (hitAny) {
            this.playSound(isFinisher ? SoundEvents.PLAYER_ATTACK_KNOCKBACK : SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, pitch);
        } else {
            this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, pitch);
        }
    }

    /**
     * Aligns body, head, and eyes directly toward the target to maintain frontality.
     */
    private void faceTarget(LivingEntity target, float maxHeadRot, float maxHeadPitch) {
        if (target == null) return;
        double dX = target.getX() - this.getX();
        double dZ = target.getZ() - this.getZ();
        float targetYaw = (float) (Math.atan2(dZ, dX) * (180.0D / Math.PI)) - 90.0F;
        this.setYRot(targetYaw);
        this.yBodyRot = targetYaw;
        this.yHeadRot = targetYaw;
        this.getLookControl().setLookAt(target, maxHeadRot, maxHeadPitch);
    }

    /**
     * Checks if the target is actively blocking using a shield.
     */
    private boolean isTargetActivelyBlocking(LivingEntity target) {
        if (target == null) return false;
        return target.isBlocking()
                || (target instanceof PillagerWarriorEntity warrior && warrior.isShieldRaised());
    }

    /**
     * Initiates an initial melee attack against a target in reach.
     * If the target is actively blocking with a shield, there is a 30% chance
     * to perform an offensive shield-punishing kick instead of standard Attack 1.
     */
    private void initiateMeleeAttack(LivingEntity target) {
        this.spacedOutTicks = 0;
        this.actionTicks = 0;
        this.kickConnected = false;
        this.getNavigation().stop();
        faceTarget(target, 60.0F, 60.0F);

        if (isTargetActivelyBlocking(target) && this.random.nextFloat() < 0.30F) {
            setActionState(ACTION_KICK);
        } else {
            setActionState(ACTION_ATTACK_1);
        }
    }

    /**
     * Executes the shield-punishing kick attack at Tick 12 (0.60s).
     * If the target is actively blocking with a shield:
     * - Disables the shield for 5.0s (100 ticks).
     * - Deals 1.0 damage.
     * - Knocks back the target 4 blocks.
     * If the player dropped their shield before contact (or dodged):
     * - Deals 0 damage and 0 knockback.
     * - Leaves the Elite vulnerable with an extended 35-tick recovery freeze.
     */
    private void executeKickAttack() {
        double reach = 3.5D;
        Vec3 look = this.getLookAngle();
        Vec3 origin = this.position().add(0, 1.2, 0);

        AABB attackBox = this.getBoundingBox().inflate(reach, 1.5D, reach);
        List<LivingEntity> potentialTargets = this.level().getEntitiesOfClass(LivingEntity.class, attackBox,
                e -> e != this && e.isAlive() && !this.isAlliedTo(e));

        boolean hitShield = false;

        for (LivingEntity victim : potentialTargets) {
            Vec3 toTarget = victim.position().add(0, victim.getEyeHeight() * 0.5, 0).subtract(origin);
            if (toTarget.lengthSqr() <= reach * reach) {
                Vec3 dir = toTarget.normalize();
                double dot = look.x * dir.x + look.z * dir.z;
                if (dot > 0.15D) {
                    boolean isShieldGuarding = victim.isBlocking();
                    if (victim instanceof PillagerWarriorEntity warrior && warrior.isShieldRaised()) {
                        isShieldGuarding = true;
                    }

                    if (isShieldGuarding) {
                        hitShield = true;

                        // 1. Disable Shield
                        if (victim instanceof Player player) {
                            ItemStack activeItem = player.getUseItem();
                            Item shieldItem = activeItem.isEmpty() ? Items.SHIELD : activeItem.getItem();
                            player.getCooldowns().addCooldown(shieldItem, 100);
                            player.stopUsingItem();
                            player.level().broadcastEntityEvent(player, (byte) 30);
                        } else if (victim instanceof PillagerWarriorEntity warrior) {
                            warrior.disableShield(200);
                        }

                        // 2. Inflict 1.0 damage
                        victim.hurt(this.damageSources().mobAttack(this), 1.0F);

                        // 3. Knockback 4 blocks
                        victim.knockback(1.65D, -dir.x, -dir.z);
                        if (victim instanceof ServerPlayer sPlayer) {
                            sPlayer.hurtMarked = true;
                            sPlayer.connection.send(new ClientboundSetEntityMotionPacket(sPlayer));
                        }

                        if (this.level() instanceof ServerLevel serverLevel) {
                            serverLevel.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + 1.0D, victim.getZ(), 15, 0.3, 0.3, 0.3, 0.2);
                        }
                    }
                }
            }
        }

        this.kickConnected = hitShield;

        if (hitShield) {
            this.playSound(SoundEvents.SHIELD_BREAK, 1.0F, 0.9F);
            this.playSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.8F);
        } else {
            // Whiff: player dropped shield before kick landed or moved away
            this.playSound(SoundEvents.PLAYER_ATTACK_WEAK, 0.9F, 1.2F);
        }
    }

    /**
     * Executes the heavy thrust finisher damage at Tick 4 (0.20s).
     * Inflicts 11.0 HP piercing damage and 0.75D knockback in a 3.0m frontal cone (dot > 0.40).
     */
    private void executeThrustDamage() {
        double reach = 3.0D;
        Vec3 look = this.getLookAngle();
        Vec3 origin = this.position().add(0, 1.2, 0);

        AABB attackBox = this.getBoundingBox().inflate(reach, 1.5D, reach);
        List<LivingEntity> potentialTargets = this.level().getEntitiesOfClass(LivingEntity.class, attackBox,
                e -> e != this && e.isAlive() && !this.isAlliedTo(e));

        float damage = 11.0F; // 1.375x base damage (11 HP)
        boolean hitAny = false;
        double minDot = Math.cos(Math.toRadians(STAB_ARC_DEGREES * 0.5F));

        for (LivingEntity victim : potentialTargets) {
            Vec3 toTarget = victim.position().add(0, victim.getEyeHeight() * 0.5, 0).subtract(origin);
            if (toTarget.lengthSqr() <= reach * reach) {
                // Frontal cone check: dot product with look vector > minDot (40 degree cone)
                Vec3 dir = toTarget.normalize();
                double dot = look.x * dir.x + look.z * dir.z;
                if (dot > minDot) {
                    DamageSource source = this.damageSources().mobAttack(this);
                    if (victim.hurt(source, damage)) {
                        hitAny = true;
                        victim.knockback(0.75D, -dir.x, -dir.z);
                        if (victim instanceof ServerPlayer sPlayer) {
                            sPlayer.hurtMarked = true;
                            sPlayer.connection.send(new ClientboundSetEntityMotionPacket(sPlayer));
                        }
                    }
                }
            }
        }

        if (this.level() instanceof ServerLevel serverLevel) {
            double px = this.getX() + look.x * 1.8D;
            double py = this.getY() + 1.2D;
            double pz = this.getZ() + look.z * 1.8D;
            serverLevel.sendParticles(ParticleTypes.CRIT, px, py, pz, 15, 0.3, 0.3, 0.3, 0.2);
        }

        if (hitAny) {
            this.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 0.75F);
        } else {
            this.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 0.8F);
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 4, this::movementPredicate));
        controllers.add(new AnimationController<>(this, "action", 2, this::actionPredicate));
    }

    private PlayState movementPredicate(AnimationState<PillagerEliteEntity> state) {
        byte action = getActionState();

        // During steerable charged sprint, drive the legs with RUN at 2.3x playback speed
        if (action == ACTION_STAB_CHARGE) {
            state.getController().setAnimationSpeed(2.3D);
            return state.setAndContinue(RUN);
        }

        // Neutral 1.0x animation playback speed for all other states
        state.getController().setAnimationSpeed(1.0D);

        boolean isAttacking = (action == ACTION_ATTACK_1 || action == ACTION_ATTACK_2 || action == ACTION_ATTACK_3 || action == ACTION_ATTACK_4 || action == ACTION_KICK
                || action == ACTION_STAB_WINDUP || action == ACTION_STAB_THRUST);

        boolean isMoving = state.isMoving()
                || this.walkAnimation.isMoving()
                || this.walkAnimation.speed() > 0.001F
                || state.getLimbSwingAmount() > 0.001F
                || (this.getDeltaMovement().horizontalDistanceSqr() > 0.00005D)
                || (this.getX() != this.xo || this.getZ() != this.zo);

        if (isMoving && !isAttacking) {
            if (this.isSprinting() || this.isAggressive() || (this.getTarget() != null && this.getTarget().isAlive())) {
                return state.setAndContinue(RUN);
            }
            return state.setAndContinue(WALK);
        }

        // When stationary or during committed attacks:
        if (action == ACTION_STANCE_XBOW || action == ACTION_SHOOT_XBOW) {
            return state.setAndContinue(STANCE_CROSSBOW);
        }
        if (action == ACTION_STANCE_SWORD && isSwordDrawn()) {
            return state.setAndContinue(STANCE_TWO_HANDED);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState actionPredicate(AnimationState<PillagerEliteEntity> state) {
        byte action = getActionState();
        if (action != this.lastActionState) {
            state.getController().forceAnimationReset();
            this.lastActionState = action;
        }

        switch (action) {
            case ACTION_UNSHEATHE:
                return state.setAndContinue(UNSHEATHE_SWORD);
            case ACTION_ATTACK_1:
                return state.setAndContinue(ATTACK_1);
            case ACTION_ATTACK_2:
                return state.setAndContinue(ATTACK_2);
            case ACTION_ATTACK_3:
                return state.setAndContinue(ATTACK_3);
            case ACTION_ATTACK_4:
                return state.setAndContinue(ATTACK_4);
            case ACTION_KICK:
                return state.setAndContinue(KICK);
            case ACTION_STAB_WINDUP:
                return state.setAndContinue(STAB_WINDUP);
            case ACTION_STAB_CHARGE:
                return state.setAndContinue(STAB_CHARGE);
            case ACTION_STAB_THRUST:
                return state.setAndContinue(STAB_THRUST);
            case ACTION_SHEATHE:
                return state.setAndContinue(SHEATHE_SWORD);
            case ACTION_BLOCK:
                return state.setAndContinue(BLOCK);
            case ACTION_SWORD_TO_XBOW:
                return state.setAndContinue(SWORD_TO_CROSSBOW);
            case ACTION_SHOOT_XBOW:
                return state.setAndContinue(SHOOT_CROSSBOW);
            case ACTION_XBOW_TO_SWORD:
                return state.setAndContinue(CROSSBOW_TO_SWORD);
            case ACTION_STANCE_SWORD:
                return state.setAndContinue(STANCE_TWO_HANDED);
            case ACTION_STANCE_XBOW:
                return state.setAndContinue(STANCE_CROSSBOW);
            default:
                state.getController().stop();
                return PlayState.STOP;
        }
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
        LivingEntity oldTarget = this.getTarget();
        if (oldTarget != null && !oldTarget.equals(target)) {
            MeleeEngagementManager.releaseSlot(oldTarget, this);
        }
        super.setTarget(target);
    }

    @Override
    public void die(DamageSource damageSource) {
        super.die(damageSource);
        MeleeEngagementManager.onMobDied(this.getTarget(), this);
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        super.remove(reason);
        MeleeEngagementManager.releaseAll(this);
    }

    @Override
    public void applyRaidBuffs(ServerLevel level, int wave, boolean unused) {
        // Elite raider scaling buffs
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

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putByte("ActionState", getActionState());
        compound.putBoolean("SwordDrawn", isSwordDrawn());
        compound.putBoolean("CrossbowDrawn", isCrossbowDrawn());
        compound.putInt("CrossbowStock", this.crossbowStock);
        compound.putInt("CrossbowCooldown", this.crossbowCooldownTicks);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.contains("ActionState")) {
            setActionState(compound.getByte("ActionState"));
        }
        if (compound.contains("SwordDrawn")) {
            setSwordDrawn(compound.getBoolean("SwordDrawn"));
        }
        if (compound.contains("CrossbowDrawn")) {
            setCrossbowDrawn(compound.getBoolean("CrossbowDrawn"));
        }
        if (compound.contains("CrossbowStock")) {
            this.crossbowStock = compound.getInt("CrossbowStock");
        }
        if (compound.contains("CrossbowCooldown")) {
            this.crossbowCooldownTicks = compound.getInt("CrossbowCooldown");
        }
    }
}
