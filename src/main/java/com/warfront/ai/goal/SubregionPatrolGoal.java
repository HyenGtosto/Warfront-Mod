package com.warfront.ai.goal;

import com.warfront.region.bridge.SubregionBridge;
import com.warfront.region.bridge.SubregionBridgeManager;
import com.warfront.spawn.SubregionPatrolManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.PathType;

import java.util.EnumSet;

/**
 * AI Goal for Subregion Patrol Squad members:
 *
 * Patrol Lifecycle:
 *   1. March toward the squad's shared target border waypoint (with formation offsets).
 *   2. Upon arriving within holding distance (<= 5 blocks), hold ground for 15 seconds (300 ticks).
 *   3. After 15 seconds, pick another border waypoint at least 48 blocks away and repeat.
 *
 * Combat Interruption & Squad Alerting:
 *   - When any member spots a player, the whole squad engages the player.
 *   - While engaged in combat (mob.getTarget() != null), this goal yields to combat goals.
 *   - When combat finishes, the squad seamlessly resumes its patrol loop.
 */
public class SubregionPatrolGoal extends Goal {

    private final Mob mob;
    private final SubregionPatrolManager.PatrolSquad squad;
    private final int offsetX;
    private final int offsetZ;
    private int repathCooldownTicks = 0;
    private int lookCooldownTicks = 0;
    private int bridgeCheckCooldown = 0;
    private boolean bridgeIntactCached = true;
    private BlockPos lastKnownWaypoint = null;

    public SubregionPatrolGoal(Mob mob, SubregionPatrolManager.PatrolSquad squad, int offsetX, int offsetZ) {
        this.mob = mob;
        this.squad = squad;
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!mob.isAlive()) return false;
        net.minecraft.world.entity.LivingEntity currentTarget = mob.getTarget();
        if (currentTarget != null && currentTarget.isAlive()) {
            if (squad.shouldSuppressTargetAcquisition(mob.level(), mob, currentTarget)) {
                mob.setTarget(null);
            } else {
                // Yield immediately to combat AI when actively targeting a valid enemy
                return false;
            }
        }
        // Retaliate if damaged by player within leash rules
        if (mob.getLastHurtByMob() instanceof net.minecraft.world.entity.player.Player player
                && player.isAlive()
                && !squad.shouldSuppressTargetAcquisition(mob.level(), mob, player)) {
            squad.alertSquadToTarget(mob.level(), player);
            mob.setTarget(player);
            return false;
        }
        // Throttle squad target polling to every 10 ticks to avoid 36 UUID lookups per squad per tick
        if ((mob.tickCount + mob.getId()) % 10 == 0) {
            net.minecraft.world.entity.LivingEntity squadTarget = squad.getSquadTarget(mob.level());
            if (squadTarget != null && squadTarget.isAlive()
                    && !squad.shouldSuppressTargetAcquisition(mob.level(), mob, squadTarget)) {
                mob.setTarget(squadTarget);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathCooldownTicks = 0;
        lookCooldownTicks = 0;
        lastKnownWaypoint = squad.getCurrentWaypoint();
    }

    @Override
    public void stop() {
        if (mob.getTarget() != null && mob.getTarget().isAlive()) {
            squad.alertSquadToTarget(mob.level(), mob.getTarget());
        }
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        // If this mob acquired a player target, alert the whole squad immediately
        if (mob.getTarget() != null && mob.getTarget().isAlive()) {
            squad.alertSquadToTarget(mob.level(), mob.getTarget());
            return;
        }

        BlockPos waypoint = squad.getCurrentWaypoint();
        if (waypoint == null) return;

        // If squad waypoint changed, reset pathfinding immediately to begin march
        if (lastKnownWaypoint == null || !lastKnownWaypoint.equals(waypoint)) {
            lastKnownWaypoint = waypoint;
            repathCooldownTicks = 0;
            mob.getNavigation().stop();
        }

        int targetX = waypoint.getX() + offsetX;
        int targetZ = waypoint.getZ() + offsetZ;

        // Bridge routing: If a bridge exists and destination is on the opposite bank of the river
        SubregionBridge bridge = SubregionBridgeManager.getBridge(mob.level(), squad.getRegionX(), squad.getRegionZ(), squad.getSubX(), squad.getSubZ());
        int navDestX = targetX;
        int navDestZ = targetZ;
        boolean crossingBridge = false;

        if (bridge != null && mob.level() instanceof ServerLevel sl && bridge.isOppositeSides(mob.getX(), mob.getZ(), targetX, targetZ)) {
            if (--bridgeCheckCooldown <= 0) {
                bridgeCheckCooldown = 20;
                bridgeIntactCached = bridge.isIntact(sl);
            }
            if (bridgeIntactCached) {
                crossingBridge = true;
                BlockPos approach = bridge.getApproachPos(mob.getX(), mob.getZ());
                BlockPos anchor = bridge.centerAnchor();
                BlockPos exit = bridge.getExitPos(mob.getX(), mob.getZ());

                double distToApproachSq = mob.distanceToSqr(approach.getX() + 0.5D, approach.getY(), approach.getZ() + 0.5D);
                double distToAnchorSq = mob.distanceToSqr(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);

                if (!bridge.isOnBridgeDeck(mob.getX(), mob.getZ()) && distToApproachSq > 9.0D) {
                    // Navigate to bridge entry approach on current bank
                    navDestX = approach.getX();
                    navDestZ = approach.getZ();
                } else if (distToAnchorSq > 4.0D && bridge.isSameSideAsApproach(mob.getX(), mob.getZ(), approach)) {
                    // Cross from approach onto the bridge towards the crown anchor
                    navDestX = anchor.getX();
                    navDestZ = anchor.getZ();
                } else {
                    // Cross from crown anchor towards the exit approach on destination bank
                    navDestX = exit.getX();
                    navDestZ = exit.getZ();
                }
            }
        }

        double dx = targetX - mob.getX();
        double dz = targetZ - mob.getZ();
        double distSq = dx * dx + dz * dz;

        if (squad.isHoldingGround()) {
            // Phase: Holding ground for 15 seconds (300 ticks)
            if (distSq > 25.0D) {
                // Return to formation post if drifted
                int targetY = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ);
                mob.getNavigation().moveTo(targetX + 0.5D, targetY, targetZ + 0.5D, 1.0D);
            } else {
                mob.getNavigation().stop();
                if (--lookCooldownTicks <= 0) {
                    lookCooldownTicks = 25 + mob.getRandom().nextInt(30);
                    double angle = mob.getRandom().nextDouble() * 2 * Math.PI;
                    double lookX = mob.getX() + Math.cos(angle) * 8.0D;
                    double lookZ = mob.getZ() + Math.sin(angle) * 8.0D;
                    mob.getLookControl().setLookAt(lookX, mob.getEyeY(), lookZ);
                }
            }
        } else {
            // Phase: Marching toward waypoint
            if (!crossingBridge && distSq <= 25.0D) {
                // Arrived at destination waypoint -> Notify squad to transition to holding ground
                squad.notifyArrival(mob.level());
            } else {
                if (--repathCooldownTicks <= 0 || mob.getNavigation().isDone()) {
                    repathCooldownTicks = 25 + mob.getRandom().nextInt(15);
                    navigateTo(navDestX, navDestZ);
                }
            }
        }
    }

