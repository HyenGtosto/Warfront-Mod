package com.warfront.stim;

/**
 * Common client-accessible snapshot of the local player's stim tolerance and mobility cooldowns.
 * Contains no client-only Minecraft imports so common network payloads can update it safely.
 */
public final class StimClientState {

    private static int recentStimCount;
    private static int decayTicksRemaining;
    private static boolean overdoseLocked;
    private static int doubleJumpCooldownTicks;
    private static int dashCooldownTicks;

    private StimClientState() {
    }

    public static void update(int recentCount, int decayTicks, boolean locked,
                              int doubleJumpCd, int dashCd) {
        recentStimCount = Math.max(0, recentCount);
        decayTicksRemaining = Math.max(0, decayTicks);
        overdoseLocked = locked && recentStimCount > 0;
        doubleJumpCooldownTicks = Math.max(0, doubleJumpCd);
        dashCooldownTicks = Math.max(0, dashCd);
    }

    public static void tickClient() {
        if (doubleJumpCooldownTicks > 0) {
            doubleJumpCooldownTicks--;
        }
        if (dashCooldownTicks > 0) {
            dashCooldownTicks--;
        }
        if (recentStimCount > 0 && decayTicksRemaining > 0) {
            decayTicksRemaining--;
        }
    }

    public static void clear() {
        update(0, 0, false, 0, 0);
    }

    public static int getRecentStimCount() {
        return recentStimCount;
    }

    public static int getDecayTicksRemaining() {
        return decayTicksRemaining;
    }

    public static boolean isOverdoseLocked() {
        return overdoseLocked;
    }

    public static int getDoubleJumpCooldownTicks() {
        return doubleJumpCooldownTicks;
    }

    public static int getDashCooldownTicks() {
        return dashCooldownTicks;
    }
}
