package com.warfront.client.faction;

import com.mojang.authlib.GameProfile;
import com.warfront.faction.FactionChatMessage;
import com.warfront.faction.FactionMember;
import com.warfront.faction.FactionMessageType;
import com.warfront.network.faction.AcceptJoinRequestPayload;
import com.warfront.network.faction.CreateFactionPayload;
import com.warfront.network.faction.DisbandFactionPayload;
import com.warfront.network.faction.InvitePlayerPayload;
import com.warfront.network.faction.JoinFactionRequestPayload;
import com.warfront.network.faction.KickPlayerPayload;
import com.warfront.network.faction.LeaveFactionPayload;
import com.warfront.network.faction.SendFactionChatPayload;
import com.warfront.network.faction.UpdateFactionSettingsPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public class FactionPanel {

    public enum Subview {
        MAIN,
        CREATE,
        SETTINGS
    }

    private static final int[] PRESET_COLORS = {
            0x21BCFF, // Humanity Cyan
            0xE63946, // Crimson
            0xFFB703, // Amber Gold
            0x2A9D8F, // Emerald Green
            0x7209B7, // Amethyst Purple
            0xFB8500, // Tiger Orange
            0x1D3557, // Deep Navy
            0x55A630, // Lime
            0xB5179E, // Magenta
            0xEDE0D4  // Ivory White
    };

    public enum ConfirmationType {
        NONE,
        KICK,
        DISBAND
    }

    private Subview subview = Subview.MAIN;
    private boolean showJoinPopup = false;
    private ConfirmationType confirmationType = ConfirmationType.NONE;
    private UUID confirmTargetUuid = null;
    private String confirmTargetName = "";

    // Dimensions
    private int x;
    private int y;
    private int width;
    private int height;

    // Scrolling
    private int memberScrollOffset = 0;
    private int chatScrollOffset = 0;

    // Active member popup (Kick / Leave)
    public static class MemberPopup {
        final UUID targetUuid;
        final String targetName;
        final int x, y, width, height;
        final boolean isKick;

        MemberPopup(UUID targetUuid, String targetName, int x, int y, int width, int height, boolean isKick) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.isKick = isKick;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx <= x + width && my >= y && my <= y + height;
        }
    }

    private MemberPopup activeMemberPopup = null;

    // Interactive clickable join request areas: (requesterUuid, left, top, right, bottom)
    private record ClickableAction(UUID requesterUuid, int left, int top, int right, int bottom) {}
    private final List<ClickableAction> activeClickableActions = new ArrayList<>();

    // Color picker state
    private int selectedColor = 0x21BCFF;

    // Widgets
    // Not in faction widgets
    private Button createFactionBtn;
    private Button joinFactionBtn;

    // Join popup widgets
    private EditBox joinNameBox;
    private Button joinSendBtn;
    private Button joinCancelBtn;

    // Create faction widgets
    private EditBox createNameBox;
    private EditBox createDescBox;
    private EditBox createHexBox;
    private Button createSubmitBtn;
    private Button createCancelBtn;

    // In faction widgets
    private Button settingsBtn;
    private EditBox inviteBox;
    private Button inviteBtn;
    private EditBox chatBox;
    private Button chatSendBtn;

    // Settings widgets
    private EditBox settingsNameBox;
    private EditBox settingsDescBox;
    private EditBox settingsHexBox;
    private Button settingsSaveBtn;
    private Button settingsDisbandBtn;
    private Button settingsBackBtn;

    private boolean panelVisible = false;
    private final List<Button> allButtons = new ArrayList<>();
    private final List<EditBox> allEditBoxes = new ArrayList<>();

    public FactionPanel() {
        FactionClientState.get().setOnStateChanged(this::onStateChanged);
    }

    public void setPanelVisible(boolean visible) {
        this.panelVisible = visible;
        if (!visible) {
            activeMemberPopup = null;
            confirmationType = ConfirmationType.NONE;
            confirmTargetUuid = null;
            confirmTargetName = "";
        }
        updateWidgetVisibility();
    }

    public boolean isPanelVisible() {
        return panelVisible;
    }

    private void onStateChanged() {
        if (FactionClientState.get().isInFaction()) {
            if (subview == Subview.CREATE) {
                subview = Subview.MAIN;
            }
            showJoinPopup = false;
        } else {
            if (subview == Subview.SETTINGS) {
                subview = Subview.MAIN;
            }
            confirmationType = ConfirmationType.NONE;
            confirmTargetUuid = null;
            confirmTargetName = "";
        }
        updateWidgetVisibility();
    }

    public void init(int x, int y, int width, int height, Font font, java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> widgetConsumer) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        allButtons.clear();
        allEditBoxes.clear();
        activeMemberPopup = null;
        confirmationType = ConfirmationType.NONE;
        confirmTargetUuid = null;
        confirmTargetName = "";

        int centerX = x + width / 2;
        int centerY = y + height / 2;

        // 1. Not in faction main buttons
        createFactionBtn = addButton(Button.builder(Component.literal("Create a faction"), b -> {
            subview = Subview.CREATE;
            selectedColor = PRESET_COLORS[0];
            if (createHexBox != null) createHexBox.setValue(String.format("%06X", selectedColor));
            updateWidgetVisibility();
        }).bounds(centerX - 90, centerY - 28, 180, 24).build(), widgetConsumer);

        joinFactionBtn = addButton(Button.builder(Component.literal("Join a Faction"), b -> {
            showJoinPopup = true;
            if (joinNameBox != null) joinNameBox.setValue("");
            updateWidgetVisibility();
        }).bounds(centerX - 90, centerY + 4, 180, 24).build(), widgetConsumer);

        // 2. Join Popup widgets
        int popupW = 200;
        int popupH = 80;
        int popupX = centerX - popupW / 2;
        int popupY = centerY - popupH / 2;

        joinNameBox = addEditBox(new EditBox(font, popupX + 16, popupY + 22, popupW - 32, 18, Component.literal("Faction Name")), widgetConsumer);
        joinNameBox.setHint(Component.literal("Enter faction name..."));

        joinSendBtn = addButton(Button.builder(Component.literal("Send"), b -> onSendJoinRequest())
                .bounds(popupX + 16, popupY + 46, (popupW - 38) / 2, 20).build(), widgetConsumer);

        joinCancelBtn = addButton(Button.builder(Component.literal("Cancel"), b -> {
            showJoinPopup = false;
            updateWidgetVisibility();
        }).bounds(popupX + 22 + (popupW - 38) / 2, popupY + 46, (popupW - 38) / 2, 20).build(), widgetConsumer);

        // 3. Create Faction Form widgets
        int formW = 240;
        int formX = centerX - formW / 2;
        int formY = y + 25;

        createNameBox = addEditBox(new EditBox(font, formX, formY + 20, formW, 18, Component.literal("Name")), widgetConsumer);
        createNameBox.setHint(Component.literal("Faction Name (required)"));
        createNameBox.setMaxLength(24);

        createDescBox = addEditBox(new EditBox(font, formX, formY + 55, formW, 18, Component.literal("Description")), widgetConsumer);
        createDescBox.setHint(Component.literal("Description (optional)"));
        createDescBox.setMaxLength(100);

        createHexBox = addEditBox(new EditBox(font, formX + 40, formY + 90, 80, 18, Component.literal("Hex Color")), widgetConsumer);
        createHexBox.setValue(String.format("%06X", selectedColor));
        createHexBox.setResponder(this::onHexChanged);

        createSubmitBtn = addButton(Button.builder(Component.literal("§a§lCREATE FACTION"), b -> onSubmitCreateFaction())
                .bounds(formX, formY + 140, formW / 2 - 4, 22).build(), widgetConsumer);

        createCancelBtn = addButton(Button.builder(Component.literal("Cancel"), b -> {
            subview = Subview.MAIN;
            updateWidgetVisibility();
        }).bounds(formX + formW / 2 + 4, formY + 140, formW / 2 - 4, 22).build(), widgetConsumer);

        // 4. In Faction widgets
        int leftColW = Math.max(120, (int) (width * 0.32D));
        int rightColX = x + leftColW + 6;
        int rightColW = width - leftColW - 12;

        settingsBtn = addButton(Button.builder(Component.literal("⚙ Settings"), b -> {
            subview = Subview.SETTINGS;
            FactionClientState state = FactionClientState.get();
            if (settingsNameBox != null) settingsNameBox.setValue(state.getFactionName());
            if (settingsDescBox != null) settingsDescBox.setValue(state.getDescription());
            selectedColor = state.getColor();
            if (settingsHexBox != null) settingsHexBox.setValue(String.format("%06X", selectedColor));
            updateWidgetVisibility();
        }).bounds(x + width - 75, y + 5, 70, 18).build(), widgetConsumer);

        // Invite controls (Bottom Left)
        int inviteY = y + height - 44;
        inviteBtn = addButton(Button.builder(Component.literal("Invite Player"), b -> onInvitePlayer())
                .bounds(x + 6, inviteY, leftColW - 12, 18).build(), widgetConsumer);

        inviteBox = addEditBox(new EditBox(font, x + 6, inviteY + 22, leftColW - 12, 18, Component.literal("Invite")), widgetConsumer);
        inviteBox.setHint(Component.literal("Player username..."));

        // Chat controls (Bottom Right)
        int chatY = y + height - 24;
        int chatInputW = rightColW - 54;
        chatBox = addEditBox(new EditBox(font, rightColX, chatY, chatInputW, 18, Component.literal("Chat")), widgetConsumer);
        chatBox.setHint(Component.literal("Type faction message..."));
        chatBox.setMaxLength(256);

        chatSendBtn = addButton(Button.builder(Component.literal("Send"), b -> onSendChat())
                .bounds(rightColX + chatInputW + 4, chatY, 48, 18).build(), widgetConsumer);

        // 5. Settings widgets
        settingsNameBox = addEditBox(new EditBox(font, formX, formY + 20, formW, 18, Component.literal("Settings Name")), widgetConsumer);
        settingsNameBox.setMaxLength(24);

        settingsDescBox = addEditBox(new EditBox(font, formX, formY + 55, formW, 18, Component.literal("Settings Desc")), widgetConsumer);
        settingsDescBox.setMaxLength(100);

        settingsHexBox = addEditBox(new EditBox(font, formX + 40, formY + 90, 80, 18, Component.literal("Settings Hex")), widgetConsumer);
        settingsHexBox.setResponder(this::onHexChanged);

        settingsSaveBtn = addButton(Button.builder(Component.literal("§aSave Changes"), b -> onSaveSettings())
                .bounds(formX, formY + 140, formW / 2 - 4, 20).build(), widgetConsumer);

        settingsBackBtn = addButton(Button.builder(Component.literal("Back"), b -> {
            subview = Subview.MAIN;
            updateWidgetVisibility();
        }).bounds(formX + formW / 2 + 4, formY + 140, formW / 2 - 4, 20).build(), widgetConsumer);

        settingsDisbandBtn = addButton(Button.builder(Component.literal("§c§lDISBAND FACTION"), b -> onDisbandFaction())
                .bounds(formX, formY + 168, formW, 20).build(), widgetConsumer);

        updateWidgetVisibility();
    }

    private Button addButton(Button btn, java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> consumer) {
        allButtons.add(btn);
        consumer.accept(btn);
        return btn;
    }

    private EditBox addEditBox(EditBox box, java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> consumer) {
        allEditBoxes.add(box);
        consumer.accept(box);
        return box;
    }

    private void onHexChanged(String hex) {
        try {
            String clean = hex.replace("#", "").trim();
            if (clean.length() == 6) {
                selectedColor = (int) Long.parseLong(clean, 16);
            }
        } catch (Exception ignored) {
        }
    }

    private void onSendJoinRequest() {
        if (joinNameBox != null) {
            String target = joinNameBox.getValue().trim();
            if (!target.isEmpty()) {
                PacketDistributor.sendToServer(new JoinFactionRequestPayload(target));
            }
        }
    }

    private void onSubmitCreateFaction() {
        if (createNameBox != null) {
            String name = createNameBox.getValue().trim();
            String desc = createDescBox != null ? createDescBox.getValue().trim() : "";
            if (!name.isEmpty()) {
                PacketDistributor.sendToServer(new CreateFactionPayload(name, desc, selectedColor));
            }
        }
    }

    private void onInvitePlayer() {
        if (inviteBox != null) {
            String target = inviteBox.getValue().trim();
            if (!target.isEmpty()) {
                PacketDistributor.sendToServer(new InvitePlayerPayload(target));
                inviteBox.setValue("");
            }
        }
    }

    private void onSendChat() {
        if (chatBox != null) {
            String msg = chatBox.getValue().trim();
            if (!msg.isEmpty()) {
                PacketDistributor.sendToServer(new SendFactionChatPayload(msg));
                chatBox.setValue("");
            }
        }
    }

    private void onSaveSettings() {
        if (settingsNameBox != null) {
            String name = settingsNameBox.getValue().trim();
            String desc = settingsDescBox != null ? settingsDescBox.getValue().trim() : "";
            if (!name.isEmpty()) {
                PacketDistributor.sendToServer(new UpdateFactionSettingsPayload(name, desc, selectedColor));
                subview = Subview.MAIN;
                updateWidgetVisibility();
            }
        }
    }

    private void onDisbandFaction() {
        confirmationType = ConfirmationType.DISBAND;
    }

    public void updateWidgetVisibility() {
        if (!panelVisible) {
            for (Button b : allButtons) b.visible = false;
            for (EditBox e : allEditBoxes) e.setVisible(false);
            return;
        }

        boolean inFaction = FactionClientState.get().isInFaction();

        // Hide everything first
        for (Button b : allButtons) b.visible = false;
        for (EditBox e : allEditBoxes) e.setVisible(false);

        if (!inFaction) {
            if (subview == Subview.CREATE) {
                createNameBox.setVisible(true);
                createDescBox.setVisible(true);
                createHexBox.setVisible(true);
                createSubmitBtn.visible = true;
                createCancelBtn.visible = true;
            } else {
                // Main Not in faction
                createFactionBtn.visible = !showJoinPopup;
                joinFactionBtn.visible = !showJoinPopup;

                if (showJoinPopup) {
                    joinNameBox.setVisible(true);
                    joinSendBtn.visible = true;
                    joinCancelBtn.visible = true;
                }
            }
        } else {
            // Player is in faction
            if (subview == Subview.SETTINGS && FactionClientState.get().isOwner()) {
                settingsNameBox.setVisible(true);
                settingsDescBox.setVisible(true);
                settingsHexBox.setVisible(true);
                settingsSaveBtn.visible = true;
                settingsDisbandBtn.visible = true;
                settingsBackBtn.visible = true;
            } else {
                subview = Subview.MAIN;
                settingsBtn.visible = FactionClientState.get().isOwner();
                inviteBtn.visible = true;
                inviteBox.setVisible(true);
                chatBox.setVisible(true);
                chatSendBtn.visible = true;
            }
        }
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        if (!panelVisible) return;

        // Main panel container background
        graphics.fill(x, y, x + width, y + height, 0xEE121417);
        graphics.fill(x, y, x + width, y + 1, 0xFF7A715D);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF7A715D);
        graphics.fill(x, y, x + 1, y + height, 0xFF7A715D);
        graphics.fill(x + width - 1, y, x + width, y + height, 0xFF7A715D);

        boolean inFaction = FactionClientState.get().isInFaction();

        if (!inFaction) {
            if (subview == Subview.CREATE) {
                renderCreateFactionScreen(graphics, font, mouseX, mouseY);
            } else {
                renderNotInFactionScreen(graphics, font, mouseX, mouseY);
            }
        } else {
            if (subview == Subview.SETTINGS) {
                renderSettingsScreen(graphics, font, mouseX, mouseY);
            } else {
                renderInFactionScreen(graphics, font, mouseX, mouseY);
            }
        }

        // Render feedback notification if present
        String feedback = FactionClientState.get().getLastFeedback();
        if (feedback != null) {
            boolean success = FactionClientState.get().isLastFeedbackSuccess();
            int color = success ? 0xFF55FF55 : 0xFFFF5555;
            graphics.drawCenteredString(font, feedback, x + width / 2, y + height - 14, color);
        }
    }

    public void renderPopups(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!panelVisible) return;
        renderMemberPopup(graphics, font, mouseX, mouseY);
        renderConfirmationModal(graphics, font, mouseX, mouseY);
    }

    private void renderNotInFactionScreen(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!showJoinPopup) {
            graphics.drawCenteredString(font, "§6§lFACTION SYSTEM", x + width / 2, y + height / 2 - 50, 0xFFFFFFFF);
            graphics.drawCenteredString(font, "§7Join an existing faction or create your own to claim territory and lead wars.", x + width / 2, y + height / 2 - 38, 0xFFAAAAAA);
        } else {
            // Render modal join box
            int popupW = 200;
            int popupH = 80;
            int popupX = x + width / 2 - popupW / 2;
            int popupY = y + height / 2 - popupH / 2;

            // Background & Border
            graphics.fill(popupX, popupY, popupX + popupW, popupY + popupH, 0xF0181A1D);
            graphics.fill(popupX, popupY, popupX + popupW, popupY + 1, 0xFFCCA84E);
            graphics.fill(popupX, popupY + popupH - 1, popupX + popupW, popupY + popupH, 0xFFCCA84E);
            graphics.fill(popupX, popupY, popupX + 1, popupY + popupH, 0xFFCCA84E);
            graphics.fill(popupX + popupW - 1, popupY, popupX + popupW, popupY + popupH, 0xFFCCA84E);

            graphics.drawCenteredString(font, "§e§lJoin a Faction", popupX + popupW / 2, popupY + 8, 0xFFFFFFFF);
        }
    }

    private void renderCreateFactionScreen(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        int formW = 240;
        int formX = x + width / 2 - formW / 2;
        int formY = y + 25;

        graphics.drawCenteredString(font, "§6§lCREATE A FACTION", x + width / 2, formY, 0xFFFFFFFF);
        graphics.drawString(font, "Faction Name:", formX, formY + 10, 0xFFCCCCCC, false);
        graphics.drawString(font, "Description (optional):", formX, formY + 45, 0xFFCCCCCC, false);
        graphics.drawString(font, "Faction Color:", formX, formY + 80, 0xFFCCCCCC, false);

        // Preview box
        renderColorPicker(graphics, font, formX, formY + 90, mouseX, mouseY);
    }

    private void renderSettingsScreen(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        int formW = 240;
        int formX = x + width / 2 - formW / 2;
        int formY = y + 25;

        graphics.drawCenteredString(font, "§6§lFACTION SETTINGS", x + width / 2, formY, 0xFFFFFFFF);
        graphics.drawString(font, "Faction Name:", formX, formY + 10, 0xFFCCCCCC, false);
        graphics.drawString(font, "Description:", formX, formY + 45, 0xFFCCCCCC, false);
        graphics.drawString(font, "Faction Color:", formX, formY + 80, 0xFFCCCCCC, false);

        renderColorPicker(graphics, font, formX, formY + 90, mouseX, mouseY);
    }

    private void renderColorPicker(GuiGraphics graphics, Font font, int formX, int startY, int mouseX, int mouseY) {
        // Color preview swatch box
        int previewX = formX;
        int previewY = startY;
        int previewSize = 18;
        graphics.fill(previewX, previewY, previewX + previewSize, previewY + previewSize, 0xFF000000 | selectedColor);
        graphics.fill(previewX, previewY, previewX + previewSize, previewY + 1, 0xFFFFFFFF);
        graphics.fill(previewX, previewY + previewSize - 1, previewX + previewSize, previewY + previewSize, 0xFFFFFFFF);
        graphics.fill(previewX, previewY, previewX + 1, previewY + previewSize, 0xFFFFFFFF);
        graphics.fill(previewX + previewSize - 1, previewY, previewX + previewSize, previewY + previewSize, 0xFFFFFFFF);

        // Preset color swatches
        int swatchStartX = formX + 130;
        int swatchSize = 12;
        int swatchGap = 3;
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            int row = i / 5;
            int col = i % 5;
            int sx = swatchStartX + col * (swatchSize + swatchGap);
            int sy = startY + row * (swatchSize + swatchGap);

            graphics.fill(sx, sy, sx + swatchSize, sy + swatchSize, 0xFF000000 | PRESET_COLORS[i]);
            boolean isCur = (PRESET_COLORS[i] == selectedColor);
            int border = isCur ? 0xFFFFFFFF : 0xFF555555;
            graphics.fill(sx, sy, sx + swatchSize, sy + 1, border);
            graphics.fill(sx, sy + swatchSize - 1, sx + swatchSize, sy + swatchSize, border);
            graphics.fill(sx, sy, sx + 1, sy + swatchSize, border);
            graphics.fill(sx + swatchSize - 1, sy, sx + swatchSize, sy + swatchSize, border);
        }
    }

    private void renderInFactionScreen(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        FactionClientState state = FactionClientState.get();

        // 1. Top Header Bar
        int headerH = 26;
        graphics.fill(x + 1, y + 1, x + width - 1, y + headerH, 0xFF1C2026);
        graphics.fill(x + 1, y + headerH, x + width - 1, y + headerH + 1, 0xFF7A715D);

        // Faction color swatch
        int swatchSize = 16;
        int swatchX = x + 8;
        int swatchY = y + 5;
        graphics.fill(swatchX, swatchY, swatchX + swatchSize, swatchY + swatchSize, 0xFF000000 | state.getColor());
        graphics.fill(swatchX, swatchY, swatchX + swatchSize, swatchY + 1, 0xFFFFFFFF);
        graphics.fill(swatchX, swatchY + swatchSize - 1, swatchX + swatchSize, swatchY + swatchSize, 0xFFFFFFFF);
        graphics.fill(swatchX, swatchY, swatchX + 1, swatchY + swatchSize, 0xFFFFFFFF);
        graphics.fill(swatchX + swatchSize - 1, swatchY, swatchX + swatchSize, swatchY + swatchSize, 0xFFFFFFFF);

        // Info icon 'i' next to swatch
        graphics.drawString(font, "§bℹ", swatchX + swatchSize + 4, swatchY + 4, 0xFFFFFFFF, false);

        // Faction Name centered in header
        String titleStr = "§f§l" + state.getFactionName();
        graphics.drawCenteredString(font, titleStr, x + width / 2, y + 8, 0xFFFFFFFF);

        // 2. Left Column: Members
        int leftColW = Math.max(120, (int) (width * 0.32D));
        int contentTop = y + headerH + 2;
        int bottomReserve = 48; // Space for invite button and box
        int memberListH = height - headerH - bottomReserve - 6;

        // Divider line between left and right column
        graphics.fill(x + leftColW, contentTop, x + leftColW + 1, y + height - 2, 0xFF3E434D);

        renderMemberList(graphics, font, x + 2, contentTop, leftColW - 4, memberListH, mouseX, mouseY);

        // 3. Right Column: Custom Chatbox
        int rightColX = x + leftColW + 4;
        int rightColW = width - leftColW - 8;
        int chatListH = height - headerH - 32;

        renderChatList(graphics, font, rightColX, contentTop, rightColW, chatListH, mouseX, mouseY);
    }

    private void renderMemberList(GuiGraphics graphics, Font font, int listX, int listY, int listW, int listH, int mouseX, int mouseY) {
        FactionClientState state = FactionClientState.get();
        List<FactionMember> members = state.getMembers();

        int rowHeight = 20;
        int totalHeight = members.size() * rowHeight;
        int maxScroll = Math.max(0, totalHeight - listH);
        memberScrollOffset = Math.clamp(memberScrollOffset, 0, maxScroll);

        graphics.enableScissor(listX, listY, listX + listW, listY + listH);

        var localPlayer = Minecraft.getInstance().player;
        UUID myUuid = localPlayer != null ? localPlayer.getUUID() : null;

        for (int i = 0; i < members.size(); i++) {
            FactionMember m = members.get(i);
            int rowY = listY + i * rowHeight - memberScrollOffset;

            if (rowY + rowHeight < listY || rowY > listY + listH) {
                continue;
            }

            boolean hovered = (mouseX >= listX && mouseX <= listX + listW && mouseY >= rowY && mouseY <= rowY + rowHeight);
            if (hovered) {
                graphics.fill(listX, rowY, listX + listW, rowY + rowHeight, 0x33FFFFFF);
            }

            // Draw player skin head
            try {
                GameProfile profile = new GameProfile(m.uuid(), m.name());
                PlayerSkin skin = Minecraft.getInstance().getSkinManager().getInsecureSkin(profile);
                if (skin != null && skin.texture() != null) {
                    PlayerFaceRenderer.draw(graphics, skin.texture(), listX + 4, rowY + 2, 16);
                } else {
                    graphics.fill(listX + 4, rowY + 2, listX + 20, rowY + 18, 0xFF888888);
                }
            } catch (Exception e) {
                graphics.fill(listX + 4, rowY + 2, listX + 20, rowY + 18, 0xFF888888);
            }

            // Draw Player Name
            String nameDisplay = m.name();
            if (m.uuid().equals(myUuid)) {
                nameDisplay = "§a" + nameDisplay + " (You)";
            }
            graphics.drawString(font, nameDisplay, listX + 24, rowY + 6, 0xFFFFFFFF, false);

            // Role indicator on the right
            if (m.role().isLeader()) {
                graphics.drawString(font, "§b◆", listX + listW - 14, rowY + 6, 0xFF55FFFF, false);
            } else {
                graphics.drawString(font, "§7●", listX + listW - 14, rowY + 6, 0xFFAAAAAA, false);
            }
        }

        graphics.disableScissor();

        // Scrollbar if needed
        if (totalHeight > listH) {
            int scrollbarX = listX + listW - 4;
            int thumbH = Math.max(12, listH * listH / totalHeight);
            int thumbY = listY + (listH - thumbH) * memberScrollOffset / Math.max(1, maxScroll);
            graphics.fill(scrollbarX, listY, scrollbarX + 2, listY + listH, 0xFF22252A);
            graphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbH, 0xFF888E99);
        }
    }

    private void renderChatList(GuiGraphics graphics, Font font, int chatX, int chatY, int chatW, int chatH, int mouseX, int mouseY) {
        FactionClientState state = FactionClientState.get();
        List<FactionChatMessage> history = state.getChatHistory();

        activeClickableActions.clear();

        // Background for chat box
        graphics.fill(chatX, chatY, chatX + chatW, chatY + chatH, 0x880C0D0F);
        graphics.fill(chatX, chatY, chatX + chatW, chatY + 1, 0xFF2F343D);
        graphics.fill(chatX, chatY + chatH - 1, chatX + chatW, chatY + chatH, 0xFF2F343D);
        graphics.fill(chatX, chatY, chatX + 1, chatY + chatH, 0xFF2F343D);
        graphics.fill(chatX + chatW - 1, chatY, chatX + chatW, chatY + chatH, 0xFF2F343D);

        // Pre-wrap chat lines
        List<RenderChatLine> wrappedLines = new ArrayList<>();
        int maxTextWidth = chatW - 16;

        for (FactionChatMessage msg : history) {
            if (msg.type() == FactionMessageType.JOIN_REQUEST) {
                String prefix = "§e" + msg.senderName() + " requested to join. ";
                String acceptBtn = "§a§l[ACCEPT]";
                wrappedLines.add(new RenderChatLine(msg, prefix, acceptBtn));
            } else if (msg.type() == FactionMessageType.SYSTEM) {
                String text = "§dSystem: " + msg.text();
                for (FormattedCharSequence seq : font.split(Component.literal(text), maxTextWidth)) {
                    wrappedLines.add(new RenderChatLine(msg, seq, null));
                }
            } else {
                String text = "§e" + msg.senderName() + ": §f" + msg.text();
                for (FormattedCharSequence seq : font.split(Component.literal(text), maxTextWidth)) {
                    wrappedLines.add(new RenderChatLine(msg, seq, null));
                }
            }
        }

        int lineHeight = 11;
        int totalHeight = wrappedLines.size() * lineHeight;
        int maxScroll = Math.max(0, totalHeight - (chatH - 8));
        chatScrollOffset = Math.clamp(chatScrollOffset, 0, maxScroll);

        int renderStartY = chatY + 4;
        int startLineIdx = chatScrollOffset / lineHeight;
        int endLineIdx = Math.min(wrappedLines.size(), (chatScrollOffset + chatH) / lineHeight + 1);

        graphics.enableScissor(chatX + 2, chatY + 2, chatX + chatW - 2, chatY + chatH - 2);

        for (int i = 0; i < wrappedLines.size(); i++) {
            RenderChatLine line = wrappedLines.get(i);
            int lineY = renderStartY + i * lineHeight - chatScrollOffset;

            if (lineY + lineHeight < chatY || lineY > chatY + chatH) {
                continue;
            }

            if (line.actionText != null && line.prefixText != null) {
                // Join request with clickable action
                graphics.drawString(font, line.prefixText, chatX + 6, lineY, 0xFFFFFFFF, false);

                int prefixW = font.width(line.prefixText);
                int btnX = chatX + 6 + prefixW;
                int btnW = font.width(line.actionText);
                int btnH = 9;

                boolean hovered = (mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= lineY && mouseY <= lineY + btnH);
                if (hovered) {
                    graphics.fill(btnX - 1, lineY - 1, btnX + btnW + 1, lineY + btnH, 0x4455FF55);
                    graphics.fill(btnX, lineY + btnH - 1, btnX + btnW, lineY + btnH, 0xFF55FF55); // underline
                }

                graphics.drawString(font, line.actionText, btnX, lineY, 0xFF55FF55, false);

                if (line.msg.targetPlayerUuid() != null) {
                    activeClickableActions.add(new ClickableAction(line.msg.targetPlayerUuid(), btnX - 1, lineY - 1, btnX + btnW + 1, lineY + btnH));
                }
            } else if (line.seq != null) {
                graphics.drawString(font, line.seq, chatX + 6, lineY, 0xFFFFFFFF, false);
            }
        }

        graphics.disableScissor();

        // Scrollbar
        if (totalHeight > chatH) {
            int scrollbarX = chatX + chatW - 4;
            int thumbH = Math.max(12, chatH * chatH / totalHeight);
            int thumbY = chatY + (chatH - thumbH) * chatScrollOffset / Math.max(1, maxScroll);
            graphics.fill(scrollbarX, chatY, scrollbarX + 2, chatY + chatH, 0xFF22252A);
            graphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbH, 0xFF888E99);
        }
    }

    private static class RenderChatLine {
        final FactionChatMessage msg;
        final String prefixText;
        final String actionText;
        final FormattedCharSequence seq;

        RenderChatLine(FactionChatMessage msg, String prefixText, String actionText) {
            this.msg = msg;
            this.prefixText = prefixText;
            this.actionText = actionText;
            this.seq = null;
        }

        RenderChatLine(FactionChatMessage msg, FormattedCharSequence seq, String placeholder) {
            this.msg = msg;
            this.prefixText = null;
            this.actionText = null;
            this.seq = seq;
        }
    }

    private void renderMemberPopup(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (activeMemberPopup == null) return;

        int px = activeMemberPopup.x;
        int py = activeMemberPopup.y;
        int pw = activeMemberPopup.width;
        int ph = activeMemberPopup.height;

        boolean hovered = activeMemberPopup.contains(mouseX, mouseY);
        int bg = activeMemberPopup.isKick ? (hovered ? 0xFFE63946 : 0xFFB71C1C) : (hovered ? 0xFFF77F00 : 0xFFD35400);
        int border = 0xFFFFFFFF;

        graphics.fill(px, py, px + pw, py + ph, bg);
        graphics.fill(px, py, px + pw, py + 1, border);
        graphics.fill(px, py + ph - 1, px + pw, py + ph, border);
        graphics.fill(px, py, px + 1, py + ph, border);
        graphics.fill(px + pw - 1, py, px + pw, py + ph, border);

        String btnLabel = activeMemberPopup.isKick ? "Kick" : "Leave";
        graphics.drawCenteredString(font, btnLabel, px + pw / 2, py + (ph - 8) / 2, 0xFFFFFFFF);
    }

    private void renderConfirmationModal(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (confirmationType == ConfirmationType.NONE) return;

        // Semi-transparent backdrop overlay
        graphics.fill(x, y, x + width, y + height, 0xAA000000);

        int modalW = 240;
        int modalH = 92;
        int modalX = x + (width - modalW) / 2;
        int modalY = y + (height - modalH) / 2;

        // Modal container background & red accent border
        graphics.fill(modalX, modalY, modalX + modalW, modalY + modalH, 0xF0181A1D);
        graphics.fill(modalX, modalY, modalX + modalW, modalY + 1, 0xFFE63946);
        graphics.fill(modalX, modalY + modalH - 1, modalX + modalW, modalY + modalH, 0xFFE63946);
        graphics.fill(modalX, modalY, modalX + 1, modalY + modalH, 0xFFE63946);
        graphics.fill(modalX + modalW - 1, modalY, modalX + modalW, modalY + modalH, 0xFFE63946);

        // Header and description
        if (confirmationType == ConfirmationType.DISBAND) {
            graphics.drawCenteredString(font, "§c§lDISBAND FACTION?", modalX + modalW / 2, modalY + 14, 0xFFFFFFFF);
            graphics.drawCenteredString(font, "§7All territory will be unowned.", modalX + modalW / 2, modalY + 28, 0xFFAAAAAA);
            graphics.drawCenteredString(font, "§cThis action cannot be undone!", modalX + modalW / 2, modalY + 40, 0xFFFF5555);
        } else if (confirmationType == ConfirmationType.KICK) {
            graphics.drawCenteredString(font, "§c§lKICK PLAYER?", modalX + modalW / 2, modalY + 14, 0xFFFFFFFF);
            String targetDisplay = confirmTargetName.isEmpty() ? "this player" : confirmTargetName;
            graphics.drawCenteredString(font, "§7Kick §e" + targetDisplay + "§7 from faction?", modalX + modalW / 2, modalY + 30, 0xFFAAAAAA);
        }

        // Action Buttons
        int btnW = 86;
        int btnH = 20;
        int gap = 14;
        int totalBtnW = btnW * 2 + gap;
        int btnY = modalY + 62;

        int btnConfirmX = modalX + (modalW - totalBtnW) / 2;
        int btnCancelX = btnConfirmX + btnW + gap;

        // Confirm button
        boolean confirmHovered = (mouseX >= btnConfirmX && mouseX <= btnConfirmX + btnW && mouseY >= btnY && mouseY <= btnY + btnH);
        int confirmBg = confirmHovered ? 0xFFE63946 : 0xFFB71C1C;
        graphics.fill(btnConfirmX, btnY, btnConfirmX + btnW, btnY + btnH, confirmBg);
        graphics.fill(btnConfirmX, btnY, btnConfirmX + btnW, btnY + 1, 0xFFFFFFFF);
        graphics.fill(btnConfirmX, btnY + btnH - 1, btnConfirmX + btnW, btnY + btnH, 0xFFFFFFFF);
        graphics.fill(btnConfirmX, btnY, btnConfirmX + 1, btnY + btnH, 0xFFFFFFFF);
        graphics.fill(btnConfirmX + btnW - 1, btnY, btnConfirmX + btnW, btnY + btnH, 0xFFFFFFFF);
        graphics.drawCenteredString(font, "§f§lConfirm", btnConfirmX + btnW / 2, btnY + 6, 0xFFFFFFFF);

        // Cancel button
        boolean cancelHovered = (mouseX >= btnCancelX && mouseX <= btnCancelX + btnW && mouseY >= btnY && mouseY <= btnY + btnH);
        int cancelBg = cancelHovered ? 0xFF4A4E57 : 0xFF2F343D;
        graphics.fill(btnCancelX, btnY, btnCancelX + btnW, btnY + btnH, cancelBg);
        graphics.fill(btnCancelX, btnY, btnCancelX + btnW, btnY + 1, 0xFF888E99);
        graphics.fill(btnCancelX, btnY + btnH - 1, btnCancelX + btnW, btnY + btnH, 0xFF888E99);
        graphics.fill(btnCancelX, btnY, btnCancelX + 1, btnY + btnH, 0xFF888E99);
        graphics.fill(btnCancelX + btnW - 1, btnY, btnCancelX + btnW, btnY + btnH, 0xFF888E99);
        graphics.drawCenteredString(font, "§fCancel", btnCancelX + btnW / 2, btnY + 6, 0xFFFFFFFF);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }

        // 0. Check confirmation modal click if active
        if (confirmationType != ConfirmationType.NONE) {
            int modalW = 240;
            int modalH = 92;
            int modalX = x + (width - modalW) / 2;
            int modalY = y + (height - modalH) / 2;

            int btnW = 86;
            int btnH = 20;
            int gap = 14;
            int totalBtnW = btnW * 2 + gap;
            int btnY = modalY + 62;

            int btnConfirmX = modalX + (modalW - totalBtnW) / 2;
            int btnCancelX = btnConfirmX + btnW + gap;

            if (mouseX >= btnConfirmX && mouseX <= btnConfirmX + btnW && mouseY >= btnY && mouseY <= btnY + btnH) {
                if (confirmationType == ConfirmationType.DISBAND) {
                    PacketDistributor.sendToServer(new DisbandFactionPayload());
                    subview = Subview.MAIN;
                    updateWidgetVisibility();
                } else if (confirmationType == ConfirmationType.KICK) {
                    if (confirmTargetUuid != null) {
                        PacketDistributor.sendToServer(new KickPlayerPayload(confirmTargetUuid));
                    }
                }
                confirmationType = ConfirmationType.NONE;
                confirmTargetUuid = null;
                confirmTargetName = "";
                return true;
            } else if (mouseX >= btnCancelX && mouseX <= btnCancelX + btnW && mouseY >= btnY && mouseY <= btnY + btnH) {
                confirmationType = ConfirmationType.NONE;
                confirmTargetUuid = null;
                confirmTargetName = "";
                return true;
            } else {
                // Clicking outside the modal dismisses it
                if (mouseX < modalX || mouseX > modalX + modalW || mouseY < modalY || mouseY > modalY + modalH) {
                    confirmationType = ConfirmationType.NONE;
                    confirmTargetUuid = null;
                    confirmTargetName = "";
                }
                return true;
            }
        }

        // 1. Check member popup click
        if (activeMemberPopup != null) {
            if (activeMemberPopup.contains(mouseX, mouseY)) {
                if (activeMemberPopup.isKick) {
                    confirmTargetUuid = activeMemberPopup.targetUuid;
                    confirmTargetName = activeMemberPopup.targetName;
                    confirmationType = ConfirmationType.KICK;
                } else {
                    PacketDistributor.sendToServer(new LeaveFactionPayload());
                }
                activeMemberPopup = null;
                return true;
            } else {
                // Clicking anywhere else dismisses the popup
                activeMemberPopup = null;
            }
        }

        // 2. Check preset color picker clicks
        if (subview == Subview.CREATE || subview == Subview.SETTINGS) {
            int formW = 240;
            int formX = x + width / 2 - formW / 2;
            int formY = y + 25;
            int swatchStartX = formX + 130;
            int swatchStartY = formY + 90;
            int swatchSize = 12;
            int swatchGap = 3;

            for (int i = 0; i < PRESET_COLORS.length; i++) {
                int row = i / 5;
                int col = i % 5;
                int sx = swatchStartX + col * (swatchSize + swatchGap);
                int sy = swatchStartY + row * (swatchSize + swatchGap);
                if (mouseX >= sx && mouseX <= sx + swatchSize && mouseY >= sy && mouseY <= sy + swatchSize) {
                    selectedColor = PRESET_COLORS[i];
                    String hexStr = String.format("%06X", selectedColor);
                    if (subview == Subview.CREATE && createHexBox != null) createHexBox.setValue(hexStr);
                    if (subview == Subview.SETTINGS && settingsHexBox != null) settingsHexBox.setValue(hexStr);
                    return true;
                }
            }
        }

        // 3. Check member row clicks in in-faction view
        if (FactionClientState.get().isInFaction() && subview == Subview.MAIN) {
            int headerH = 26;
            int leftColW = Math.max(120, (int) (width * 0.32D));
            int contentTop = y + headerH + 2;
            int bottomReserve = 48;
            int memberListH = height - headerH - bottomReserve - 6;

            if (mouseX >= x + 2 && mouseX <= x + leftColW - 2 && mouseY >= contentTop && mouseY <= contentTop + memberListH) {
                List<FactionMember> members = FactionClientState.get().getMembers();
                int rowHeight = 20;
                int clickedIndex = (int) (mouseY - contentTop + memberScrollOffset) / rowHeight;

                if (clickedIndex >= 0 && clickedIndex < members.size()) {
                    FactionMember m = members.get(clickedIndex);
                    var localPlayer = Minecraft.getInstance().player;
                    UUID myUuid = localPlayer != null ? localPlayer.getUUID() : null;
                    boolean isMe = m.uuid().equals(myUuid);

                    int rowY = contentTop + clickedIndex * rowHeight - memberScrollOffset;
                    int popupW = 44;
                    int popupH = 16;
                    int popupX = x + leftColW - popupW - 8;
                    int popupY = rowY + 2;

                    if (isMe) {
                        // Clicking own name -> Leave Faction button
                        activeMemberPopup = new MemberPopup(m.uuid(), m.name(), popupX, popupY, popupW, popupH, false);
                        return true;
                    } else if (FactionClientState.get().isOwner()) {
                        // Clicking another player's name -> Kick Player button (Owner ONLY!)
                        activeMemberPopup = new MemberPopup(m.uuid(), m.name(), popupX, popupY, popupW, popupH, true);
                        return true;
                    }
                }
            }

            // 4. Check clickable action in chat (Accept join request)
            for (ClickableAction action : activeClickableActions) {
                if (mouseX >= action.left && mouseX <= action.right && mouseY >= action.top && mouseY <= action.bottom) {
                    PacketDistributor.sendToServer(new AcceptJoinRequestPayload(action.requesterUuid));
                    return true;
                }
            }
        }

        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!FactionClientState.get().isInFaction() || subview != Subview.MAIN) {
            return false;
        }

        int headerH = 26;
        int leftColW = Math.max(120, (int) (width * 0.32D));
        int contentTop = y + headerH + 2;

        // Over member list
        if (mouseX >= x && mouseX <= x + leftColW && mouseY >= contentTop && mouseY <= y + height) {
            if (scrollY > 0) memberScrollOffset = Math.max(0, memberScrollOffset - 20);
            else if (scrollY < 0) memberScrollOffset += 20;
            return true;
        }

        // Over chat list
        if (mouseX > x + leftColW && mouseX <= x + width && mouseY >= contentTop && mouseY <= y + height - 24) {
            if (scrollY > 0) chatScrollOffset = Math.max(0, chatScrollOffset - 16);
            else if (scrollY < 0) chatScrollOffset += 16;
            return true;
        }

        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && confirmationType != ConfirmationType.NONE) {
            confirmationType = ConfirmationType.NONE;
            confirmTargetUuid = null;
            confirmTargetName = "";
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (chatBox != null && chatBox.isFocused()) {
                onSendChat();
                return true;
            }
            if (inviteBox != null && inviteBox.isFocused()) {
                onInvitePlayer();
                return true;
            }
            if (joinNameBox != null && joinNameBox.isFocused()) {
                onSendJoinRequest();
                return true;
            }
        }
        return false;
    }
}
