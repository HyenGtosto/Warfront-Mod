package com.warfront.entity;

import com.warfront.mission.easy.SupplyConvoyMissionHandler;
import com.warfront.spawn.SubregionPatrolManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

/**
 * Supply Wagon Cart (Front convoy wagon).
 *
 * Pulled by 2 harnessed horses in front.
 * Starts from a subregion border and navigates through 3 border waypoints.
 * Pauses for 10 seconds upon reaching each border waypoint, then proceeds to the next.
 * Never stops moving between waypoints as long as pulling horses remain alive.
 * If 3rd border goal is reached, convoy escapes and the mission ends in failure.
 */
public class SupplyWagonCartEntity extends PathfinderMob implements GeoEntity {

    private static final EntityDataAccessor<Boolean> IS_MOVING =
            SynchedEntityData.defineId(SupplyWagonCartEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> HAS_HORSES =
            SynchedEntityData.defineId(SupplyWagonCartEntity.class, EntityDataSerializers.BOOLEAN);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Linked horses and extension wagon
    private UUID leftHorseUuid = null;
    private UUID rightHorseUuid = null;
    private UUID extensionWagonUuid = null;

    // Subregion border goals (3 goals)
    private int originRegionX;
    private int originRegionZ;
    private int originSubX;
    private int originSubZ;

    private int borderGoalsReached = 0;
    private BlockPos currentTargetWaypoint = null;
    private boolean isWaitingAtWaypoint = false;
    private int waitTicksRemaining = 0;
    private int repathCooldown = 0;

    public SupplyWagonCartEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
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
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 100.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.28D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, 128.0D)
                .add(Attributes.ARMOR, 6.0D)
                .add(Attributes.STEP_HEIGHT, 1.25D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(IS_MOVING, false);
        builder.define(HAS_HORSES, true);
    }

    public boolean isMoving() {
        return this.entityData.get(IS_MOVING);
    }

    public void setMoving(boolean moving) {
        this.entityData.set(IS_MOVING, moving);
    }

    public boolean hasHorses() {
        return this.entityData.get(HAS_HORSES);
    }

    public void setHasHorses(boolean hasHorses) {
        this.entityData.set(HAS_HORSES, hasHorses);
    }

    public void setHorses(UUID left, UUID right) {
        this.leftHorseUuid = left;
        this.rightHorseUuid = right;
    }

    public void setExtensionWagon(UUID extensionUuid) {
        this.extensionWagonUuid = extensionUuid;
    }

    public UUID getExtensionWagonUuid() {
        return this.extensionWagonUuid;
    }

    public void setConvoySubregion(int rx, int rz, int sx, int sz) {
        this.originRegionX = rx;
        this.originRegionZ = rz;
        this.originSubX = sx;
        this.originSubZ = sz;
    }

    public int getOriginRegionX() { return originRegionX; }
    public int getOriginRegionZ() { return originRegionZ; }
    public int getOriginSubX() { return originSubX; }
    public int getOriginSubZ() { return originSubZ; }

    @Override
    public void push(Entity other) {
        if (other == null) return;
        UUID otherId = other.getUUID();
        // Do not push against hitched horses or rear trailer wagon
        if (otherId.equals(leftHorseUuid) || otherId.equals(rightHorseUuid) || otherId.equals(extensionWagonUuid)) {
            return;
        }
        super.push(other);
    }

    @Override
    public void dropLeash(boolean broadcastPacket, boolean dropItem) {
        super.dropLeash(broadcastPacket, false); // Never drop lead items
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) {
            this.yBodyRot = this.getYRot();
            this.yHeadRot = this.getYRot();
            return;
        }

        ServerLevel serverLevel = (ServerLevel) this.level();

        // 1. Check and maintain pulling horses
        Horse leftHorse = leftHorseUuid != null && serverLevel.getEntity(leftHorseUuid) instanceof Horse h ? h : null;
        Horse rightHorse = rightHorseUuid != null && serverLevel.getEntity(rightHorseUuid) instanceof Horse h ? h : null;

        boolean canPull = (leftHorse != null && leftHorse.isAlive()) || (rightHorse != null && rightHorse.isAlive());
        this.setHasHorses(canPull);

        boolean moving = this.getDeltaMovement().horizontalDistanceSqr() > 0.0004D;
        this.setMoving(moving);

        if (!canPull) {
            // Cannot move without living horses!
            this.getNavigation().stop();
            this.setDeltaMovement(this.getDeltaMovement().multiply(0.5, 1.0, 0.5));
            return;
        }

        // 2. Position living horses strictly in front of the cart
        updateHarnessedHorse(leftHorse, -0.95D);
        updateHarnessedHorse(rightHorse, 0.95D);

