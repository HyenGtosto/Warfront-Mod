package com.warfront.entity.ai.goal;

import com.warfront.region.base.PillagerHeadquartersGenerator;
import com.warfront.region.base.hq.HQSquadPatrolManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/**
 * AI goal for mobs belonging to dedicated Pillager Headquarters ground patrol squads.
 *
 * <p>Lifecycle:</p>
 * <ul>
 *   <li>The squad leader coordinates movement along exterior perimeter circuits and
 *       occasional ground-path courtyard detours.</li>
 *   <li>Followers maintain formation offsets behind the leader, funneling into single/double
 *       file when traversing 4-block wide gate archways.</li>
 *   <li>Approaching gates triggers portcullis opening via the gate controller; exiting
 *       triggers portcullis closure.</li>
 *   <li>When players are spotted within the 16-block HQ engagement boundary, the squad synchronizes
 *       aggro and engages. If players flee beyond the boundary, the squad drops aggro and resumes patrol.</li>
 * </ul>
 */
public class HQGroundPatrolGoal extends Goal {

    public static final double BORDER_MARGIN_BLOCKS = 16.0D;

    private final Mob mob;
    private final HQSquadPatrolManager.HQSquad squad;
    private final int offsetX;
    private final int offsetZ;
    private final AABB engagementBounds;

    private int repathCooldownTicks = 0;
    private int lookCooldownTicks = 0;
    private BlockPos lastTargetPos = null;

    public HQGroundPatrolGoal(Mob mob, HQSquadPatrolManager.HQSquad squad, int offsetX, int offsetZ) {
        this.mob = mob;
        this.squad = squad;
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
        this.engagementBounds = PillagerHeadquartersGenerator.getHQEngagementAABB(squad.getHqAnchor(), BORDER_MARGIN_BLOCKS);
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!mob.isAlive()) return false;

        LivingEntity currentTarget = mob.getTarget();
        if (currentTarget != null && currentTarget.isAlive()) {
            if (!this.engagementBounds.contains(currentTarget.getX(), currentTarget.getY(), currentTarget.getZ())) {
                // Target fled outside 16 blocks of HQ perimeter -> disengage
                mob.setTarget(null);
                squad.clearTarget();
            } else {
                // In combat within engagement bounds: yield to combat goals
                return false;
            }
        }

        // Retaliate if damaged by player
        if (mob.getLastHurtByMob() instanceof net.minecraft.world.entity.player.Player player && player.isAlive()) {
            if (this.engagementBounds.contains(player.getX(), player.getY(), player.getZ())) {
                squad.alertSquadToTarget(mob.level(), player);
                mob.setTarget(player);
                return false;
            }
        }

        // Periodic squad target synchronization
        if ((mob.tickCount + mob.getId()) % 10 == 0) {
            LivingEntity squadTarget = squad.getSquadTarget(mob.level());
            if (squadTarget != null && squadTarget.isAlive()) {
                if (this.engagementBounds.contains(squadTarget.getX(), squadTarget.getY(), squadTarget.getZ())) {
                    mob.setTarget(squadTarget);
                    return false;
                } else {
                    squad.clearTarget();
                }
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
        lastTargetPos = null;
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
        if (mob.getTarget() != null && mob.getTarget().isAlive()) {
            squad.alertSquadToTarget(mob.level(), mob.getTarget());
            return;
        }

        boolean isLeader = squad.isLeader(mob);

        // Leader evaluates waypoint arrival and advances squad route
        if (isLeader) {
            squad.tickLeader(mob);
        }

        BlockPos waypoint = squad.getCurrentWaypoint();
        if (waypoint == null) return;

        // Compute mob destination with formation offset
        BlockPos dest;
        if (isLeader) {
            dest = waypoint;
        } else {
            // Check if near a gate portal (within 6 blocks) to funnel formation through the archway
            boolean nearGate = squad.isNearGate(waypoint);
            int effectiveOffsetX = nearGate ? Math.clamp(offsetX, -1, 1) : offsetX;
            int effectiveOffsetZ = nearGate ? Math.clamp(offsetZ, -1, 1) : offsetZ;
            dest = waypoint.offset(effectiveOffsetX, 0, effectiveOffsetZ);
        }

        // Look toward waypoint
        if (--lookCooldownTicks <= 0) {
            lookCooldownTicks = 20 + mob.getRandom().nextInt(15);
            mob.getLookControl().setLookAt(dest.getX() + 0.5D, dest.getY() + 1.5D, dest.getZ() + 0.5D, 30.0F, 30.0F);
        }

        // Repath to destination
        if (--repathCooldownTicks <= 0 || lastTargetPos == null || !lastTargetPos.equals(dest)) {
            repathCooldownTicks = 25 + mob.getRandom().nextInt(15);
            lastTargetPos = dest;

            double distSq = mob.distanceToSqr(dest.getX() + 0.5D, dest.getY() + 0.5D, dest.getZ() + 0.5D);
            if (distSq > 2.0D) {
                double speed = (distSq > 144.0D) ? 1.25D : 1.05D;
                mob.getNavigation().moveTo(dest.getX() + 0.5D, dest.getY(), dest.getZ() + 0.5D, speed);
            }
        }
    }
}
