package com.warfront.client.gui;

import com.warfront.Warfront;
import com.warfront.block.WarfrontBlocks;
import com.warfront.menu.FriendlyBallistaMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class FriendlyBallistaScreen extends AbstractContainerScreen<FriendlyBallistaMenu> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "textures/gui/container/friendly_mortar.png");

    public FriendlyBallistaScreen(FriendlyBallistaMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 72;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int left = this.leftPos;
        int top = this.topPos;
        guiGraphics.blit(TEXTURE, left, top, 0, 0, this.imageWidth, this.imageHeight);

        // Render chambered bolt in chamber slot (x=44, y=53)
        if (this.menu.isBoltChambered()) {
            ItemStack rep = new ItemStack(WarfrontBlocks.BALLISTA_BOLT_ITEM.get());
            guiGraphics.renderItem(rep, left + 44, top + 53);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0x404040, false);
        guiGraphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x404040, false);

        Component statusText;
        int color;
        if (this.menu.isDeactivated()) {
            statusText = Component.translatable("screen.warfront.friendly_ballista.deactivated");
            color = 0xAA0000;
        } else if (this.menu.isBurnPaused()) {
            statusText = Component.translatable("screen.warfront.friendly_ballista.burn_paused");
            color = 0xD4A017;
        } else if (this.menu.getMaxFuelTicks() <= 0) {
            statusText = Component.translatable("screen.warfront.friendly_ballista.offline");
            color = 0xAA0000;
        } else {
            int totalSec = this.menu.getMaxFuelTicks() / 20;
            String timeStr = String.format("%02d:%02d", totalSec / 60, totalSec % 60);
            statusText = Component.translatable("screen.warfront.friendly_ballista.max_fuel_time", timeStr);
            color = 0x2E7D32;
        }

        guiGraphics.drawString(this.font, statusText, 44, 40, color, false);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);

        // Chamber slot tooltip
        int chamberX = this.leftPos + 43;
        int chamberY = this.topPos + 52;
        if (mouseX >= chamberX && mouseX <= chamberX + 18 && mouseY >= chamberY && mouseY <= chamberY + 18) {
            Component tooltip = this.menu.isBoltChambered()
                    ? Component.translatable("screen.warfront.friendly_ballista.chamber_loaded")
                    : Component.translatable("screen.warfront.friendly_ballista.chamber_empty");
            guiGraphics.renderComponentTooltip(this.font, List.of(tooltip), mouseX, mouseY);
        }
    }
}
