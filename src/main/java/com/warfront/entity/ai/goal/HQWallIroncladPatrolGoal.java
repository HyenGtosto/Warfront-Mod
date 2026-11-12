package com.warfront.entity.ai.goal;

import com.warfront.entity.PillagerIroncladEntity;
import com.warfront.region.base.PillagerHeadquartersGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/**
 * AI goal for Pillager Ironclads patrolling the Headquarters side-wall walkways end to end.
 * - Out of combat: Continuously patrols between startPos and endPos along the wall walkway.
 * - In combat: Pursues and attacks approaching players within 16 blocks of the HQ borders.
 * - If target flees beyond 16 blocks: Ironclad clears target, returns to the walkway, and resumes patrol.
 */
public class HQWallIroncladPatrolGoal extends Goal {

    public static final double BORDER_MARGIN_BLOCKS = 16.0D;

    private final PillagerIroncladEntity ironclad;
    private final BlockPos startPos;
    private final BlockPos endPos;
    private final AABB engagementBounds;
    private boolean headingToEnd = true;
    private boolean isRetreating = false;
    private int repathCooldown = 0;

    public HQWallIroncladPatrolGoal(PillagerIroncladEntity ironclad, BlockPos startPos, BlockPos endPos, BlockPos hqAnchor) {
        this.ironclad = ironclad;
        this.startPos = startPos;
        this.endPos = endPos;
        this.engagementBounds = PillagerHeadquartersGenerator.getHQEngagementAABB(hqAnchor, BORDER_MARGIN_BLOCKS);
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.startPos == null || this.endPos == null) return false;

        if (this.isRetreating) return true;

        LivingEntity target = this.ironclad.getTarget();
        if (target != null && target.isAlive()) {
            if (!this.engagementBounds.contains(target.getX(), target.getY(), target.getZ())) {
                this.isRetreating = true;
                this.ironclad.setTarget(null);
                return true;
            }
            // In combat within bounds: yield to melee/bash attack goals
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
        LivingEntity target = this.ironclad.getTarget();

        if (this.isRetreating) {
            BlockPos currentDest = this.headingToEnd ? this.endPos : this.startPos;
            double distSq = this.ironclad.distanceToSqr(currentDest.getX() + 0.5D, currentDest.getY(), currentDest.getZ() + 0.5D);

            if (this.repathCooldown <= 0) {
                this.repathCooldown = 20;
                this.ironclad.getNavigation().moveTo(currentDest.getX() + 0.5D, currentDest.getY(), currentDest.getZ() + 0.5D, 1.0D);
            } else {
                this.repathCooldown--;
            }

            if (distSq <= 4.0D) {
                this.isRetreating = false;
            }
            return;
        }

        // Out of combat: patrol walkway end to end
        BlockPos destination = this.headingToEnd ? this.endPos : this.startPos;
        double distSq = this.ironclad.distanceToSqr(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D);

        if (distSq <= 4.0D) {
            // Reached waypoint: flip direction
            this.headingToEnd = !this.headingToEnd;
            destination = this.headingToEnd ? this.endPos : this.startPos;
            this.repathCooldown = 0;
        }

        if (this.repathCooldown <= 0) {
            this.repathCooldown = 30;
            this.ironclad.getNavigation().moveTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D, 0.85D);
        } else {
            this.repathCooldown--;
        }
    }
}
