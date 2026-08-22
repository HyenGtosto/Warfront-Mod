package com.warfront.client.map;

import com.warfront.Warfront;
import com.warfront.client.animation.MapAnimationManager;
import com.warfront.client.notification.StrategicNotificationManager;
import com.warfront.network.RegionDetailsPayload;
import com.warfront.network.RegionMapPayload;
import com.warfront.network.RequestRegionDetailsPayload;
import com.warfront.network.RequestRegionMapPayload;
import com.warfront.region.Faction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import com.warfront.map.MapViewType;
import com.warfront.network.CloseMapSessionPayload;

public final class RegionMapScreen extends Screen {
    private static final int HEADER_HEIGHT = 24;
    private static final int PANEL_MARGIN = 6;
    private static final int PANEL_GAP = 6;

    private final RegionMapState state;
    private final RegionMapCamera camera;
    private final RegionMapRenderer renderer;
    private final MapAnimationManager animationManager;
    private final StrategicNotificationManager notificationManager;

    private Button launchAttackButton;
    private Button defendAreaButton;
    private Button cancelAttackButton;
    private Button confirmCampaignButton;
    private Button mapTabButton;
    private Button logsTabButton;
    private final Button[] subRegionMissionButtons = new Button[4];

    public RegionMapScreen(RegionMapPayload payload) {
        super(Component.translatable("screen.warfront.strategic_map"));
        this.state = new RegionMapState(payload);
        this.camera = new RegionMapCamera(payload.centerChunkX() + 0.5D, payload.centerChunkZ() + 0.5D);
        this.renderer = new RegionMapRenderer();
        this.animationManager = new MapAnimationManager();
        this.notificationManager = new StrategicNotificationManager();

        updateActionButtons();
    }

    public MapViewType getViewType() {
        return state.getViewType();
    }

    public boolean isCommandTerminalMap() {
        return state.isCommandTerminalMap();
    }

    public boolean isDebugMap() {
        return state.isDebugMap();
    }

    public RegionMapState getState() {
        return state;
    }

    public RegionMapCamera getCamera() {
        return camera;
    }

    public RegionMapRenderer getRenderer() {
        return renderer;
    }

    public MapAnimationManager getAnimationManager() {
        return animationManager;
    }

    public StrategicNotificationManager getNotificationManager() {
        return notificationManager;
    }

    public void updateMapData(RegionMapPayload payload) {
        boolean shouldRefreshDetails = state.updateMapData(payload);
        renderer.markTextureDirty();

        if (shouldRefreshDetails && state.getSelectedRegion() != null) {
            SelectedRegion sel = state.getSelectedRegion();
            PacketDistributor.sendToServer(new RequestRegionDetailsPayload(
                    sel.regionX(), sel.regionZ(),
                    sel.subX(), sel.subZ(), state.getViewType()));
        }

        updateActionButtons();
    }

