package com.warfront.entity.ai.goal;

import com.warfront.entity.PillagerMarksmanEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Custom AI goal for Outpost tower and wall marksmen.
 *
 * Rules:
 * - Constrains marksmen to towers and walls connecting towers (cannot leave/jump down).
 * - Occupies assigned post facing outside looking for players.
 * - Every 45s (+-15s randomization), moves toward the adjacent wall 1/3 of the wall length.
 * - South-facing marksmen and the 9th main-gate marksman do not patrol and permanently hold post.
 */
public class OutpostMarksmanPatrolGoal extends Goal {

    private enum PatrolState {
        AT_POST,
        ADVANCING_TO_WALL,
        HOLDING_AT_WALL,
        RETURNING_TO_POST
    }

    private final PillagerMarksmanEntity marksman;
    private final BlockPos postPos;
    private final float postYaw;
    @Nullable
    private final BlockPos wallPatrolPos;
    private final float wallLookYaw;
    private final int minY;
    private final int maxY;

    private PatrolState state = PatrolState.AT_POST;
    private int patrolTimer;
    private int wallHoldTimer = 0;
    private int navigationStuckTimer = 0;

    public OutpostMarksmanPatrolGoal(
            PillagerMarksmanEntity marksman,
            BlockPos postPos,
            Direction facingDirection,
            @Nullable BlockPos wallPatrolPos,
            Direction wallFacingDirection
    ) {
        this.marksman = marksman;
        this.postPos = postPos.immutable();
        this.postYaw = facingDirection.toYRot();
        this.wallPatrolPos = wallPatrolPos != null ? wallPatrolPos.immutable() : null;
        this.wallLookYaw = (wallFacingDirection != null) ? wallFacingDirection.toYRot() : postYaw;
        this.minY = Math.min(postPos.getY(), (wallPatrolPos != null ? wallPatrolPos.getY() : postPos.getY())) - 1;
        this.maxY = Math.max(postPos.getY(), (wallPatrolPos != null ? wallPatrolPos.getY() : postPos.getY())) + 3;

        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        // Quick initial patrol activation on spawn (5 to 8 seconds = 100 to 160 ticks)
        this.patrolTimer = 100 + marksman.getRandom().nextInt(61);
    }

    public BlockPos getPostPos() {
        return postPos;
    }

    @Nullable
    public BlockPos getWallPatrolPos() {
        return wallPatrolPos;
    }

    private void resetPatrolTimer() {
        // Subsequent cycles: 45s +- 15s (30s to 60s, i.e., 600 to 1200 ticks)
        this.patrolTimer = 600 + marksman.getRandom().nextInt(601);
    }

