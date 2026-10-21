package com.warfront.ai.goal;

import com.warfront.entity.AlliedFactionHelper;
import com.warfront.entity.PillagerScoutEntity;
import com.warfront.spawn.EnemyEncounterSpawner;
import com.warfront.spawn.SubregionPatrolManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * AI Goal for Pillager Scout Reconnaissance:
 *
 * Behavior:
 *   1. Swiftly navigates through its assigned subregion (64×64 blocks) between strategic vantage waypoints.
 *   2. Every 20–30 seconds (or upon reaching a waypoint), stops and enters the "Look" state (~82 ticks / 4.125s).
 *   3. During "Look", the Scout raises its spyglass and scans the horizon with a 150-degree horizontal field of view.
 *   4. Taking damage during "Look" interrupts the scan immediately (Option 2A), drawing daggers without sounding the horn.
 *   5. If a player is detected within the 150° arc and 30-block range with line of sight at the conclusion of the look:
 *      - Enters Horn state (60 ticks / 3.0s).
 *      - Holds a Goat Horn and plays "Seek" (item.goat_horn.sound.2).
 *      - Calls SubregionPatrolManager to trigger coordinated squad reinforcements.
 *      - Once the horn finishes, draws daggers and engages the player in melee combat to stall them (Option 1B).
 */
public class ScoutReconGoal extends Goal {

    public static final int LOOK_DURATION_TICKS = 82; // 4.125s animation length
    public static final int LOOK_COOLDOWN_TICKS = 10 * 20; // 10 seconds = 200 ticks
    public static final int ALERT_HORN_DURATION_TICKS = 60; // 3.0s horn alert
    public static final int REQUIRED_EXPOSURE_TICKS = 15; // ~0.75s exposure in 150° cone required to trigger alarm
    public static final double SCAN_RANGE_BLOCKS = 30.0D;
    public static final double SCAN_RANGE_SQ = SCAN_RANGE_BLOCKS * SCAN_RANGE_BLOCKS;
    // 150 degree frontal arc: cos(75°) ≈ 0.258819
    public static final double HORIZONTAL_COS_HALF_ANGLE = Math.cos(Math.toRadians(75.0D));

    private final PillagerScoutEntity scout;
    private BlockPos currentWaypoint = null;
    private int stateTicks = 0;
    private int moveTicks = 0;
    private int lookCooldownTicks = 0;
    private Player detectedPlayer = null;
    private Player spottedPlayer = null;
    private int playerExposureTicks = 0;

    private enum ReconSubState {
        MOVING,
        LOOKING,
        ALERTING
    }

    private ReconSubState currentState = ReconSubState.MOVING;