    public void selectRegion(RegionDetailsPayload payload) {
        state.selectRegion(payload);
        updateActionButtons();
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new CloseMapSessionPayload());
        super.onClose();
    }

    @Override
    protected void init() {
        MapViewport viewport = viewport();
        camera.init(viewport, state.mapChunkDiameter());
        camera.clampView(viewport, state.mapChunkDiameter(), state.getOriginChunkX(), state.getOriginChunkZ());
        createActionButtons(viewport);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderer.renderBackground(graphics, width, height, partialTick);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        animationManager.tick(partialTick);
        notificationManager.tick();

        RegionMapState.TickAction tickAction = state.tickTimer();
        if (tickAction == RegionMapState.TickAction.REFRESH_ALL && state.getSelectedRegion() != null) {
            SelectedRegion sel = state.getSelectedRegion();
            PacketDistributor.sendToServer(new RequestRegionMapPayload(state.getViewType()));
            PacketDistributor.sendToServer(new RequestRegionDetailsPayload(
                    sel.regionX(), sel.regionZ(),
                    sel.subX(), sel.subZ(), state.getViewType()));
        } else if (tickAction == RegionMapState.TickAction.SYNC_DETAILS && state.getSelectedRegion() != null) {
            SelectedRegion sel = state.getSelectedRegion();
            PacketDistributor.sendToServer(new RequestRegionDetailsPayload(
                    sel.regionX(), sel.regionZ(),
                    sel.subX(), sel.subZ(), state.getViewType()));
        }

        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        MapViewport viewport = viewport();
        camera.clampView(viewport, state.mapChunkDiameter(), state.getOriginChunkX(), state.getOriginChunkZ());

        renderer.renderFrame(graphics, viewport);

        graphics.drawCenteredString(font, title, width / 2, viewport.frameTop() + 6, 0xF4E6C3);

        if (state.getActiveTab() == RegionMapState.ActiveTab.MAP_VIEW) {
            renderer.renderMapView(graphics, font, viewport, state, camera, mouseX, mouseY);
        } else {
            renderer.renderIntelligenceLogFeed(graphics, font, viewport, state);
        }

        for (Renderable renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }

        // Draw colored 1px borders on subregion buttons (red=toggled, green=confirmed)
        renderSubRegionButtonBorders(graphics);
    }

    private void onRegionClicked(double mouseX, double mouseY, MapViewport viewport) {
        if (!viewport.contains(mouseX, mouseY)) {
            return;
        }
        int chunkX = (int) Math.floor(camera.screenToChunkX(mouseX, viewport));
        int chunkZ = (int) Math.floor(camera.screenToChunkZ(mouseY, viewport));
        com.warfront.region.SubRegionPos subPos = com.warfront.region.SubRegionPos.fromChunk(chunkX, chunkZ);

        state.setSelectedRegion(null);
        updateActionButtons();
        PacketDistributor.sendToServer(
                new RequestRegionDetailsPayload(subPos.regionX(), subPos.regionZ(), subPos.subX(), subPos.subZ(), state.getViewType()));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (state.getActiveTab() == RegionMapState.ActiveTab.MAP_VIEW && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && viewport().contains(mouseX, mouseY)) {
            camera.startPan(mouseX, mouseY);
            onRegionClicked(mouseX, mouseY, viewport());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (state.getActiveTab() == RegionMapState.ActiveTab.MAP_VIEW && camera.isPanning() && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return camera.panTo(mouseX, mouseY);
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            camera.stopPan();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (state.getActiveTab() == RegionMapState.ActiveTab.INTELLIGENCE_LOGS && viewport().contains(mouseX, mouseY)) {
            if (scrollY > 0) state.setLogScrollOffset(state.getLogScrollOffset() + 1);
            else if (scrollY < 0) state.setLogScrollOffset(state.getLogScrollOffset() - 1);
            return true;
        }
        if (state.getActiveTab() == RegionMapState.ActiveTab.MAP_VIEW && viewport().contains(mouseX, mouseY)) {
            return camera.handleZoom(mouseX, mouseY, scrollY, viewport(), state.mapChunkDiameter(), state.getOriginChunkX(), state.getOriginChunkZ());
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private MapViewport viewport() {
        int frameLeft = (int) (width * 0.03D);
        int frameTop = (int) (height * 0.03D);
        int frameWidth = Math.max(160, (int) (width * 0.94D));
        int frameHeight = Math.max(120, (int) (height * 0.94D));

        int mapAreaLeft = frameLeft + PANEL_MARGIN;
        int mapAreaTop = frameTop + HEADER_HEIGHT + PANEL_MARGIN;
        int mapAreaWidth = frameWidth - (PANEL_MARGIN * 2);
        int mapAreaHeight = frameHeight - HEADER_HEIGHT - (PANEL_MARGIN * 2);

        int sidePanelWidth = Math.max(80, (int) (mapAreaWidth * 0.22D));
        int centerAvailableW = Math.max(64, mapAreaWidth - (sidePanelWidth * 2) - (PANEL_GAP * 2));
        int mapSize = Math.max(64, Math.min(centerAvailableW, mapAreaHeight));

        int mapLeft = mapAreaLeft + sidePanelWidth + PANEL_GAP + (centerAvailableW - mapSize) / 2;
        int mapTop = mapAreaTop + (mapAreaHeight - mapSize) / 2;

        int leftPanelLeft = mapAreaLeft;
        int rightPanelLeft = mapLeft + mapSize + PANEL_GAP;
        int rightPanelWidth = Math.max(60, mapAreaLeft + mapAreaWidth - rightPanelLeft);

        return new MapViewport(frameLeft, frameTop, frameWidth, frameHeight, mapAreaLeft, leftPanelLeft, sidePanelWidth, rightPanelLeft, rightPanelWidth, mapLeft, mapTop, mapSize);
    }

    private void createActionButtons(MapViewport viewport) {
        this.clearWidgets();

        int rightLeft = viewport.rightPanelLeft();
        int rightWidth = viewport.rightPanelWidth();
        int top = viewport.mapTop() + 4;
        int btnHeight = Math.clamp((int) (viewport.mapSize() * 0.08D), 16, 22);

        launchAttackButton = addRenderableWidget(Button.builder(Component.literal("LAUNCH ATTACK"),
                b -> onLaunchAttack())
                .bounds(rightLeft, top, rightWidth, btnHeight)
                .build());
        launchAttackButton.visible = false;

        defendAreaButton = addRenderableWidget(Button.builder(Component.literal("DEFEND AREA"),
                b -> onDefendArea())
                .bounds(rightLeft, top, rightWidth, btnHeight)
                .build());
        defendAreaButton.visible = false;

        cancelAttackButton = addRenderableWidget(Button.builder(Component.literal("§c§lCANCEL ATTACK"),
                b -> onCancelAttack())
                .bounds(rightLeft, top, rightWidth, btnHeight)
                .build());
        cancelAttackButton.visible = false;

        // 1×4 vertical stack — all buttons use full rightWidth
        for (int i = 0; i < 4; i++) {
            int subX = i % 2;
            int subZ = i / 2;
            int btnY = top + i * (btnHeight + 2);
            int index = i;
            subRegionMissionButtons[i] = addRenderableWidget(Button.builder(
                    Component.literal(String.format("(%d,%d) Mission", subX, subZ)),
                    b -> toggleSubRegionMission(index))
                    .bounds(rightLeft, btnY, rightWidth, btnHeight)
                    .build());
            subRegionMissionButtons[i].visible = false;
        }

        int confirmY = top + 4 * (btnHeight + 2) + 4;
        confirmCampaignButton = addRenderableWidget(Button.builder(
                Component.literal("§a§lCONFIRM CAMPAIGN"),
                b -> onConfirmCampaign())
                .bounds(rightLeft, confirmY, rightWidth, btnHeight)
                .build());
        confirmCampaignButton.visible = false;

        int tabY = viewport.frameTop() + 3;
        int tabWidth = Math.clamp((int) (viewport.frameWidth() * 0.12D), 60, 90);
        mapTabButton = addRenderableWidget(Button.builder(Component.literal("MAP VIEW"), b -> {
            state.setActiveTab(RegionMapState.ActiveTab.MAP_VIEW);
            updateActionButtons();
        }).bounds(viewport.frameLeft() + 6, tabY, tabWidth, 18).build());

        logsTabButton = addRenderableWidget(Button.builder(Component.literal("LOG FEED"), b -> {
            state.setActiveTab(RegionMapState.ActiveTab.INTELLIGENCE_LOGS);
            updateActionButtons();
        }).bounds(viewport.frameLeft() + 10 + tabWidth, tabY, tabWidth, 18).build());

        updateActionButtons();
    }

    private void toggleSubRegionMission(int index) {
        state.toggleSubRegionMission(index);
        if (subRegionMissionButtons[index] != null) {
            subRegionMissionButtons[index].setMessage(Component.literal(buildSubRegionLabel(index)));
        }
    }

    /** Builds the button label for subregion {@code index} using the mission cache when available. */
    private String buildSubRegionLabel(int index) {
        int subX = index % 2;
        int subZ = index / 2;
        SelectedRegion sel = state.getSelectedRegion();
        if (sel != null) {
            int bit = subZ * 2 + subX;
            if ((sel.conqueredMask() & (1 << bit)) != 0) {
                return String.format("(%d,%d) §aSecured", subX, subZ);
            }
            if ((sel.reachableMask() & (1 << bit)) == 0) {
                return String.format("(%d,%d) §8Blocked", subX, subZ);
            }
        }
        com.warfront.mission.SubRegionMission[] missions =
                (sel != null) ? state.getCachedMissions(sel.regionX(), sel.regionZ()) : null;
        String label = (missions != null && index < missions.length)
                ? missions[index].displayLabel()
                : "Mission";
        return String.format("(%d,%d) %s", subX, subZ, label);
    }

    private void updateActionButtons() {
        boolean isMapView = state.getActiveTab() == RegionMapState.ActiveTab.MAP_VIEW;
        SelectedRegion selectedRegion = state.getSelectedRegion();

        if (!state.getViewType().canLaunchAttacks() || !isMapView || selectedRegion == null || (state.getViewType().hasFogOfWar() && !selectedRegion.isVisited())) {
            if (launchAttackButton != null) launchAttackButton.visible = false;
            if (defendAreaButton != null) defendAreaButton.visible = false;
            if (cancelAttackButton != null) cancelAttackButton.visible = false;
            if (confirmCampaignButton != null) confirmCampaignButton.visible = false;
            for (int i = 0; i < 4; i++) {
                if (subRegionMissionButtons[i] != null) subRegionMissionButtons[i].visible = false;
            }
            if (mapTabButton != null) mapTabButton.active = (state.getActiveTab() != RegionMapState.ActiveTab.MAP_VIEW);
            if (logsTabButton != null) logsTabButton.active = (state.getActiveTab() != RegionMapState.ActiveTab.INTELLIGENCE_LOGS);
            return;
        }

        boolean canAttack = selectedRegion.owner() != Faction.HUMANITY
                && selectedRegion.owner() != Faction.UNCLAIMED
                && selectedRegion.regionReachable()
                && selectedRegion.conqueredMask() != 0xF;

        boolean canDefend = selectedRegion.owner() == Faction.HUMANITY
                && selectedRegion.underSiege();

        long regKey = net.minecraft.world.level.ChunkPos.asLong(selectedRegion.regionX(), selectedRegion.regionZ());
        boolean isActivated = state.getActivatedRegions().contains(regKey) || selectedRegion.underSiege();

        if (!isActivated) {
            launchAttackButton.visible = canAttack;
            defendAreaButton.visible = canDefend;
            if (cancelAttackButton != null) cancelAttackButton.visible = false;
            if (confirmCampaignButton != null) confirmCampaignButton.visible = false;
            for (int i = 0; i < 4; i++) {
                if (subRegionMissionButtons[i] != null) subRegionMissionButtons[i].visible = false;
            }
        } else {
            launchAttackButton.visible = false;
            defendAreaButton.visible = false;
            if (cancelAttackButton != null) cancelAttackButton.visible = selectedRegion.underSiege();
            boolean showConfirm = canAttack || canDefend;
            if (confirmCampaignButton != null) confirmCampaignButton.visible = showConfirm;
            for (int i = 0; i < 4; i++) {
                if (subRegionMissionButtons[i] != null) {
                    int sx = i % 2;
                    int sz = i / 2;
                    int bit = sz * 2 + sx;
                    boolean isConquered = (selectedRegion.conqueredMask() & (1 << bit)) != 0;
                    boolean reachable = (selectedRegion.reachableMask() & (1 << bit)) != 0;
                    subRegionMissionButtons[i].visible = true;

                    if (isConquered) {
                        subRegionMissionButtons[i].active = false;
                        state.setSubRegionMissionToggled(i, false);
                    } else if (!reachable) {
                        subRegionMissionButtons[i].active = false;
                        state.setSubRegionMissionToggled(i, false);
                    } else {
                        subRegionMissionButtons[i].active = true;
                    }
                    // Always rebuild the label via the shared helper to keep format consistent
                    subRegionMissionButtons[i].setMessage(Component.literal(buildSubRegionLabel(i)));
                }
            }
        }

        if (mapTabButton != null) mapTabButton.active = (state.getActiveTab() != RegionMapState.ActiveTab.MAP_VIEW);
        if (logsTabButton != null) logsTabButton.active = (state.getActiveTab() != RegionMapState.ActiveTab.INTELLIGENCE_LOGS);
    }

    private void onLaunchAttack() {
        SelectedRegion selectedRegion = state.getSelectedRegion();
        if (selectedRegion != null
                && (state.isDebugMap() || selectedRegion.isVisited())
                && selectedRegion.owner() != Faction.HUMANITY
                && selectedRegion.owner() != Faction.UNCLAIMED
                && !selectedRegion.underSiege()) {
            long regKey = net.minecraft.world.level.ChunkPos.asLong(selectedRegion.regionX(), selectedRegion.regionZ());
            state.getActivatedRegions().add(regKey);
            state.setCampaignConfirmed(false);

            // Generate and cache missions for this region (no-op on repeated clicks)
            state.getOrGenerateMissions(
                    selectedRegion.regionX(), selectedRegion.regionZ(),
                    selectedRegion.owner(), selectedRegion.baseType(),
                    selectedRegion.resistance(), selectedRegion.stability());

            for (int i = 0; i < 4; i++) {
                state.setSubRegionMissionToggled(i, false);
                if (subRegionMissionButtons[i] != null) {
                    subRegionMissionButtons[i].setMessage(Component.literal(buildSubRegionLabel(i)));
                }
            }
            updateActionButtons();
        }
    }

    private void onCancelAttack() {
        SelectedRegion selectedRegion = state.getSelectedRegion();
        if (selectedRegion != null && selectedRegion.underSiege()) {
            PacketDistributor.sendToServer(new com.warfront.network.CancelAttackPayload(
                    selectedRegion.regionX(), selectedRegion.regionZ(),
                    selectedRegion.subX(), selectedRegion.subZ(), state.getViewType()));
            long regKey = net.minecraft.world.level.ChunkPos.asLong(selectedRegion.regionX(), selectedRegion.regionZ());
            state.getActivatedRegions().remove(regKey);
            state.setCampaignConfirmed(false);
            for (int i = 0; i < 4; i++) {
                state.setSubRegionMissionToggled(i, false);
            }
            updateActionButtons();
        }
    }

    private void onConfirmCampaign() {
        SelectedRegion selectedRegion = state.getSelectedRegion();
        if (selectedRegion == null || !(state.isDebugMap() || selectedRegion.isVisited())) {
            return;
        }

        boolean isDefense = selectedRegion.owner() == Faction.HUMANITY && selectedRegion.underSiege();
        boolean isAttack = selectedRegion.owner() != Faction.HUMANITY && selectedRegion.owner() != Faction.UNCLAIMED;

        if (!isDefense && !isAttack) {
            return;
        }

        int subMask = 0;
        for (int i = 0; i < 4; i++) {
            int sx = i % 2;
            int sz = i / 2;
            int bit = sz * 2 + sx;
            boolean isSecured = (selectedRegion.conqueredMask() & (1 << bit)) != 0;
            if (!isSecured && state.isSubRegionMissionToggled(i)) {
                subMask |= (1 << bit);
            }
        }
        if (subMask == 0) {
            Warfront.LOGGER.warn("No sub-region missions selected! Toggle at least one READY Sub button before confirming.");
            return;
        }

        Warfront.LOGGER.info("Confirming campaign on region ({}, {}) with mission mask {}",
                selectedRegion.regionX(), selectedRegion.regionZ(), subMask);
        PacketDistributor.sendToServer(new com.warfront.network.LaunchAttackPayload(
                selectedRegion.regionX(), selectedRegion.regionZ(), selectedRegion.subX(), selectedRegion.subZ(), subMask));

        // Mark confirmed so local UI updates immediately
        state.setCampaignConfirmed(true);

        PacketDistributor.sendToServer(new RequestRegionDetailsPayload(
                selectedRegion.regionX(), selectedRegion.regionZ(),
                selectedRegion.subX(), selectedRegion.subZ(), state.getViewType()));
    }

    private void onDefendArea() {
        SelectedRegion selectedRegion = state.getSelectedRegion();
        if (selectedRegion != null && selectedRegion.underSiege()) {
            long regKey = net.minecraft.world.level.ChunkPos.asLong(selectedRegion.regionX(), selectedRegion.regionZ());
            state.getActivatedRegions().add(regKey);
            for (int i = 0; i < 4; i++) {
                state.setSubRegionMissionToggled(i, false);
            }
            updateActionButtons();
        }
    }

    /**
     * Draws a 1px colored border around subregion mission buttons.
     * GREEN for subregions included in the server's active campaign (existingSiegeMask);
     * RED for subregions toggled by the player before confirmation.
     */
    private void renderSubRegionButtonBorders(GuiGraphics graphics) {
        SelectedRegion sel = state.getSelectedRegion();
        if (sel == null) return;
        long regKey = net.minecraft.world.level.ChunkPos.asLong(sel.regionX(), sel.regionZ());
        boolean isActivated = state.getActivatedRegions().contains(regKey) || sel.underSiege();
        if (!isActivated) return;

        for (int i = 0; i < 4; i++) {
            Button btn = subRegionMissionButtons[i];
            if (btn == null || !btn.visible) continue;

            int sx = i % 2;
            int sz = i / 2;
            int bit = sz * 2 + sx;

            boolean isConquered = (sel.conqueredMask() & (1 << bit)) != 0;
            if (isConquered) continue;

            boolean inActiveCampaign = sel.underSiege() && ((sel.existingSiegeMask() & (1 << bit)) != 0);
            boolean isToggled = state.isSubRegionMissionToggled(i);

            if (inActiveCampaign) {
                // Included in active server campaign -> GREEN border
                drawButtonBorder(graphics, btn, 0xFF00BB00);
            } else if (isToggled) {
                // Toggled by player before confirmation -> RED border
                drawButtonBorder(graphics, btn, 0xFFCC0000);
            }
        }
    }

    /** Draws a single-pixel border around a button using absolute screen coordinates. */
    private void drawButtonBorder(GuiGraphics graphics, Button btn, int color) {
        int x = btn.getX();
        int y = btn.getY();
        int w = btn.getWidth();
        int h = btn.getHeight();
        graphics.fill(x,         y,         x + w,     y + 1,     color); // top
        graphics.fill(x,         y + h - 1, x + w,     y + h,     color); // bottom
        graphics.fill(x,         y,         x + 1,     y + h,     color); // left
        graphics.fill(x + w - 1, y,         x + w,     y + h,     color); // right
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