        // 3. Convoy Waypoint Navigation (3 Border Goals)
        tickConvoyNavigation(serverLevel);
    }

    private void updateHarnessedHorse(Horse horse, double sideOffset) {
        if (horse == null || !horse.isAlive()) return;

        float yaw = this.getYRot();
        float rad = yaw * Mth.DEG_TO_RAD;
        double fwdX = -Mth.sin(rad);
        double fwdZ = Mth.cos(rad);
        double rightX = Mth.cos(rad);
        double rightZ = Mth.sin(rad);

        double targetX = this.getX() + fwdX * 3.6D + rightX * sideOffset;
        double targetZ = this.getZ() + fwdZ * 3.6D + rightZ * sideOffset;
        int targetY = this.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.round(targetX), (int) Math.round(targetZ));

        // Lock position and rotations to prevent violent spinning
        horse.setPos(targetX, targetY, targetZ);
        horse.setYRot(yaw);
        horse.setYHeadRot(yaw);
        horse.yBodyRot = yaw;
        horse.yRotO = yaw;
        horse.yBodyRotO = yaw;
        horse.yHeadRotO = yaw;
        horse.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        horse.getNavigation().stop();
    }

    private void tickConvoyNavigation(ServerLevel serverLevel) {
        // Initialize first border waypoint if null
        if (currentTargetWaypoint == null) {
            currentTargetWaypoint = SubregionPatrolManager.pickNextBorderWaypoint(
                    serverLevel, originRegionX, originRegionZ, originSubX, originSubZ, this.blockPosition()
            );
        }

        if (currentTargetWaypoint == null) {
            return;
        }

        double dx = currentTargetWaypoint.getX() + 0.5D - this.getX();
        double dz = currentTargetWaypoint.getZ() + 0.5D - this.getZ();
        double distSq = dx * dx + dz * dz;

        if (isWaitingAtWaypoint) {
            // Reached border goal: Wait 10 seconds (200 ticks)
            this.getNavigation().stop();
            waitTicksRemaining--;
            if (waitTicksRemaining <= 0) {
                isWaitingAtWaypoint = false;
                currentTargetWaypoint = SubregionPatrolManager.pickNextBorderWaypoint(
                        serverLevel, originRegionX, originRegionZ, originSubX, originSubZ, currentTargetWaypoint
                );
                repathCooldown = 0;
            }
        } else {
            // Marching towards border goal: never stop moving
            if (distSq <= 36.0D) { // Within 6 blocks of goal
                borderGoalsReached++;
                if (borderGoalsReached >= 3) {
                    // Reached 3rd border goal -> Convoy escaped! Mission failed!
                    SupplyConvoyMissionHandler.getInstance().onConvoyEscaped(serverLevel, this);
                    this.discard();
                    return;
                }
                isWaitingAtWaypoint = true;
                waitTicksRemaining = 10 * 20; // 10 seconds hold
                this.getNavigation().stop();
            } else {
                if (--repathCooldown <= 0 || this.getNavigation().isDone()) {
                    repathCooldown = 20;
                    int targetY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, currentTargetWaypoint.getX(), currentTargetWaypoint.getZ());
                    this.getNavigation().moveTo(currentTargetWaypoint.getX() + 0.5D, targetY, currentTargetWaypoint.getZ() + 0.5D, 1.0D);
                }
            }
        }
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (leftHorseUuid != null) tag.putUUID("LeftHorse", leftHorseUuid);
        if (rightHorseUuid != null) tag.putUUID("RightHorse", rightHorseUuid);
        if (extensionWagonUuid != null) tag.putUUID("ExtensionWagon", extensionWagonUuid);
        tag.putInt("OriginRegionX", originRegionX);
        tag.putInt("OriginRegionZ", originRegionZ);
        tag.putInt("OriginSubX", originSubX);
        tag.putInt("OriginSubZ", originSubZ);
        tag.putInt("BorderGoalsReached", borderGoalsReached);
        tag.putBoolean("IsWaitingAtWaypoint", isWaitingAtWaypoint);
        tag.putInt("WaitTicksRemaining", waitTicksRemaining);
        if (currentTargetWaypoint != null) {
            tag.putLong("TargetWaypoint", currentTargetWaypoint.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("LeftHorse")) leftHorseUuid = tag.getUUID("LeftHorse");
        if (tag.hasUUID("RightHorse")) rightHorseUuid = tag.getUUID("RightHorse");
        if (tag.hasUUID("ExtensionWagon")) extensionWagonUuid = tag.getUUID("ExtensionWagon");
        originRegionX = tag.getInt("OriginRegionX");
        originRegionZ = tag.getInt("OriginRegionZ");
        originSubX = tag.getInt("OriginSubX");
        originSubZ = tag.getInt("OriginSubZ");
        borderGoalsReached = tag.getInt("BorderGoalsReached");
        isWaitingAtWaypoint = tag.getBoolean("IsWaitingAtWaypoint");
        waitTicksRemaining = tag.getInt("WaitTicksRemaining");
        if (tag.contains("TargetWaypoint")) {
            currentTargetWaypoint = BlockPos.of(tag.getLong("TargetWaypoint"));
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 2, state -> {
            if (this.isMoving()) {
                return state.setAndContinue(RawAnimation.begin().thenLoop("moving"));
            }
            return PlayState.STOP;
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
