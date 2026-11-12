package com.warfront.entity.ai.goal;

import com.warfront.region.base.PillagerHeadquartersGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/**
 * AI goal for stationary Pillager Headquarters wall sentries (Cannoneers, Rapidbows, Marksmen, Warriors, Elites).
 * - Out of combat: Mob remains strictly stationary at their designated wall post, facing outward.
 * - In combat: Engages and pursues approaching players as long as they stay within 16 blocks of the HQ borders.
 * - If target flees beyond 16 blocks of the HQ perimeter: Mob clears target, returns to their wall post,
 *   and resumes outward-facing stationary watch.
 */
public class HQWallSentryGuardGoal extends Goal {

    public static final double BORDER_MARGIN_BLOCKS = 16.0D;

    private final Mob mob;
    private final BlockPos postPos;
    private final Direction outwardFacing;
    private final AABB engagementBounds;
    private boolean isRetreating = false;
    private int repathCooldown = 0;

    public HQWallSentryGuardGoal(Mob mob, BlockPos postPos, Direction outwardFacing, BlockPos hqAnchor) {
        this.mob = mob;
        this.postPos = postPos;
        this.outwardFacing = outwardFacing;
        this.engagementBounds = PillagerHeadquartersGenerator.getHQEngagementAABB(hqAnchor, BORDER_MARGIN_BLOCKS);
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.postPos == null) return false;

        if (this.isRetreating) return true;

        LivingEntity target = this.mob.getTarget();
        if (target != null && target.isAlive()) {
            // Check if player is outside the 16-block HQ border boundary
            if (!this.engagementBounds.contains(target.getX(), target.getY(), target.getZ())) {
                this.isRetreating = true;
                this.mob.setTarget(null);
                return true;
            }
            // In combat within engagement bounds: yield to attack goals
            return false;
        }

        // Out of combat: active if not at post, or at post holding watch
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        double distSq = this.mob.distanceToSqr(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D);

        if (this.isRetreating || (target == null && distSq > 1.25D)) {
            // Returning to wall post
            if (this.repathCooldown <= 0) {
                this.repathCooldown = 15;
                this.mob.getNavigation().moveTo(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D, 1.15D);
            } else {
                this.repathCooldown--;
            }

            if (distSq <= 1.25D) {
                this.isRetreating = false;
                this.mob.getNavigation().stop();
            }
            return;
        }

        // At post: lock position and face outward
        this.mob.getNavigation().stop();
        float yaw = this.outwardFacing.toYRot();
        this.mob.setYRot(yaw);
        this.mob.setYHeadRot(yaw);
        this.mob.setYBodyRot(yaw);
    }
}
