package com.warfront.entity.ai.goal;

import com.warfront.entity.PillagerScoutEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Stationary guard goal for Pillager Scouts stationed at the Headquarters stables.
 * The scout remains fixed at their assigned post facing outward until an enemy player
 * is spotted within detection range or attacks them, at which point daggers are drawn
 * and melee combat engages. If the target flees or dies, the scout retreats to the post.
 */
public class HQStablesScoutGuardGoal extends Goal {

    private final PillagerScoutEntity scout;
    private final BlockPos postPos;
    private final Direction facing;
    private final double detectRadius;
    private final double maxLeashDistance;
    private boolean isRetreating = false;

    public HQStablesScoutGuardGoal(PillagerScoutEntity scout, BlockPos postPos, Direction facing, double detectRadius, double maxLeashDistance) {
        this.scout = scout;
        this.postPos = postPos;
        this.facing = facing;
        this.detectRadius = detectRadius;
        this.maxLeashDistance = maxLeashDistance;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.postPos == null) return false;

        if (this.isRetreating) return true;

        LivingEntity target = this.scout.getTarget();
        if (target != null && target.isAlive()) {
            double distToPostSq = this.scout.distanceToSqr(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D);
            if (distToPostSq > this.maxLeashDistance * this.maxLeashDistance) {
                this.isRetreating = true;
                this.scout.setTarget(null);
                return true;
            }
            // In combat within leash: yield to melee attack goal
            return false;
        }

        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        LivingEntity target = this.scout.getTarget();
        double distSq = this.scout.distanceToSqr(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D);

        if (this.isRetreating || (target == null && distSq > 1.5D)) {
            // Returning to post
            this.scout.getNavigation().moveTo(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D, 1.15D);
            if (distSq <= 1.5D) {
                this.isRetreating = false;
                this.scout.getNavigation().stop();
                if (this.scout.areDaggersDrawn()) {
                    this.scout.setDaggersDrawn(false);
                }
            }
            return;
        }

        // At post: completely stationary, facing assigned direction
        this.scout.getNavigation().stop();
        float yaw = this.facing.toYRot();
        this.scout.setYRot(yaw);
        this.scout.setYHeadRot(yaw);
        this.scout.setYBodyRot(yaw);

        // Look for nearby survival players
        if (!this.scout.level().isClientSide && target == null) {
            Player nearestPlayer = this.scout.level().getNearestPlayer(
                    this.scout.getX(), this.scout.getY(), this.scout.getZ(),
                    this.detectRadius,
                    p -> p instanceof Player pl && !pl.isCreative() && !pl.isSpectator() && this.scout.hasLineOfSight(pl)
            );
            if (nearestPlayer != null) {
                this.scout.setDaggersDrawn(true);
                this.scout.setTarget(nearestPlayer);
            }
        }
    }
}
