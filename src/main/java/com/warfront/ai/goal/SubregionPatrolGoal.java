package com.warfront.ai.goal;

import com.warfront.spawn.SubregionPatrolManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

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
        // If another squad member spotted a target, sync to it and yield to combat AI
        net.minecraft.world.entity.LivingEntity squadTarget = squad.getSquadTarget(mob.level());
        if (squadTarget != null && squadTarget.isAlive()) {
            if (mob.getTarget() == null || !mob.getTarget().isAlive()) {
                mob.setTarget(squadTarget);
            }
            return false;
        }
        // Yield to combat AI when actively targeting an enemy
        return mob.getTarget() == null || !mob.getTarget().isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathCooldownTicks = 0;
        lookCooldownTicks = 0;
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

        int targetX = waypoint.getX() + offsetX;
        int targetZ = waypoint.getZ() + offsetZ;

        double dx = targetX - mob.getX();
        double dz = targetZ - mob.getZ();
        double distSq = dx * dx + dz * dz;

        if (squad.isHoldingGround()) {
            // Phase: Holding ground for 15 seconds (300 ticks)
            if (distSq > 25.0D) {
                // Return to formation post if drifted
                int targetY = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ);
                mob.getNavigation().moveTo(targetX + 0.5D, targetY, targetZ + 0.5D, 0.55D);
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
            if (distSq <= 25.0D) {
                // Arrived at destination waypoint -> Notify squad to transition to holding ground
                squad.notifyArrival(mob.level());
            } else {
                if (--repathCooldownTicks <= 0 || mob.getNavigation().isDone()) {
                    repathCooldownTicks = 25 + mob.getRandom().nextInt(15);
                    navigateTo(targetX, targetZ);
                }
            }
        }
    }

    private void navigateTo(int destX, int destZ) {
        double dx = destX - mob.getX();
        double dz = destZ - mob.getZ();
        double dist = Math.hypot(dx, dz);

        if (dist <= 0.5D) {
            return;
        }

        if (dist <= 24.0D) {
            int targetY = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, destX, destZ);
            mob.getNavigation().moveTo(destX + 0.5D, targetY, destZ + 0.5D, 0.45D);
        } else {
            // Incremental step toward long-distance destination
            double ux = dx / dist;
            double uz = dz / dist;
            int stepX = (int) Math.round(mob.getX() + ux * 20.0D);
            int stepZ = (int) Math.round(mob.getZ() + uz * 20.0D);
            int stepY = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, stepX, stepZ);
            mob.getNavigation().moveTo(stepX + 0.5D, stepY, stepZ + 0.5D, 0.45D);
        }
    }
}
