package com.warfront.ai.goal;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/**
 * Phase 3 AI Goal for Attack Roamers: Hold ground and defend assigned frontline formation post.
 *
 * Activates when the unit has arrived within holding distance (<= 5-6 blocks) of its specific
 * assigned formation post and is not currently engaging a player. Keeps the unit anchored near its
 * post within the squad's 8x8 holding zone while looking around for approaching enemies.
 */
public class HoldGroundGoal extends Goal {

    private final Mob mob;
    private final int targetX;
    private final int targetZ;
    private int lookCooldownTicks = 0;

    public HoldGroundGoal(Mob mob, int targetX, int targetZ) {
        this.mob = mob;
        this.targetX = targetX;
        this.targetZ = targetZ;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!mob.isAlive()) return false;
        if (mob.getTarget() != null && mob.getTarget().isAlive()) return false;
        return getHorizontalDistanceSq() <= 36.0D; // Within 6 blocks of assigned post
    }

    @Override
    public boolean canContinueToUse() {
        if (!mob.isAlive()) return false;
        if (mob.getTarget() != null && mob.getTarget().isAlive()) return false;
        return getHorizontalDistanceSq() <= 100.0D; // Allow tether radius up to 10 blocks
    }

    @Override
    public void start() {
        lookCooldownTicks = 0;
    }

    @Override
    public void tick() {
        double distSq = getHorizontalDistanceSq();
        if (distSq > 16.0D) { // If drifted more than 4 blocks from assigned post, return to post
            int targetY = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ);
            mob.getNavigation().moveTo(targetX + 0.5D, targetY, targetZ + 0.5D, 1.0D);
        } else {
            mob.getNavigation().stop();
            if (--lookCooldownTicks <= 0) {
                lookCooldownTicks = 30 + mob.getRandom().nextInt(40);
                double angle = mob.getRandom().nextDouble() * 2 * Math.PI;
                double lookX = mob.getX() + Math.cos(angle) * 8.0D;
                double lookZ = mob.getZ() + Math.sin(angle) * 8.0D;
                mob.getLookControl().setLookAt(lookX, mob.getEyeY(), lookZ);
            }
        }
    }

    private double getHorizontalDistanceSq() {
        double dx = targetX - mob.getX();
        double dz = targetZ - mob.getZ();
        return dx * dx + dz * dz;
    }
}
