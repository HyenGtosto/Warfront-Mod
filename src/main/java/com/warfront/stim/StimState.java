package com.warfront.stim;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Per-player persistent state tracking recent bio-stim usage, tolerance decay,
 * overdose lock status, and Acrobatics mobility cooldowns.
 */
public final class StimState {

    public static final Codec<StimState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("recentStimCount", 0).forGetter(StimState::getRecentStimCount),
            Codec.INT.optionalFieldOf("decayTicksRemaining", 0).forGetter(StimState::getDecayTicksRemaining),
            Codec.BOOL.optionalFieldOf("overdoseLocked", false).forGetter(StimState::isOverdoseLocked),
            Codec.INT.optionalFieldOf("doubleJumpCooldownTicks", 0).forGetter(StimState::getDoubleJumpCooldownTicks),
            Codec.INT.optionalFieldOf("dashCooldownTicks", 0).forGetter(StimState::getDashCooldownTicks)
    ).apply(instance, StimState::new));

    private int recentStimCount;
    private int decayTicksRemaining;
    private boolean overdoseLocked;
    private int doubleJumpCooldownTicks;
    private int dashCooldownTicks;

    public StimState() {
        this(0, 0, false, 0, 0);
    }

    public StimState(int recentStimCount, int decayTicksRemaining, boolean overdoseLocked,
                     int doubleJumpCooldownTicks, int dashCooldownTicks) {
        this.recentStimCount = Math.max(0, recentStimCount);
        this.decayTicksRemaining = Math.max(0, decayTicksRemaining);
        this.overdoseLocked = overdoseLocked && this.recentStimCount > 0;
        this.doubleJumpCooldownTicks = Math.max(0, doubleJumpCooldownTicks);
        this.dashCooldownTicks = Math.max(0, dashCooldownTicks);
    }

    public int getRecentStimCount() {
        return recentStimCount;
    }

    public int getDecayTicksRemaining() {
        return decayTicksRemaining;
    }

    public boolean isOverdoseLocked() {
        return overdoseLocked;
    }

    public int getDoubleJumpCooldownTicks() {
        return doubleJumpCooldownTicks;
    }

    public int getDashCooldownTicks() {
        return dashCooldownTicks;
    }

    /**
     * Records a stim injection and returns true if this injection pushed the player into overdose.
     */
    public boolean recordStimUse() {
        recentStimCount++;
        decayTicksRemaining = StimConstants.TOLERANCE_DECAY_TICKS;
        if (recentStimCount > StimConstants.OVERDOSE_THRESHOLD) {
            recentStimCount = StimConstants.OVERDOSE_THRESHOLD;
            overdoseLocked = true;
            return true;
        }
        return false;
    }

    public void startDoubleJumpCooldown() {
        this.doubleJumpCooldownTicks = StimConstants.MOBILITY_COOLDOWN_TICKS;
    }

    public void startDashCooldown() {
        this.dashCooldownTicks = StimConstants.MOBILITY_COOLDOWN_TICKS;
    }

    /**
     * Advances timers by one server tick. Returns true if tolerance/lock state changed and requires sync.
     */
    public boolean tickServer() {
        boolean changed = false;

        if (doubleJumpCooldownTicks > 0) {
            doubleJumpCooldownTicks--;
        }
        if (dashCooldownTicks > 0) {
            dashCooldownTicks--;
        }

        if (recentStimCount > 0) {
            if (decayTicksRemaining > 0) {
                decayTicksRemaining--;
            }
            if (decayTicksRemaining <= 0) {
                recentStimCount = Math.max(0, recentStimCount - 1);
                changed = true;
                if (recentStimCount > 0) {
                    decayTicksRemaining = StimConstants.TOLERANCE_DECAY_TICKS;
                } else {
                    decayTicksRemaining = 0;
                    if (overdoseLocked) {
                        overdoseLocked = false;
                    }
                }
            }
        } else if (overdoseLocked || decayTicksRemaining != 0) {
            overdoseLocked = false;
            decayTicksRemaining = 0;
            changed = true;
        }

        return changed;
    }

    public StimState copy() {
        return new StimState(recentStimCount, decayTicksRemaining, overdoseLocked,
                doubleJumpCooldownTicks, dashCooldownTicks);
    }
}