    public ScoutReconGoal(PillagerScoutEntity scout) {
        this.scout = scout;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.scout.isAlive()) return false;
        // Yield if already engaged in combat
        if (this.scout.getTarget() != null && this.scout.getTarget().isAlive()) return false;
        if (this.scout.areDaggersDrawn()) return false;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.scout.isAlive()) return false;
        if (this.scout.getTarget() != null && this.scout.getTarget().isAlive()) return false;
        if (this.scout.areDaggersDrawn()) return false;
        return true;
    }

    @Override
    public void start() {
        this.currentState = ReconSubState.MOVING;
        this.moveTicks = 0;
        this.stateTicks = 0;
        this.lookCooldownTicks = 0;
        this.detectedPlayer = null;
        this.spottedPlayer = null;
        this.playerExposureTicks = 0;
        pickNextSubregionWaypoint();
    }

    @Override
    public void stop() {
        this.scout.getNavigation().stop();
        if (this.scout.getActionState() == PillagerScoutEntity.ACTION_LOOK ||
            this.scout.getActionState() == PillagerScoutEntity.ACTION_ALERT) {
            this.scout.setActionState(PillagerScoutEntity.ACTION_NONE, 0);
        }
        if (this.currentState == ReconSubState.ALERTING) {
            this.scout.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        this.spottedPlayer = null;
        this.playerExposureTicks = 0;
    }

    @Override
    public void tick() {
        if (this.scout.level().isClientSide) return;

        switch (this.currentState) {
            case MOVING -> tickMoving();
            case LOOKING -> tickLooking();
            case ALERTING -> tickAlerting();
        }
    }

    private void tickMoving() {
        this.moveTicks++;
        if (this.lookCooldownTicks > 0) {
            this.lookCooldownTicks--;
        }

        // If interrupted by damage, scout state changes outside this goal
        if (this.scout.getActionState() != PillagerScoutEntity.ACTION_NONE) {
            return;
        }

        boolean arrivedAtWaypoint = this.currentWaypoint == null || this.scout.blockPosition().closerThan(this.currentWaypoint, 3.5D);

        // Can enter look state if 10-second cooldown has expired and either arrived at waypoint or moved for 10 seconds
        if (this.lookCooldownTicks <= 0 && (arrivedAtWaypoint || this.moveTicks >= LOOK_COOLDOWN_TICKS)) {
            enterLookState();
            return;
        }

        // If arrived at waypoint before cooldown has expired, pick next waypoint to keep moving
        if (arrivedAtWaypoint) {
            pickNextSubregionWaypoint();
        }

        if (this.scout.getNavigation().isDone() || this.moveTicks % 40 == 0) {
            this.scout.getNavigation().moveTo(
                    this.currentWaypoint.getX() + 0.5D,
                    this.currentWaypoint.getY(),
                    this.currentWaypoint.getZ() + 0.5D,
                    1.20D
            );
        }
    }

    private void enterLookState() {
        this.currentState = ReconSubState.LOOKING;
        this.stateTicks = LOOK_DURATION_TICKS;
        this.spottedPlayer = null;
        this.playerExposureTicks = 0;
        this.scout.getNavigation().stop();
        this.scout.setActionState(PillagerScoutEntity.ACTION_LOOK, LOOK_DURATION_TICKS + 20);
    }

    private void tickLooking() {
        // Option 2A: Check if interrupted by damage or external force
        if (this.scout.getActionState() != PillagerScoutEntity.ACTION_LOOK) {
            this.currentState = ReconSubState.MOVING;
            this.moveTicks = 0;
            this.lookCooldownTicks = LOOK_COOLDOWN_TICKS;
            this.spottedPlayer = null;
            this.playerExposureTicks = 0;
            pickNextSubregionWaypoint();
            return;
        }

        this.scout.getNavigation().stop();
        this.stateTicks--;

        // Accumulate player exposure without decay if player is in the 150-degree arc with line-of-sight
        Player candidate = scanForHostilePlayer();
        if (candidate != null) {
            this.spottedPlayer = candidate;
            this.playerExposureTicks++;
        }

        // Trigger alarm AFTER the look animation completes (conclusion scan)
        if (this.stateTicks <= 0) {
            if (this.spottedPlayer != null && this.spottedPlayer.isAlive() &&
                (this.playerExposureTicks >= REQUIRED_EXPOSURE_TICKS || candidate != null)) {
                enterAlertState(this.spottedPlayer);
            } else {
                // Clear horizon: resume swift patrol with 10-second cooldown
                this.scout.setActionState(PillagerScoutEntity.ACTION_NONE, 0);
                this.currentState = ReconSubState.MOVING;
                this.moveTicks = 0;
                this.lookCooldownTicks = LOOK_COOLDOWN_TICKS;
                this.spottedPlayer = null;
                this.playerExposureTicks = 0;
                pickNextSubregionWaypoint();
            }
        }
    }

    private void enterAlertState(Player player) {
        this.currentState = ReconSubState.ALERTING;
        this.detectedPlayer = player;
        this.stateTicks = ALERT_HORN_DURATION_TICKS;

        this.scout.getNavigation().stop();
        this.scout.setActionState(PillagerScoutEntity.ACTION_ALERT, ALERT_HORN_DURATION_TICKS + 20);
        this.scout.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOAT_HORN));

        // Face the detected threat
        this.scout.getLookControl().setLookAt(player, 40.0F, 40.0F);

        // Play Goat Horn "Seek" sound (item.goat_horn.sound.2) with 16.0F volume (subregion-wide audible)
        this.scout.level().playSound(
                null,
                this.scout.blockPosition(),
                SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(2).value(),
                SoundSource.HOSTILE,
                16.0F,
                1.0F
        );

        // Notify spotted player
        player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("§c🚨 [SPOTTED] A Pillager Scout has spotted you and sounded the alarm!"),
                true
        );

        // Particle alert cue
        if (this.scout.level() instanceof ServerLevel sl) {
            sl.sendParticles(
                    ParticleTypes.ANGRY_VILLAGER,
                    this.scout.getX(),
                    this.scout.getY() + 2.0D,
                    this.scout.getZ(),
                    8,
                    0.35D, 0.35D, 0.35D, 0.02D
            );
        }

        // Trigger SubregionPatrolManager reinforcements
        int rx = Math.floorDiv(this.scout.getBlockX(), 128);
        int rz = Math.floorDiv(this.scout.getBlockZ(), 128);
        int localX = Math.floorMod(this.scout.getBlockX(), 128);
        int localZ = Math.floorMod(this.scout.getBlockZ(), 128);
        int sx = localX >= 64 ? 1 : 0;
        int sz = localZ >= 64 ? 1 : 0;

        if (this.scout.level() instanceof ServerLevel sl) {
            SubregionPatrolManager.triggerScoutReinforcements(sl, rx, rz, sx, sz, player);
        }
    }

    private void tickAlerting() {
        this.scout.getNavigation().stop();
        if (this.detectedPlayer != null && this.detectedPlayer.isAlive()) {
            this.scout.getLookControl().setLookAt(this.detectedPlayer, 30.0F, 30.0F);
        }

        this.stateTicks--;
        if (this.stateTicks <= 0) {
            // Option 1B: Horn sequence completed! Draw daggers and engage player in melee to stall them
            this.scout.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            this.scout.setActionState(PillagerScoutEntity.ACTION_NONE, 0);
            this.scout.setDaggersDrawn(true);

            if (this.detectedPlayer != null && this.detectedPlayer.isAlive()) {
                this.scout.setTarget(this.detectedPlayer);
            }

            this.currentState = ReconSubState.MOVING;
            this.moveTicks = 0;
            this.detectedPlayer = null;
            this.spottedPlayer = null;
            this.playerExposureTicks = 0;
        }
    }

    /**
     * Evaluates all players within range for 150-degree horizontal field of view and line-of-sight.
     */
    private Player scanForHostilePlayer() {
        Player closest = null;
        double closestDistSq = Double.MAX_VALUE;

        Vec3 scoutPos = this.scout.getEyePosition();
        Vec3 lookVec = this.scout.getViewVector(1.0F);
        double lookHorizLen = Math.sqrt(lookVec.x * lookVec.x + lookVec.z * lookVec.z);
        if (lookHorizLen < 0.0001D) {
            return null;
        }

        for (Player player : this.scout.level().players()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (AlliedFactionHelper.isAllied(this.scout, player)) {
                continue;
            }

            double distSq = this.scout.distanceToSqr(player);
            if (distSq > SCAN_RANGE_SQ) {
                continue;
            }

            if (!this.scout.hasLineOfSight(player)) {
                continue;
            }

            Vec3 toPlayer = player.getEyePosition().subtract(scoutPos);
            double toPlayerHorizLen = Math.sqrt(toPlayer.x * toPlayer.x + toPlayer.z * toPlayer.z);
            if (toPlayerHorizLen < 0.0001D) {
                return player; // Right on top of scout
            }

            double dot = (lookVec.x * toPlayer.x + lookVec.z * toPlayer.z) / (lookHorizLen * toPlayerHorizLen);
            if (dot >= HORIZONTAL_COS_HALF_ANGLE) {
                if (distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closest = player;
                }
            }
        }

        return closest;
    }

    private void pickNextSubregionWaypoint() {
        int rx = Math.floorDiv(this.scout.getBlockX(), 128);
        int rz = Math.floorDiv(this.scout.getBlockZ(), 128);
        int localX = Math.floorMod(this.scout.getBlockX(), 128);
        int localZ = Math.floorMod(this.scout.getBlockZ(), 128);
        int sx = localX >= 64 ? 1 : 0;
        int sz = localZ >= 64 ? 1 : 0;

        int minX = rx * 128 + sx * 64 + 8;
        int maxX = rx * 128 + sx * 64 + 56;
        int minZ = rz * 128 + sz * 64 + 8;
        int maxZ = rz * 128 + sz * 64 + 56;

        int targetX = minX + this.scout.getRandom().nextInt(Math.max(1, maxX - minX));
        int targetZ = minZ + this.scout.getRandom().nextInt(Math.max(1, maxZ - minZ));
        if (this.scout.level() instanceof ServerLevel sl) {
            int surfaceY = EnemyEncounterSpawner.findDryLandSurfaceY(sl, targetX, targetZ);
            if (surfaceY != Integer.MIN_VALUE) {
                this.currentWaypoint = new BlockPos(targetX, surfaceY, targetZ);
                return;
            }
        }
        this.currentWaypoint = new BlockPos(targetX, this.scout.getBlockY(), targetZ);
    }
}