    @Override
    public boolean canUse() {
        if (!marksman.isAlive()) {
            return false;
        }
        BlockState support = marksman.level().getBlockState(postPos.below());
        if (support.isAir() || !support.blocksMotion()) {
            return false;
        }
        LivingEntity target = marksman.getTarget();
        if (target != null && target.isAlive()) {
            // If knocked off or outside vertical bounds, recover control to rescue marksman
            if (marksman.getBlockY() < minY || marksman.getBlockY() > maxY) {
                return true;
            }
            // In combat within bounds: yield so MarksmanAttackGoal can run!
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!marksman.isAlive()) {
            return false;
        }
        BlockState support = marksman.level().getBlockState(postPos.below());
        if (support.isAir() || !support.blocksMotion()) {
            return false;
        }
        LivingEntity target = marksman.getTarget();
        if (target != null && target.isAlive()) {
            if (marksman.getBlockY() < minY || marksman.getBlockY() > maxY) {
                return true;
            }
            // In combat within bounds: yield so MarksmanAttackGoal can run!
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        // Enforce bounds and disable door usage
        marksman.getNavigation().setCanFloat(true);
    }

    @Override
    public void tick() {
        // Leash enforcement: prevent falling down to courtyard/ground level
        if (marksman.getBlockY() < minY) {
            BlockState support = marksman.level().getBlockState(postPos.below());
            if (support.isAir() || !support.blocksMotion()) {
                // Tower platform is destroyed! Abort patrol leash and fight on foot
                marksman.goalSelector.removeGoal(this);
                return;
            }
            // Reposition back onto the platform if knocked off
            marksman.teleportTo(postPos.getX() + 0.5D, postPos.getY(), postPos.getZ() + 0.5D);
            marksman.setDeltaMovement(0, 0, 0);
            state = PatrolState.AT_POST;
            resetPatrolTimer();
            return;
        }

        // Out-of-combat patrol state machine:
        switch (state) {
            case AT_POST -> {
                double distSq = marksman.distanceToSqr(postPos.getX() + 0.5D, postPos.getY(), postPos.getZ() + 0.5D);
                if (distSq > 1.5D) {
                    if (!marksman.getNavigation().isInProgress()) {
                        marksman.getNavigation().moveTo(postPos.getX() + 0.5D, postPos.getY(), postPos.getZ() + 0.5D, 0.8D);
                    }
                } else {
                    marksman.getNavigation().stop();
                    double rad = Math.toRadians(postYaw);
                    double lookX = marksman.getX() - Math.sin(rad) * 10.0D;
                    double lookZ = marksman.getZ() + Math.cos(rad) * 10.0D;
                    marksman.getLookControl().setLookAt(lookX, marksman.getEyeY(), lookZ, 30.0F, 30.0F);
                }

                if (wallPatrolPos != null) {
                    patrolTimer--;
                    if (patrolTimer <= 0) {
                        state = PatrolState.ADVANCING_TO_WALL;
                        navigationStuckTimer = 0;
                        marksman.getNavigation().moveTo(wallPatrolPos.getX() + 0.5D, wallPatrolPos.getY(), wallPatrolPos.getZ() + 0.5D, 0.8D);
                    }
                }
            }
            case ADVANCING_TO_WALL -> {
                if (wallPatrolPos == null) {
                    state = PatrolState.AT_POST;
                    return;
                }
                double distSq = marksman.distanceToSqr(wallPatrolPos.getX() + 0.5D, wallPatrolPos.getY(), wallPatrolPos.getZ() + 0.5D);
                navigationStuckTimer++;

                if (distSq <= 2.0D || navigationStuckTimer > 240) { // reached or 12s timeout
                    marksman.getNavigation().stop();
                    state = PatrolState.HOLDING_AT_WALL;
                    wallHoldTimer = 200; // Hold at wall for 10s (200 ticks) looking outward
                } else if (!marksman.getNavigation().isInProgress()) {
                    marksman.getNavigation().moveTo(wallPatrolPos.getX() + 0.5D, wallPatrolPos.getY(), wallPatrolPos.getZ() + 0.5D, 0.8D);
                }
            }
            case HOLDING_AT_WALL -> {
                double rad = Math.toRadians(wallLookYaw);
                double lookX = marksman.getX() - Math.sin(rad) * 10.0D;
                double lookZ = marksman.getZ() + Math.cos(rad) * 10.0D;
                marksman.getLookControl().setLookAt(lookX, marksman.getEyeY(), lookZ, 30.0F, 30.0F);

                wallHoldTimer--;
                if (wallHoldTimer <= 0) {
                    state = PatrolState.RETURNING_TO_POST;
                    navigationStuckTimer = 0;
                    marksman.getNavigation().moveTo(postPos.getX() + 0.5D, postPos.getY(), postPos.getZ() + 0.5D, 0.8D);
                }
            }
            case RETURNING_TO_POST -> {
                double distSq = marksman.distanceToSqr(postPos.getX() + 0.5D, postPos.getY(), postPos.getZ() + 0.5D);
                navigationStuckTimer++;

                if (distSq <= 1.5D || navigationStuckTimer > 240) {
                    marksman.getNavigation().stop();
                    state = PatrolState.AT_POST;
                    resetPatrolTimer();
                } else if (!marksman.getNavigation().isInProgress()) {
                    marksman.getNavigation().moveTo(postPos.getX() + 0.5D, postPos.getY(), postPos.getZ() + 0.5D, 0.8D);
                }
            }
        }
    }
}
