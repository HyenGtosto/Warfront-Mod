package com.warfront.ai.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * AI Goal for mission-specific defenders guarding an authoritative post or formation anchor.
 *
 * Behaviors:
 * 1. Within leash radius: yields to combat goals so normal melee/ranged combat occurs.
 * 2. If target pulls mob beyond maxLeashDistance: clears target, breaks combat, and retreats to post.
 * 3. When target is lost or dead: pathfinds back to post and holds formation until a player approaches.
 */
public class MissionPostGuardGoal extends Goal {

    private final Mob mob;
    private final BlockPos postPos;
    private final double maxLeashDistance;
    private final double returnSpeed;

    private boolean isRetreating = false;
    private int repathCooldown = 0;

    public MissionPostGuardGoal(Mob mob, BlockPos postPos, double maxLeashDistance, double returnSpeed) {
        this.mob = mob;
        this.postPos = postPos;
        this.maxLeashDistance = maxLeashDistance;
        this.returnSpeed = returnSpeed;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public BlockPos getPostPos() {
        return postPos;
    }

    public boolean isRetreating() {
        return isRetreating;
    }

    private void triggerRetreat() {
        this.isRetreating = true;
        this.mob.setTarget(null);
        this.repathCooldown = 0;
    }

    @Override
    public boolean canUse() {
        if (this.postPos == null) {
            return false;
        }

        if (this.isRetreating) {
            return true;
        }

        LivingEntity target = this.mob.getTarget();
        if (target != null && target.isAlive()) {
            double distToPostSq = this.mob.distanceToSqr(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D);
            if (distToPostSq > this.maxLeashDistance * this.maxLeashDistance) {
                triggerRetreat();
                return true;
            }
            // In combat within leash bounds: yield to combat goals
            return false;
        }

        // No target: if mob drifted away from post (> 3.5 blocks), return to post
        double distSq = this.mob.distanceToSqr(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D);
        return distSq > 12.25D; // > 3.5 blocks
    }

    @Override
    public boolean canContinueToUse() {
        if (this.postPos == null) {
            return false;
        }

        if (this.isRetreating) {
            double distSq = this.mob.distanceToSqr(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D);
            return distSq > 6.25D; // Keep returning until within 2.5 blocks of post
        }

        LivingEntity target = this.mob.getTarget();
        if (target != null && target.isAlive()) {
            double distToPostSq = this.mob.distanceToSqr(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D);
            if (distToPostSq > this.maxLeashDistance * this.maxLeashDistance) {
                triggerRetreat();
                return true;
            }
            return false;
        }

        double distSq = this.mob.distanceToSqr(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D);
        return distSq > 6.25D;
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        this.isRetreating = false;
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.repathCooldown <= 0) {
            this.repathCooldown = 15;
            this.mob.getNavigation().moveTo(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D, this.returnSpeed);
        } else {
            this.repathCooldown--;
        }

        double distSq = this.mob.distanceToSqr(this.postPos.getX() + 0.5D, this.postPos.getY(), this.postPos.getZ() + 0.5D);
        if (distSq <= 6.25D) {
            this.isRetreating = false;
            this.mob.getNavigation().stop();
        } else {
            this.mob.getLookControl().setLookAt(this.postPos.getX() + 0.5D, this.postPos.getY() + 1.0D, this.postPos.getZ() + 0.5D, 30.0F, 30.0F);
        }
    }
}