    private static final double LAND_SPEED = 0.80D;
    private static final double WATER_SPEED = 1.15D;

    private void navigateTo(int destX, int destZ) {
        double dx = destX - mob.getX();
        double dz = destZ - mob.getZ();
        double dist = Math.hypot(dx, dz);

        if (dist <= 0.5D) {
            return;
        }

        boolean inWater = mob.isInWater();
        double speed = inWater ? WATER_SPEED : LAND_SPEED;

        // In water, eliminate pathfinding water malus so mobs swim directly across channels
        mob.setPathfindingMalus(PathType.WATER, inWater ? 0.0F : 8.0F);

        int targetY;
        if (dist <= 24.0D) {
            targetY = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, destX, destZ);
            mob.getNavigation().moveTo(destX + 0.5D, targetY, destZ + 0.5D, speed);
        } else {
            // Incremental step toward long-distance destination
            double ux = dx / dist;
            double uz = dz / dist;
            int stepX = (int) Math.round(mob.getX() + ux * 20.0D);
            int stepZ = (int) Math.round(mob.getZ() + uz * 20.0D);
            targetY = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, stepX, stepZ);
            mob.getNavigation().moveTo(stepX + 0.5D, targetY, stepZ + 0.5D, speed);
        }

        // Anti-stall fallback in water: if ground navigation drops path or is done, directly drive move control
        if (inWater && (mob.getNavigation().isDone() || !mob.getNavigation().isInProgress())) {
            mob.getMoveControl().setWantedPosition(destX + 0.5D, targetY, destZ + 0.5D, speed);
        }
    }
}
