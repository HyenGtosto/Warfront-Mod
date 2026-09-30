package com.warfront.ai.goal;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

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
        double dx = targetX - mob.getX();
        double dz = targetZ - mob.getZ();
        double dist = Math.hypot(dx, dz);

        if (dist <= 0.5D) {
            return;
        }

        if (dist <= 20.0D) {
            int targetY = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ);
            mob.getNavigation().moveTo(targetX + 0.5D, targetY, targetZ + 0.5D, speedModifier);
        } else {
            // Incremental waypoint step (20 blocks forward)
            double ux = dx / dist;
            double uz = dz / dist;
            int stepX = (int) Math.round(mob.getX() + ux * 20.0D);
            int stepZ = (int) Math.round(mob.getZ() + uz * 20.0D);
            int stepY = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, stepX, stepZ);
            mob.getNavigation().moveTo(stepX + 0.5D, stepY, stepZ + 0.5D, speedModifier);
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
