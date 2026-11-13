package com.warfront.stim;

/**
 * Centralized tunable constants for Zombie faction bio-stims and the shared overdose system.
 */
public final class StimConstants {

    private StimConstants() {
    }

    /** Active buff duration for all stim injections (30 seconds). */
    public static final int STIM_DURATION_TICKS = 30 * 20;

    /** Time required for recent stim tolerance count to decay by 1 (40 seconds). */
    public static final int TOLERANCE_DECAY_TICKS = 40 * 20;

    /** Maximum safe consecutive stim count; exceeding this triggers overdose. */
    public static final int OVERDOSE_THRESHOLD = 2;

    /** Short Wither duration applied on overdose (6 seconds). */
    public static final int OVERDOSE_WITHER_DURATION_TICKS = 6 * 20;

    /** Wither amplifier applied on overdose (0 = Level 1). */
    public static final int OVERDOSE_WITHER_AMPLIFIER = 0;

    /** Max health reduction for Combat and Acrobatics stims (-2.0 HP = -1 Heart). */
    public static final double STIM_MAX_HEALTH_COST = -2.0D;

    /** Shared item-use cooldown across all stims to prevent accidental double-clicks (1 second). */
    public static final int ITEM_USE_COOLDOWN_TICKS = 20;

    /** Mobility cooldown for Acrobatics Double Jump and Dash (4 seconds each). */
    public static final int MOBILITY_COOLDOWN_TICKS = 4 * 20;

    /** Maximum tick gap between two key presses to trigger a directional WASD dash. */
    public static final int DOUBLE_TAP_WINDOW_TICKS = 6;

    /** Upward impulse applied on Acrobatics Double Jump. */
    public static final double DOUBLE_JUMP_VERTICAL_VELOCITY = 0.56D;

    /** Horizontal speed and slight lift applied on Acrobatics Directional Dash. */
    public static final double DASH_HORIZONTAL_SPEED = 1.15D;
    public static final double DASH_VERTICAL_LIFT = 0.22D;

    /** Radius in blocks within which Combat Stim marks hostile entities. */
    public static final double HOSTILE_MARK_RANGE = 48.0D;
}
