package com.warfront.ai.goal;

import com.warfront.region.bridge.SubregionBridge;
import com.warfront.region.bridge.SubregionBridgeManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.PathType;

import java.util.EnumSet;

/**
 * Phase 1 AI Goal for Attack Roamers: Directional advance toward frontline destination.
 *
 * Directs the mob to navigate along a directional vector toward its assigned formation post (targetX, targetZ)
 * using incremental waypoints (max 20 blocks per step) to guarantee robust long-distance
 * pathfinding across the entire region.
 *
 * Automatically yields when a combat target is acquired (Phase 2), and finishes when the mob
 * arrives within holding distance (<= 4 blocks) of the target (Phase 3).
 */
public class AdvanceToLocationGoal extends Goal {

    private final Mob mob;
    private final int targetX;
    private final int targetZ;
    private final double speedModifier;
    private int repathCooldownTicks = 0;

    public static final double ARRIVAL_DIST_SQ = 4.0D * 4.0D; // 16.0 blocks squared

    public AdvanceToLocationGoal(Mob mob, int targetX, int targetZ, double speedModifier) {
        this.mob = mob;
        this.targetX = targetX;
        this.targetZ = targetZ;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!mob.isAlive()) return false;
        if (mob.getTarget() != null && mob.getTarget().isAlive()) return false;
        return getHorizontalDistanceSq() > ARRIVAL_DIST_SQ;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathCooldownTicks = 0;
        navigateToTarget();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (--repathCooldownTicks <= 0 || mob.getNavigation().isDone()) {
            repathCooldownTicks = 20 + mob.getRandom().nextInt(15);
            navigateToTarget();
        }
    }

    private void navigateToTarget() {
        int navX = targetX;
        int navZ = targetZ;

        SubregionBridge bridge = SubregionBridgeManager.getBridgeAt(mob.level(), mob.blockPosition());
        if (bridge != null && mob.level() instanceof ServerLevel sl && bridge.isIntact(sl) && bridge.isOppositeSides(mob.getX(), mob.getZ(), targetX, targetZ)) {
            BlockPos approach = bridge.getApproachPos(mob.getX(), mob.getZ());
            BlockPos anchor = bridge.centerAnchor();
            BlockPos exit = bridge.getExitPos(mob.getX(), mob.getZ());

            double distToApproachSq = mob.distanceToSqr(approach.getX() + 0.5D, approach.getY(), approach.getZ() + 0.5D);
            double distToAnchorSq = mob.distanceToSqr(anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.5D);

            if (!bridge.isOnBridgeDeck(mob.getX(), mob.getZ()) && distToApproachSq > 9.0D) {
                navX = approach.getX();
                navZ = approach.getZ();
            } else if (distToAnchorSq > 4.0D && bridge.isSameSideAsApproach(mob.getX(), mob.getZ(), approach)) {
                navX = anchor.getX();
                navZ = anchor.getZ();
            } else {
                navX = exit.getX();
                navZ = exit.getZ();
            }
        }

        double dx = navX - mob.getX();
        double dz = navZ - mob.getZ();
        double dist = Math.hypot(dx, dz);

        if (dist <= 0.5D) {
            return;
        }

        boolean inWater = mob.isInWater();
        double effectiveSpeed = inWater ? 1.15D : speedModifier;
        mob.setPathfindingMalus(PathType.WATER, inWater ? 0.0F : 8.0F);

        int targetY;
        if (dist <= 20.0D) {
            targetY = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, navX, navZ);
            mob.getNavigation().moveTo(navX + 0.5D, targetY, navZ + 0.5D, effectiveSpeed);
        } else {
            // Incremental waypoint step (20 blocks forward)
            double ux = dx / dist;
            double uz = dz / dist;
            int stepX = (int) Math.round(mob.getX() + ux * 20.0D);
            int stepZ = (int) Math.round(mob.getZ() + uz * 20.0D);
            targetY = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, stepX, stepZ);
            mob.getNavigation().moveTo(stepX + 0.5D, targetY, stepZ + 0.5D, effectiveSpeed);
        }

        if (inWater && (mob.getNavigation().isDone() || !mob.getNavigation().isInProgress())) {
            mob.getMoveControl().setWantedPosition(navX + 0.5D, targetY, navZ + 0.5D, effectiveSpeed);
        }
    }

    private double getHorizontalDistanceSq() {
        double dx = targetX - mob.getX();
        double dz = targetZ - mob.getZ();
        return dx * dx + dz * dz;
    }

    public int getTargetX() { return targetX; }
    public int getTargetZ() { return targetZ; }
}
