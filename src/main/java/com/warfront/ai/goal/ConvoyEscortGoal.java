package com.warfront.ai.goal;

import com.warfront.entity.PillagerMarksmanEntity;
import com.warfront.entity.PillagerWarriorEntity;
import com.warfront.entity.SupplyWagonCartEntity;
import com.warfront.entity.SupplyWagonExtensionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.UUID;

/**
 * AI Goal for Supply Convoy escort guards (Warriors and Marksmen).
 *
 * Behaviors:
 *   1. Formation March: Guards match pace alongside their assigned wagon in predetermined left/right slots.
 *   2. Dynamic Leash Engagement:
 *      - Marksmen engagement range: 8 blocks max from assigned wagon.
 *      - Warriors engagement range: 24 blocks max from assigned wagon.
 *   3. Anti-Glitch Full Retreat:
 *      - If the target pulls the escort beyond their max chase distance, the escort breaks combat,
 *        clears their target, and sprints all the way back to the assigned wagon (within 3.0 blocks)
 *        before being permitted to re-engage enemies.
 */
public class ConvoyEscortGoal extends Goal {

    private final Mob mob;
    private final double sideOffset;
    private final double forwardOffset;
    private final double maxChaseDistance;

    private Mob wagon;
    private UUID wagonUuid;
    private boolean isRetreating = false;
    private int repathCooldown = 0;

    public ConvoyEscortGoal(Mob mob, Mob wagon, double sideOffset, double forwardOffset, double maxChaseDistance) {
        this.mob = mob;
        this.wagon = wagon;
        this.wagonUuid = wagon != null ? wagon.getUUID() : null;
        this.sideOffset = sideOffset;
        this.forwardOffset = forwardOffset;
        this.maxChaseDistance = maxChaseDistance;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public ConvoyEscortGoal(Mob mob, Mob wagon, double sideOffset, double forwardOffset) {
        this(
                mob,
                wagon,
                sideOffset,
                forwardOffset,
                (mob instanceof PillagerMarksmanEntity) ? 8.0D : ((mob instanceof PillagerWarriorEntity) ? 24.0D : 16.0D)
        );
    }

    private Mob getWagon() {
        if (this.wagon != null && this.wagon.isAlive()) {
            return this.wagon;
        }
        if (this.wagonUuid != null && this.mob.level() instanceof ServerLevel serverLevel) {
            if (serverLevel.getEntity(this.wagonUuid) instanceof Mob foundWagon && foundWagon.isAlive()) {
                this.wagon = foundWagon;
                return this.wagon;
            }
        }
        return null;
    }

    private boolean isWagonMoving(Mob w) {
        if (w instanceof SupplyWagonCartEntity c) return c.isMoving();
        if (w instanceof SupplyWagonExtensionEntity e) return e.isMoving();
        return w.getDeltaMovement().horizontalDistanceSqr() > 0.0004D;
    }

    public boolean isRetreating() {
        return this.isRetreating;
    }

    private void triggerRetreat() {
        this.isRetreating = true;
        this.mob.setTarget(null);
        this.repathCooldown = 0;
    }

    @Override
    public boolean canUse() {
        Mob w = getWagon();
        if (w == null || !w.isAlive()) {
            return false;
        }

        if (this.isRetreating) {
            return true;
        }

        if (this.mob.getTarget() != null && this.mob.getTarget().isAlive()) {
            double distToWagon = this.mob.distanceTo(w);
            if (distToWagon > this.maxChaseDistance) {
                triggerRetreat();
                return true;
            }
            // Yield to combat goals within chase bounds
            return false;
        }

        return true;
    }

    @Override
    public boolean canContinueToUse() {
        Mob w = getWagon();
        if (w == null || !w.isAlive()) {
            return false;
        }

        if (this.isRetreating) {
            return true;
        }

        if (this.mob.getTarget() != null && this.mob.getTarget().isAlive()) {
            double distToWagon = this.mob.distanceTo(w);
            if (distToWagon > this.maxChaseDistance) {
                triggerRetreat();
                return true;
            }
            return false;
        }

        return true;
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        Mob w = getWagon();
        if (w == null || !w.isAlive()) {
            return;
        }

        Vec3 formationPos = getFormationPos(w);
        double dx = formationPos.x - this.mob.getX();
        double dz = formationPos.z - this.mob.getZ();
        double distSq = dx * dx + dz * dz;

        if (this.isRetreating) {
            // Anti-Glitch: forcibly wipe acquired target to prevent edge kiting
            if (this.mob.getTarget() != null) {
                this.mob.setTarget(null);
            }

            // Completed full retreat upon reaching station close to wagon (<= 3.0 blocks)
            if (distSq <= 9.0D) {
                this.isRetreating = false;
                this.repathCooldown = 0;
                return;
            }

            // Sprint back towards formation position at high speed
            if (--this.repathCooldown <= 0 || this.mob.getNavigation().isDone()) {
                this.repathCooldown = 12;
                this.mob.getNavigation().moveTo(formationPos.x, formationPos.y, formationPos.z, 1.35D);
            }
        } else {
            // In formation: verify dynamic leash if target was acquired
            if (this.mob.getTarget() != null && this.mob.getTarget().isAlive()) {
                if (this.mob.distanceTo(w) > this.maxChaseDistance) {
                    triggerRetreat();
                    return;
                }
            }

            boolean moving = isWagonMoving(w);
            if (distSq > 3.0D) {
                double speed = moving ? 1.25D : 1.05D;
                if (--this.repathCooldown <= 0 || this.mob.getNavigation().isDone()) {
                    this.repathCooldown = 15;
                    this.mob.getNavigation().moveTo(formationPos.x, formationPos.y, formationPos.z, speed);
                }
            } else {
                if (!moving) {
                    this.mob.getNavigation().stop();
                    this.mob.setYRot(w.getYRot());
                    this.mob.setYHeadRot(w.getYRot());
                } else {
                    if (--this.repathCooldown <= 0) {
                        this.repathCooldown = 10;
                        this.mob.getNavigation().moveTo(formationPos.x, formationPos.y, formationPos.z, 1.15D);
                    }
                }
            }
        }
    }

    private Vec3 getFormationPos(Mob w) {
        float yawRad = w.getYRot() * Mth.DEG_TO_RAD;
        double fwdX = -Mth.sin(yawRad);
        double fwdZ = Mth.cos(yawRad);
        double rightX = Mth.cos(yawRad);
        double rightZ = Mth.sin(yawRad);

        double targetX = w.getX() + fwdX * this.forwardOffset + rightX * this.sideOffset;
        double targetZ = w.getZ() + fwdZ * this.forwardOffset + rightZ * this.sideOffset;
        int targetY = this.mob.level().getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.round(targetX),
                (int) Math.round(targetZ)
        );
        return new Vec3(targetX, targetY, targetZ);
    }
}
