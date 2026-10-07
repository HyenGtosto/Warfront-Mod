package com.warfront.client.hud;

import com.warfront.network.ActiveMissionHudPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Client-side mini transparent HUD window rendering active mission info,
 * flexible objective description, dynamic progress string, and remaining timer.
 */
public final class ActiveMissionHudOverlay {

    private static ActiveMissionHudPayload currentPayload = null;

    private ActiveMissionHudOverlay() {
    }

    public static void updateHud(ActiveMissionHudPayload payload) {
        currentPayload = payload;
    }

    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (currentPayload == null || !currentPayload.hasActiveMission()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null) {
            return;
        }

        Font font = mc.font;
        int screenWidth = guiGraphics.guiWidth();

        int width = 185;
        int height = 62;
        int x = screenWidth - width - 10;
        int y = 10;

        // 1. Semi-transparent dark slate background
        guiGraphics.fill(x, y, x + width, y + height, 0x990A0E16);

        // 2. High-tech border
        guiGraphics.fill(x, y, x + width, y + 1, 0xBB335577);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, 0xBB335577);
        guiGraphics.fill(x, y, x + 1, y + height, 0xBB335577);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, 0xBB335577);

        // 3. Left Accent Status Stripe
        int accentColor = currentPayload.isDefense() ? 0xFFFF5533 : 0xFF00DD88;
        guiGraphics.fill(x + 1, y + 1, x + 4, y + height - 1, accentColor);

        // 4. Header: Mission Name
        String name = currentPayload.missionName();
        if (name == null || name.isEmpty()) {
            name = "Active Operation";
        }
        guiGraphics.drawString(font, "§e§l" + name, x + 8, y + 4, 0xFFFFDD33, false);

        // 5. Sector and Region Coordinate Line
        String sectorText = String.format("§7Sector [%d,%d] — Reg (%d,%d)",
                currentPayload.subX(), currentPayload.subZ(), currentPayload.regionX(), currentPayload.regionZ());
        guiGraphics.drawString(font, sectorText, x + 8, y + 16, 0xFFAAAAAA, false);

        // 6. Objective Description Line
        String objDesc = currentPayload.objectiveDescription();
        if (objDesc == null || objDesc.isEmpty()) {
            objDesc = "Engage Targets";
        }
        guiGraphics.drawString(font, "§f" + objDesc, x + 8, y + 27, 0xFFFFFFFF, false);

        // 7. Progress Line & Bar
        String progressDisp = currentPayload.progressDisplayString();
        if (progressDisp == null || progressDisp.isEmpty()) {
            progressDisp = currentPayload.currentProgress() + "/" + currentPayload.targetProgress();
        }
        String progressText = "§7Status: §a" + progressDisp;
        guiGraphics.drawString(font, progressText, x + 8, y + 38, 0xFFFFFFFF, false);

        int barX = x + 105;
        int barY = y + 40;
        int barW = 70;
        int barH = 5;
        guiGraphics.fill(barX, barY, barX + barW, barY + barH, 0xFF222830);
        float fraction = Math.clamp((float) currentPayload.currentProgress() / Math.max(1, currentPayload.targetProgress()), 0.0f, 1.0f);
        int fillW = (int) (barW * fraction);
        if (fillW > 0) {
            guiGraphics.fill(barX, barY, barX + fillW, barY + barH, accentColor);
        }

        // 8. Remaining Timer Line
        long totalSec = Math.max(0, currentPayload.remainingTicks() / 20);
        long mins = totalSec / 60;
        long secs = totalSec % 60;
        String timeText = String.format("§6Time Left: §f%02d:%02d", mins, secs);
        guiGraphics.drawString(font, timeText, x + 8, y + 50, 0xFFFFCC66, false);
    }
}
