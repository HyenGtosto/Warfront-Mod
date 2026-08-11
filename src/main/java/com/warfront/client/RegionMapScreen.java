package com.warfront.client;

import com.warfront.Warfront;
import com.warfront.network.RegionDetailsPayload;
import com.warfront.network.RegionMapPayload;
import com.warfront.network.RequestRegionDetailsPayload;
import com.warfront.network.RequestRegionMapPayload;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

public final class RegionMapScreen extends Screen {
    private static final int HEADER_HEIGHT = 24;
    private static final int PANEL_MARGIN = 6;
    private static final int PANEL_GAP = 6;
    private static final double MAX_CHUNK_TILE_SIZE = 48.0D;

    public enum ActiveTab {
        MAP_VIEW,
        INTELLIGENCE_LOGS
    }

    private ActiveTab activeTab = ActiveTab.MAP_VIEW;
    private int logScrollOffset = 0;
    private final List<String> logMessages = new ArrayList<>();

    private final int playerChunkX;
    private final int playerChunkZ;
    private final int originChunkX;
    private final int originChunkZ;
    private final boolean isCommandTerminalMap;
    private final boolean isDebugMap;
    private final Map<Long, RegionMapPayload.ChunkData> chunks = new HashMap<>();
    private final List<RegionMapPayload.RegionMarkerData> markers = new ArrayList<>();
    private final List<RegionMapPayload.SiegeArrowData> siegeArrows = new ArrayList<>();
    private double viewCenterX;
    private double viewCenterZ;
    private double chunkTileSize;
    private boolean panning;
    private boolean dragged;
    private double lastPanX;
    private double lastPanY;
    private SelectedRegion selectedRegion;
    private Button launchAttackButton;
    private Button defendAreaButton;
    private Button confirmCampaignButton;
    private Button mapTabButton;
    private Button logsTabButton;
    private final Button[] subRegionMissionButtons = new Button[4];
    private final boolean[] subRegionMissionToggled = new boolean[4];

    // Tracks regions activated for sub-region missions during this session
    private final Set<Long> activatedRegions = new HashSet<>();

    private DynamicTexture mapTexture;
    private ResourceLocation mapTextureLocation;
    private boolean textureNeedsUpdate = true;

    public boolean isCommandTerminalMap() {
        return isCommandTerminalMap;
    }

    public boolean isDebugMap() {
        return isDebugMap;
    }

    public RegionMapScreen(RegionMapPayload payload) {
        super(Component.translatable("screen.warfront.strategic_map"));
        playerChunkX = payload.centerChunkX();
        playerChunkZ = payload.centerChunkZ();
        isCommandTerminalMap = payload.isCommandTerminalMap();
        isDebugMap = payload.isDebugMap();

        int diameter = Math.max(64, (int) Math.round(Math.sqrt(payload.chunks().size())));
        originChunkX = playerChunkX - diameter / 2;
        originChunkZ = playerChunkZ - diameter / 2;
        viewCenterX = playerChunkX + 0.5D;
        viewCenterZ = playerChunkZ + 0.5D;

        updateMapData(payload);
    }

    public void updateMapData(RegionMapPayload payload) {
        this.chunks.clear();
        for (RegionMapPayload.ChunkData chunk : payload.chunks()) {
            chunks.put(ChunkPos.asLong(chunk.chunkX(), chunk.chunkZ()), chunk);
        }
        if (payload.markers() != null) {
            markers.clear();
            markers.addAll(payload.markers());
        }
        if (payload.siegeArrows() != null) {
            siegeArrows.clear();
            siegeArrows.addAll(payload.siegeArrows());
        }
        if (payload.logMessages() != null) {
            logMessages.clear();
            logMessages.addAll(payload.logMessages());
        }
        this.textureNeedsUpdate = true;

        // Bug fix: if the map snapshot shows the selected region is no longer under siege
        // (e.g. domino conquest or timer expiry just completed), auto-refresh region details so
        // the sidebar timer clears immediately. Scan all 64 chunks in the region rather than only
        // the top-left one, because that chunk may simply not be in the loaded snapshot.
        if (selectedRegion != null && selectedRegion.underSiege()) {
            int rcx = selectedRegion.regionX() * 8;
            int rcz = selectedRegion.regionZ() * 8;
            boolean foundAny = false;
            boolean regionStillSieged = false;
            outer:
            for (int cx = 0; cx < 8; cx++) {
                for (int cz = 0; cz < 8; cz++) {
                    RegionMapPayload.ChunkData c = chunks.get(ChunkPos.asLong(rcx + cx, rcz + cz));
                    if (c != null) {
                        foundAny = true;
                        regionStillSieged = c.underSiege();
                        break outer;
                    }
                }
            }
            if (foundAny && !regionStillSieged) {
                // Region was just cleared server-side -> refresh details immediately
                PacketDistributor.sendToServer(new RequestRegionDetailsPayload(
                        selectedRegion.regionX(), selectedRegion.regionZ(),
                        selectedRegion.subX(), selectedRegion.subZ(), isCommandTerminalMap, isDebugMap));
            }
        }

        updateActionButtons();
    }

    @Override
    protected void init() {
        chunkTileSize = minimumChunkTileSize(viewport());
        clampView(viewport());
        createActionButtons(viewport());
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x90000000);
    }

    private long lastTickTimeMs = 0L;
    private int syncTimerTicks = 0;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long currentTimeMs = System.currentTimeMillis();
        if (lastTickTimeMs == 0L) {
            lastTickTimeMs = currentTimeMs;
        }

        // Live Client-Side Countdown Ticking (every 1 second / 1000ms)
        if (currentTimeMs - lastTickTimeMs >= 1000L) {
            long secondsElapsed = (currentTimeMs - lastTickTimeMs) / 1000L;
            lastTickTimeMs = currentTimeMs;
            if (selectedRegion != null && selectedRegion.remainingSiegeTicks() > 0) {
                long newTicks = Math.max(0L, selectedRegion.remainingSiegeTicks() - (secondsElapsed * 20L));
                selectedRegion = selectedRegion.withRemainingSiegeTicks(newTicks);
                if (newTicks == 0L) {
                    // Attack/Defense timer reached zero -> instantly refresh map & region details from server!
                    PacketDistributor.sendToServer(new RequestRegionMapPayload(isCommandTerminalMap));
                    PacketDistributor.sendToServer(new RequestRegionDetailsPayload(
                            selectedRegion.regionX(), selectedRegion.regionZ(),
                            selectedRegion.subX(), selectedRegion.subZ(), isCommandTerminalMap, isDebugMap));
                }
            }

            // Periodic Automatic Server Detail Synchronization (every 4 seconds)
            syncTimerTicks++;
            if (syncTimerTicks >= 4 && selectedRegion != null && (isDebugMap || selectedRegion.isVisited())) {
                syncTimerTicks = 0;
                PacketDistributor.sendToServer(new RequestRegionDetailsPayload(
                        selectedRegion.regionX(), selectedRegion.regionZ(),
                        selectedRegion.subX(), selectedRegion.subZ(), isCommandTerminalMap, isDebugMap));
            }
        }

        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        MapViewport viewport = viewport();
        clampView(viewport);

        renderFrame(graphics, viewport);

        // Header Title (Centered)
        graphics.drawCenteredString(font, title, width / 2, viewport.frameTop() + 6, 0xF4E6C3);

        if (activeTab == ActiveTab.MAP_VIEW) {
            graphics.enableScissor(viewport.mapLeft(), viewport.mapTop(),
                    viewport.mapLeft() + viewport.mapSize(), viewport.mapTop() + viewport.mapSize());
            renderChunks(graphics, viewport);
            renderFrontlineBorders(graphics, viewport);
            renderSiegeArrows(graphics, viewport);
            renderRegionMarkers(graphics, viewport);
            renderHoveredRegion(graphics, viewport, mouseX, mouseY);
            renderPlayerMarker(graphics, viewport);
            graphics.disableScissor();

            renderSelectedRegionInfo(graphics, viewport);
        } else {
            renderIntelligenceLogFeed(graphics, viewport);
        }

        // Render widgets directly without super.render menu darkener/blur
        for (Renderable renderable : this.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void renderIntelligenceLogFeed(GuiGraphics graphics, MapViewport viewport) {
        int panelLeft = viewport.leftPanelLeft();
        int panelWidth = viewport.frameWidth() - (PANEL_MARGIN * 2);
        int top = viewport.mapTop();
        int height = viewport.mapSize();

        graphics.fill(panelLeft, top, panelLeft + panelWidth, top + height, 0xFF121417);
        graphics.fill(panelLeft, top, panelLeft + panelWidth, top + 1, 0xFF7A715D);
        graphics.fill(panelLeft, top + height - 1, panelLeft + panelWidth, top + height, 0xFF7A715D);
        graphics.fill(panelLeft, top, panelLeft + 1, top + height, 0xFF7A715D);
        graphics.fill(panelLeft + panelWidth - 1, top, panelLeft + panelWidth, top + height, 0xFF7A715D);

        graphics.drawString(font, Component.literal("§e§lINTELLIGENCE LOG FEED"), panelLeft + 6, top + 6, 0xFFFFFFFF, false);

        int maxVisibleLines = (height - 24) / 10;
        List<FormattedCharSequence> allWrappedLines = new ArrayList<>();
        // Chronological order: index 0 (oldest) at top, newest at bottom
        for (int i = 0; i < logMessages.size(); i++) {
            allWrappedLines.addAll(font.split(Component.literal(logMessages.get(i)), panelWidth - 16));
        }

        int totalLines = allWrappedLines.size();
        int maxScroll = Math.max(0, totalLines - maxVisibleLines);
        logScrollOffset = Math.clamp(logScrollOffset, 0, maxScroll);

        // logScrollOffset = 0 -> viewing bottom (newest logs)
        // logScrollOffset = maxScroll -> viewing top (oldest logs)
        int endIndex = totalLines - logScrollOffset;
        int startIndex = Math.max(0, endIndex - maxVisibleLines);

        graphics.enableScissor(panelLeft + 4, top + 22, panelLeft + panelWidth - 4, top + height - 4);
        int yOffset = top + 26;
        for (int i = startIndex; i < endIndex; i++) {
            graphics.drawString(font, allWrappedLines.get(i), panelLeft + 10, yOffset, 0xFFFFFFFF, false);
            yOffset += 10;
        }
        graphics.disableScissor();

        if (totalLines > maxVisibleLines) {
            int scrollbarX = panelLeft + panelWidth - 8;
            int scrollbarHeight = height - 32;
            int thumbHeight = Math.max(14, scrollbarHeight * maxVisibleLines / totalLines);
            // Thumb at top when viewing oldest (maxScroll), thumb at bottom when viewing newest (0)
            int thumbY = top + 24 + (scrollbarHeight - thumbHeight) * (maxScroll - logScrollOffset) / Math.max(1, maxScroll);

            graphics.fill(scrollbarX, top + 24, scrollbarX + 3, top + 24 + scrollbarHeight, 0xFF2A2D32);
            graphics.fill(scrollbarX, thumbY, scrollbarX + 3, thumbY + thumbHeight, 0xFFCBB985);
        }
    }

    private double screenToChunkX(double screenX, MapViewport viewport) {
        return leftChunk(viewport) + (screenX - viewport.mapLeft()) / chunkTileSize;
    }

    private double screenToChunkZ(double screenY, MapViewport viewport) {
        return topChunk(viewport) + (screenY - viewport.mapTop()) / chunkTileSize;
    }

    private void onRegionClicked(double mouseX, double mouseY, MapViewport viewport) {
        if (!viewport.contains(mouseX, mouseY)) {
            return;
        }
        int chunkX = (int) Math.floor(screenToChunkX(mouseX, viewport));
        int chunkZ = (int) Math.floor(screenToChunkZ(mouseY, viewport));
        com.warfront.region.SubRegionPos subPos = com.warfront.region.SubRegionPos.fromChunk(chunkX, chunkZ);

        selectedRegion = null;
        updateActionButtons();
        PacketDistributor.sendToServer(
                new RequestRegionDetailsPayload(subPos.regionX(), subPos.regionZ(), subPos.subX(), subPos.subZ(), isCommandTerminalMap, isDebugMap));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (activeTab == ActiveTab.MAP_VIEW && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && viewport().contains(mouseX, mouseY)) {
            panning = true;
            dragged = false;
            lastPanX = mouseX;
            lastPanY = mouseY;
            onRegionClicked(mouseX, mouseY, viewport());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (activeTab == ActiveTab.MAP_VIEW && panning && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            double deltaX = mouseX - lastPanX;
            double deltaY = mouseY - lastPanY;
            if (deltaX != 0.0D || deltaY != 0.0D) {
                dragged = true;
            }
            viewCenterX -= deltaX / chunkTileSize;
            viewCenterZ -= deltaY / chunkTileSize;
            lastPanX = mouseX;
            lastPanY = mouseY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            panning = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeTab == ActiveTab.INTELLIGENCE_LOGS && viewport().contains(mouseX, mouseY)) {
            if (scrollY > 0) logScrollOffset++;
            else if (scrollY < 0) logScrollOffset--;
            return true;
        }
        if (activeTab == ActiveTab.MAP_VIEW && viewport().contains(mouseX, mouseY)) {
            MapViewport viewport = viewport();
            double mouseChunkX = screenToChunkX(mouseX, viewport);
            double mouseChunkZ = screenToChunkZ(mouseY, viewport);
            double factor = (scrollY > 0) ? 1.15D : 0.87D;
            double newTileSize = Math.clamp(chunkTileSize * factor, minimumChunkTileSize(viewport), MAX_CHUNK_TILE_SIZE);
            if (newTileSize != chunkTileSize) {
                chunkTileSize = newTileSize;
                viewCenterX = mouseChunkX - (mouseX - viewport.mapLeft()) / chunkTileSize + viewport.mapSize() / (2.0D * chunkTileSize);
                viewCenterZ = mouseChunkZ - (mouseY - viewport.mapTop()) / chunkTileSize + viewport.mapSize() / (2.0D * chunkTileSize);
                clampView(viewport);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int mapChunkDiameter() {
        return Math.max(64, (int) Math.round(Math.sqrt(chunks.size())));
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

        // Responsive Percentage Layout: Reserve 22% width for Left Panel and 22% width for Right Panel
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

    private void renderFrame(GuiGraphics graphics, MapViewport viewport) {
        graphics.fill(viewport.frameLeft(), viewport.frameTop(),
                viewport.frameLeft() + viewport.frameWidth(), viewport.frameTop() + viewport.frameHeight(), 0xEE0B0C0E);
        graphics.fill(viewport.frameLeft(), viewport.frameTop(),
                viewport.frameLeft() + viewport.frameWidth(), viewport.frameTop() + HEADER_HEIGHT, 0xFF1A1C20);
        graphics.fill(viewport.frameLeft(), viewport.frameTop() + HEADER_HEIGHT - 1,
                viewport.frameLeft() + viewport.frameWidth(), viewport.frameTop() + HEADER_HEIGHT, 0xFF7A715D);

        graphics.fill(viewport.frameLeft(), viewport.frameTop(), viewport.frameLeft() + viewport.frameWidth(), viewport.frameTop() + 1, 0xFF7A715D);
        graphics.fill(viewport.frameLeft(), viewport.frameTop() + viewport.frameHeight() - 1, viewport.frameLeft() + viewport.frameWidth(), viewport.frameTop() + viewport.frameHeight(), 0xFF7A715D);
        graphics.fill(viewport.frameLeft(), viewport.frameTop(), viewport.frameLeft() + 1, viewport.frameTop() + viewport.frameHeight(), 0xFF7A715D);
        graphics.fill(viewport.frameLeft() + viewport.frameWidth() - 1, viewport.frameTop(), viewport.frameLeft() + viewport.frameWidth(), viewport.frameTop() + viewport.frameHeight(), 0xFF7A715D);
    }

    private void updateDynamicTexture() {
        int diameter = mapChunkDiameter();
        if (mapTexture == null || mapTexture.getPixels().getWidth() != diameter) {
            mapTexture = new DynamicTexture(diameter, diameter, false);
            mapTextureLocation = Minecraft.getInstance().getTextureManager().register("region_map_texture", mapTexture);
        }

        com.mojang.blaze3d.platform.NativeImage image = mapTexture.getPixels();
        if (image != null) {
            for (int cz = 0; cz < diameter; cz++) {
                for (int cx = 0; cx < diameter; cx++) {
                    int chunkX = originChunkX + cx;
                    int chunkZ = originChunkZ + cz;
                    RegionMapPayload.ChunkData chunk = chunks.get(ChunkPos.asLong(chunkX, chunkZ));
                    int colorABGR;
                    if (chunk == null) {
                        colorABGR = 0xFF000000;
                    } else if (!chunk.isVisited()) {
                        colorABGR = 0xFF181A1D; // Dark gray fog of war
                    } else {
                        int rawColor = chunk.biomeColor();
                        int br = (rawColor >> 16) & 0xFF;
                        int bg = (rawColor >> 8) & 0xFF;
                        int bb = rawColor & 0xFF;

                        // Check if chunk is inside a region currently under attack/siege
                        boolean isSiege = chunk.underSiege();

                        // Blend Faction Overlay Color if claimed by a faction
                        if (chunk.factionId() != Faction.UNCLAIMED.id()) {
                            Faction faction = Faction.byId(chunk.factionId());
                            int fColor = faction.color();
                            int fr = (fColor >> 16) & 0xFF;
                            int fg = (fColor >> 8) & 0xFF;
                            int fb = fColor & 0xFF;

                            // 50% Alpha Blend between Biome Color and Faction Color
                            br = (br + fr) / 2;
                            bg = (bg + fg) / 2;
                            bb = (bb + fb) / 2;

                            // Check border lines with 4 neighbors to draw solid border lines directly into texture pixels!
                            RegionMapPayload.ChunkData north = chunks.get(ChunkPos.asLong(chunkX, chunkZ - 1));
                            RegionMapPayload.ChunkData south = chunks.get(ChunkPos.asLong(chunkX, chunkZ + 1));
                            RegionMapPayload.ChunkData west = chunks.get(ChunkPos.asLong(chunkX - 1, chunkZ));
                            RegionMapPayload.ChunkData east = chunks.get(ChunkPos.asLong(chunkX + 1, chunkZ));

                            boolean isBorder = (north == null || !north.isVisited() || north.factionId() != chunk.factionId())
                                    || (south == null || !south.isVisited() || south.factionId() != chunk.factionId())
                                    || (west == null || !west.isVisited() || west.factionId() != chunk.factionId())
                                    || (east == null || !east.isVisited() || east.factionId() != chunk.factionId());

                            if (isBorder) {
                                br = fr;
                                bg = fg;
                                bb = fb;
                            }
                        }

                        // If region is under siege, apply red warzone tint & bright red tactical siege outline!
                        if (isSiege) {
                            br = Math.min(255, br + 80);
                            bg = bg / 2;
                            bb = bb / 2;

                            RegionMapPayload.ChunkData north = chunks.get(ChunkPos.asLong(chunkX, chunkZ - 1));
                            RegionMapPayload.ChunkData south = chunks.get(ChunkPos.asLong(chunkX, chunkZ + 1));
                            RegionMapPayload.ChunkData west = chunks.get(ChunkPos.asLong(chunkX - 1, chunkZ));
                            RegionMapPayload.ChunkData east = chunks.get(ChunkPos.asLong(chunkX + 1, chunkZ));

                            boolean isSiegeBorder = (north == null || !north.underSiege())
                                    || (south == null || !south.underSiege())
                                    || (west == null || !west.underSiege())
                                    || (east == null || !east.underSiege());

                            if (isSiegeBorder) {
                                br = 255;
                                bg = 34;
                                bb = 34; // Bright Tactical Red Outline! (0xFFFF2222)
                            }
                        }

                        colorABGR = 0xFF000000 | (bb << 16) | (bg << 8) | br;
                    }
                    image.setPixelRGBA(cx, cz, colorABGR);
                }
            }
            mapTexture.upload();
        }
        textureNeedsUpdate = false;
    }

    private void renderChunks(GuiGraphics graphics, MapViewport viewport) {
        if (textureNeedsUpdate || mapTextureLocation == null) {
            updateDynamicTexture();
        }

        int diameter = mapChunkDiameter();
        double visibleChunks = viewport.mapSize() / chunkTileSize;

        float srcX = (float) (viewCenterX - originChunkX - visibleChunks / 2.0D);
        float srcY = (float) (viewCenterZ - originChunkZ - visibleChunks / 2.0D);
        int srcW = (int) Math.ceil(visibleChunks);
        int srcH = (int) Math.ceil(visibleChunks);

        graphics.blit(mapTextureLocation,
                viewport.mapLeft(), viewport.mapTop(),
                viewport.mapSize(), viewport.mapSize(),
                srcX, srcY, srcW, srcH,
                diameter, diameter);
    }

    private void renderFrontlineBorders(GuiGraphics graphics, MapViewport viewport) {
        // Pre-baked into dynamic texture for 1-blit 60+ FPS performance!
    }

    private void renderRegionMarkers(GuiGraphics graphics, MapViewport viewport) {
        for (RegionMapPayload.RegionMarkerData marker : markers) {
            int centerChunkX = marker.regionX() * 8 + 4;
            int centerChunkZ = marker.regionZ() * 8 + 4;

            int drawX = (int) Math.round(viewport.mapLeft() + (centerChunkX - leftChunk(viewport)) * chunkTileSize);
            int drawY = (int) Math.round(viewport.mapTop() + (centerChunkZ - topChunk(viewport)) * chunkTileSize);

            if (drawX < viewport.mapLeft() - 10 || drawX > viewport.mapLeft() + viewport.mapSize() + 10
                    || drawY < viewport.mapTop() - 10 || drawY > viewport.mapTop() + viewport.mapSize() + 10) {
                continue;
            }

            BaseType baseType = BaseType.byId(marker.baseTypeId());

            if (baseType == BaseType.OUTPOST) {
                // Outpost Icon: Black 8x8 filled square
                graphics.fill(drawX - 4, drawY - 4, drawX + 4, drawY + 4, 0xFF000000);
            } else if (baseType == BaseType.HEADQUARTERS) {
                // HQ Icon (Standard Base Core): Dark Gray 8x8 filled square with Black border
                graphics.fill(drawX - 5, drawY - 5, drawX + 5, drawY + 5, 0xFF000000);
                graphics.fill(drawX - 3, drawY - 3, drawX + 3, drawY + 3, 0xFF444444);
            } else if (baseType == BaseType.MEGA_BASE) {
                // Mega Base Icon (ONLY Mega Base Variant): Gold outline with white center square
                graphics.fill(drawX - 6, drawY - 6, drawX + 6, drawY + 6, 0xFF000000);
                graphics.fill(drawX - 5, drawY - 5, drawX + 5, drawY + 5, 0xFFFFA800);
                graphics.fill(drawX - 2, drawY - 2, drawX + 2, drawY + 2, 0xFFFFFFFF);
            }
        }
    }

    private void drawThickLine(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int x = x0;
        int y = y0;

        while (true) {
            graphics.fill(x - 1, y - 1, x + 2, y + 2, color);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private void renderSiegeArrows(GuiGraphics graphics, MapViewport viewport) {
        for (RegionMapPayload.SiegeArrowData arrow : siegeArrows) {
            int srcChunkX = arrow.sourceRegionX() * 8 + 4;
            int srcChunkZ = arrow.sourceRegionZ() * 8 + 4;
            int tgtChunkX = arrow.targetRegionX() * 8 + 4;
            int tgtChunkZ = arrow.targetRegionZ() * 8 + 4;

            int x0 = (int) Math.round(viewport.mapLeft() + (srcChunkX - leftChunk(viewport)) * chunkTileSize);
            int y0 = (int) Math.round(viewport.mapTop() + (srcChunkZ - topChunk(viewport)) * chunkTileSize);
            int x1 = (int) Math.round(viewport.mapLeft() + (tgtChunkX - leftChunk(viewport)) * chunkTileSize);
            int y1 = (int) Math.round(viewport.mapTop() + (tgtChunkZ - topChunk(viewport)) * chunkTileSize);

            // Crisp 3px red siege line connecting source to target
            int lineColor = 0xFFFF3333;
            drawThickLine(graphics, x0, y0, x1, y1, lineColor);

            // Distinct directional arrowhead pointer tip at target (x1, y1)
            int dirX = Integer.signum(x1 - x0);
            int dirY = Integer.signum(y1 - y0);

            // Outer black border
            graphics.fill(x1 - 5, y1 - 5, x1 + 5, y1 + 5, 0xFF000000);
            // Inner red pointer core
            graphics.fill(x1 - 3, y1 - 3, x1 + 3, y1 + 3, 0xFFFF3333);
            // White directional tip
            if (dirX != 0 || dirY != 0) {
                graphics.fill(x1 + dirX * 4 - 2, y1 + dirY * 4 - 2, x1 + dirX * 4 + 2, y1 + dirY * 4 + 2, 0xFFFFFFFF);
            }
        }
    }

    private void renderHoveredRegion(GuiGraphics graphics, MapViewport viewport, int mouseX, int mouseY) {
        if (!viewport.contains(mouseX, mouseY)) {
            return;
        }

        int chunkX = (int) Math.floor(screenToChunkX(mouseX, viewport));
        int chunkZ = (int) Math.floor(screenToChunkZ(mouseY, viewport));
        int regionX = Math.floorDiv(chunkX, 8);
        int regionZ = Math.floorDiv(chunkZ, 8);

        int minChunkX = regionX * 8;
        int minChunkZ = regionZ * 8;
        int x0 = (int) Math.round(viewport.mapLeft() + (minChunkX - leftChunk(viewport)) * chunkTileSize);
        int y0 = (int) Math.round(viewport.mapTop() + (minChunkZ - topChunk(viewport)) * chunkTileSize);
        int x1 = (int) Math.round(viewport.mapLeft() + (minChunkX + 8 - leftChunk(viewport)) * chunkTileSize);
        int y1 = (int) Math.round(viewport.mapTop() + (minChunkZ + 8 - topChunk(viewport)) * chunkTileSize);

        graphics.fill(x0, y0, x1, y0 + 1, 0x80FFFFFF);
        graphics.fill(x0, y1 - 1, x1, y1, 0x80FFFFFF);
        graphics.fill(x0, y0, x0 + 1, y1, 0x80FFFFFF);
        graphics.fill(x1 - 1, y0, x1, y1, 0x80FFFFFF);
    }

    private void renderSelectedRegionHighlight(GuiGraphics graphics, MapViewport viewport) {
        if (selectedRegion == null) {
            return;
        }

        int minChunkX = selectedRegion.regionX() * 8;
        int minChunkZ = selectedRegion.regionZ() * 8;
        int x0 = (int) Math.round(viewport.mapLeft() + (minChunkX - leftChunk(viewport)) * chunkTileSize);
        int y0 = (int) Math.round(viewport.mapTop() + (minChunkZ - topChunk(viewport)) * chunkTileSize);
        int x1 = (int) Math.round(viewport.mapLeft() + (minChunkX + 8 - leftChunk(viewport)) * chunkTileSize);
        int y1 = (int) Math.round(viewport.mapTop() + (minChunkZ + 8 - topChunk(viewport)) * chunkTileSize);

        // White region box
        graphics.fill(x0, y0, x1, y0 + 1, 0xFFFFFFFF);
        graphics.fill(x0, y1 - 1, x1, y1, 0xFFFFFFFF);
        graphics.fill(x0, y0, x0 + 1, y1, 0xFFFFFFFF);
        graphics.fill(x1 - 1, y0, x1, y1, 0xFFFFFFFF);

        // Gold sub-region box
        int subMinCX = minChunkX + selectedRegion.subX() * 4;
        int subMinCZ = minChunkZ + selectedRegion.subZ() * 4;
        int sx0 = (int) Math.round(viewport.mapLeft() + (subMinCX - leftChunk(viewport)) * chunkTileSize);
        int sy0 = (int) Math.round(viewport.mapTop() + (subMinCZ - topChunk(viewport)) * chunkTileSize);
        int sx1 = (int) Math.round(viewport.mapLeft() + (subMinCX + 4 - leftChunk(viewport)) * chunkTileSize);
        int sy1 = (int) Math.round(viewport.mapTop() + (subMinCZ + 4 - topChunk(viewport)) * chunkTileSize);

        graphics.fill(sx0, sy0, sx1, sy0 + 2, 0xFFFFD700);
        graphics.fill(sx0, sy1 - 2, sx1, sy1, 0xFFFFD700);
        graphics.fill(sx0, sy0, sx0 + 2, sy1, 0xFFFFD700);
        graphics.fill(sx1 - 2, sy0, sx1, sy1, 0xFFFFD700);
    }

    private void renderPlayerMarker(GuiGraphics graphics, MapViewport viewport) {
        int drawX = (int) Math.round(viewport.mapLeft() + (playerChunkX + 0.5D - leftChunk(viewport)) * chunkTileSize);
        int drawY = (int) Math.round(viewport.mapTop() + (playerChunkZ + 0.5D - topChunk(viewport)) * chunkTileSize);

        if (drawX >= viewport.mapLeft() && drawX <= viewport.mapLeft() + viewport.mapSize()
                && drawY >= viewport.mapTop() && drawY <= viewport.mapTop() + viewport.mapSize()) {
            graphics.fill(drawX - 3, drawY - 3, drawX + 4, drawY + 4, 0xFF000000);
            graphics.fill(drawX - 2, drawY - 2, drawX + 3, drawY + 3, 0xFF00FFFF);
        }
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

        // 4 Sub-Region Mission Buttons for Command Terminal (Percentage Responsive Sub-width)
        int subWidth = Math.max(20, (rightWidth - 4) / 2);
        for (int i = 0; i < 4; i++) {
            int subX = i % 2;
            int subZ = i / 2;
            int btnX = rightLeft + subX * (subWidth + 4);
            int btnY = top + subZ * (btnHeight + 4);
            int index = i;
            subRegionMissionButtons[i] = addRenderableWidget(Button.builder(
                    Component.literal(String.format("Sub(%d,%d): §7READY", subX, subZ)),
                    b -> toggleSubRegionMission(index))
                    .bounds(btnX, btnY, subWidth, btnHeight)
                    .build());
            subRegionMissionButtons[i].visible = false;
        }

        // Confirm Campaign button — appears below sub-region buttons after LAUNCH ATTACK is clicked
        int confirmY = top + 2 * (btnHeight + 4);
        confirmCampaignButton = addRenderableWidget(Button.builder(
                Component.literal("§a§lCONFIRM CAMPAIGN"),
                b -> onConfirmCampaign())
                .bounds(rightLeft, confirmY, rightWidth, btnHeight)
                .build());
        confirmCampaignButton.visible = false;

        // Header Top Tab Buttons (placed neatly on top-left of header bar)
        int tabY = viewport.frameTop() + 3;
        int tabWidth = Math.clamp((int) (viewport.frameWidth() * 0.12D), 60, 90);
        mapTabButton = addRenderableWidget(Button.builder(Component.literal("MAP VIEW"), b -> {
            activeTab = ActiveTab.MAP_VIEW;
            updateActionButtons();
        }).bounds(viewport.frameLeft() + 6, tabY, tabWidth, 18).build());

        logsTabButton = addRenderableWidget(Button.builder(Component.literal("LOG FEED"), b -> {
            activeTab = ActiveTab.INTELLIGENCE_LOGS;
            updateActionButtons();
        }).bounds(viewport.frameLeft() + 10 + tabWidth, tabY, tabWidth, 18).build());

        updateActionButtons();
    }

    private void toggleSubRegionMission(int index) {
        subRegionMissionToggled[index] = !subRegionMissionToggled[index];
        if (subRegionMissionButtons[index] != null) {
            int subX = index % 2;
            int subZ = index / 2;
            String status = subRegionMissionToggled[index] ? "§aACTIVE" : "§7READY";
            subRegionMissionButtons[index].setMessage(Component.literal(String.format("Sub(%d,%d): %s", subX, subZ, status)));
        }
    }

    private void renderSelectedRegionInfo(GuiGraphics graphics, MapViewport viewport) {
        int left = viewport.leftPanelLeft();
        int width = viewport.leftPanelWidth();
        int top = viewport.mapTop();
        int availableHeight = viewport.mapSize();

        int boxHeight = Math.clamp((availableHeight - 16) / 5, 24, 40);
        int boxGap = Math.clamp((availableHeight - (boxHeight * 5)) / 4, 2, 6);

        if (selectedRegion == null) {
            renderInfoBox(graphics, left, top, width, boxHeight,
                    Component.translatable("screen.warfront.region_coordinates"),
                    Component.literal("--, --"));
            return;
        }

        long minX = (long) selectedRegion.regionX() * RegionData.REGION_SIZE_BLOCKS;
        long minZ = (long) selectedRegion.regionZ() * RegionData.REGION_SIZE_BLOCKS;

        if (!isDebugMap && isCommandTerminalMap && !selectedRegion.isVisited()) {
            renderInfoBox(graphics, left, top, width, boxHeight,
                    Component.translatable("screen.warfront.region_coordinates"),
                    Component.literal(selectedRegion.regionX() + ", " + selectedRegion.regionZ()));
            renderInfoBox(graphics, left, top + (boxHeight + boxGap), width, boxHeight,
                    Component.translatable("screen.warfront.region_owner"),
                    Component.translatable("screen.warfront.undiscovered"));
            renderInfoBox(graphics, left, top + (boxHeight + boxGap) * 2, width, boxHeight,
                    Component.translatable("screen.warfront.stability"),
                    Component.translatable("screen.warfront.unknown"));
            renderInfoBox(graphics, left, top + (boxHeight + boxGap) * 3, width, boxHeight,
                    Component.translatable("screen.warfront.resistance"),
                    Component.translatable("screen.warfront.unknown"));
            return;
        }

        Component ownerDisplayName = selectedRegion.owner().displayName();
        if (selectedRegion.baseType() != BaseType.NONE) {
            ownerDisplayName = selectedRegion.baseType().getDisplayName(selectedRegion.owner());
        }

        renderInfoBox(graphics, left, top, width, boxHeight,
                Component.translatable("screen.warfront.region_coordinates"),
                Component.literal(selectedRegion.regionX() + ", " + selectedRegion.regionZ()));
        renderInfoBox(graphics, left, top + (boxHeight + boxGap), width, boxHeight,
                Component.translatable("screen.warfront.region_owner"), ownerDisplayName);
        renderInfoBox(graphics, left, top + (boxHeight + boxGap) * 2, width, boxHeight,
                Component.translatable("screen.warfront.stability"),
                Component.literal(formatMetric(selectedRegion.stability())));
        renderInfoBox(graphics, left, top + (boxHeight + boxGap) * 3, width, boxHeight,
                Component.translatable("screen.warfront.resistance"),
                Component.literal(formatMetric(selectedRegion.resistance())));

        // Dynamic Warfare Status (Live Defense Countdown or Attack Campaign Timer)
        if (selectedRegion.remainingSiegeTicks() > 0 || selectedRegion.underSiege()) {
            long totalSeconds = selectedRegion.remainingSiegeTicks() / 20L;
            long mins = totalSeconds / 60L;
            long secs = totalSeconds % 60L;
            String headerText = selectedRegion.owner() == Faction.HUMANITY ? "§e§lDEFENSE TIME" : "§c§lATTACK TIME";
            renderInfoBox(graphics, left, top + (boxHeight + boxGap) * 4, width, boxHeight,
                    Component.literal(headerText),
                    Component.literal(String.format("§fTime Left: §c%02d:%02d", mins, secs)));
        } else if (selectedRegion.owner() != Faction.HUMANITY && selectedRegion.owner() != Faction.UNCLAIMED) {
            renderInfoBox(graphics, left, top + (boxHeight + boxGap) * 4, width, boxHeight,
                    Component.literal("§c§lATTACK TARGET"),
                    Component.literal(String.format("§fReq: §e%d Sectors", selectedRegion.dominoThreshold())));
        }
    }

    private void renderInfoBox(GuiGraphics graphics, int left, int top, int width, int height, Component title,
            Component... lines) {
        graphics.fill(left, top, left + width, top + height, 0xFF1A1C20);
        graphics.fill(left, top, left + width, top + 1, 0xFF7A715D);
        graphics.fill(left, top + height - 1, left + width, top + height, 0xFF7A715D);
        graphics.fill(left, top, left + 1, top + height, 0xFF7A715D);
        graphics.fill(left + width - 1, top, left + width, top + height, 0xFF7A715D);
        if (width > 10) {
            graphics.enableScissor(left + 1, top + 1, left + width - 1, top + height - 1);
            graphics.drawString(font, title, left + 4, top + 3, 0xFFE8DFC8, false);
            for (int index = 0; index < lines.length; index++) {
                graphics.drawString(font, lines[index], left + 4, top + 13 + index * 9, 0xFFD0D0D0, false);
            }
            graphics.disableScissor();
        }
    }

    public void selectRegion(RegionDetailsPayload payload) {
        // Before overwriting selectedRegion, remember if THIS region was previously under siege.
        // We use this to distinguish between "siege just ended" vs "still in local staging" when
        // deciding whether to clear activatedRegions.
        boolean prevWasSieged = selectedRegion != null
                && selectedRegion.regionX() == payload.regionX()
                && selectedRegion.regionZ() == payload.regionZ()
                && selectedRegion.underSiege();

        selectedRegion = new SelectedRegion(
                payload.regionX(),
                payload.regionZ(),
                payload.subX(),
                payload.subZ(),
                Faction.byId(payload.factionId()),
                payload.stability(),
                payload.resistance(),
                BaseType.byId(payload.baseTypeId()),
                payload.underSiege(),
                payload.isVisited(),
                payload.remainingSiegeTicks(),
                payload.dominoThreshold(),
                payload.reachableMask(),
                payload.regionReachable(),
                payload.existingSiegeMask(),
                payload.conqueredMask());

        long regKey = ChunkPos.asLong(payload.regionX(), payload.regionZ());

        if (payload.underSiege() && payload.existingSiegeMask() != 0) {
            // Active server siege exists - restore sub-region button state from server campaign mask.
            activatedRegions.add(regKey);
            for (int i = 0; i < 4; i++) {
                int sx = i % 2;
                int sz = i / 2;
                int bit = sz * 2 + sx;
                boolean isConquered = (payload.conqueredMask() & (1 << bit)) != 0;
                if (!isConquered) {
                    boolean wasActive = (payload.existingSiegeMask() & (1 << bit)) != 0;
                    subRegionMissionToggled[i] = wasActive;
                } else {
                    subRegionMissionToggled[i] = false;
                }
            }
        } else if (!payload.underSiege() && prevWasSieged) {
            // Siege just ended (confirmed by server) - clear staging so LAUNCH ATTACK reappears.
            activatedRegions.remove(regKey);
        }
        // If !underSiege and !prevWasSieged: region is in local staging (LAUNCH ATTACK pressed
        // but CONFIRM not yet sent). Do NOT clear activatedRegions - this was the bug that caused
        // the 4-second periodic sync to wipe the staged state before the player could confirm.

        updateActionButtons();
    }

    private void updateActionButtons() {
        boolean isMapView = activeTab == ActiveTab.MAP_VIEW;

        // Handheld maps (isCommandTerminalMap = false) are strictly for information / recon -> HIDE ALL ACTION BUTTONS!
        if (!isCommandTerminalMap || !isMapView || selectedRegion == null || (!isDebugMap && !selectedRegion.isVisited())) {
            if (launchAttackButton != null) launchAttackButton.visible = false;
            if (defendAreaButton != null) defendAreaButton.visible = false;
            for (int i = 0; i < 4; i++) {
                if (subRegionMissionButtons[i] != null) subRegionMissionButtons[i].visible = false;
            }
            if (mapTabButton != null) mapTabButton.active = (activeTab != ActiveTab.MAP_VIEW);
            if (logsTabButton != null) logsTabButton.active = (activeTab != ActiveTab.INTELLIGENCE_LOGS);
            return;
        }

        boolean canAttack = selectedRegion.owner() != Faction.HUMANITY
                && selectedRegion.owner() != Faction.UNCLAIMED
                && selectedRegion.regionReachable()
                && selectedRegion.conqueredMask() != 0xF;

        boolean canDefend = selectedRegion.owner() == Faction.HUMANITY
                && selectedRegion.underSiege();

        long regKey = ChunkPos.asLong(selectedRegion.regionX(), selectedRegion.regionZ());
        boolean isActivated = activatedRegions.contains(regKey) || selectedRegion.underSiege();

        if (!isActivated) {
            // Before LAUNCH ATTACK pressed: show LAUNCH ATTACK or DEFEND AREA only
            launchAttackButton.visible = canAttack;
            defendAreaButton.visible = canDefend;
            if (confirmCampaignButton != null) confirmCampaignButton.visible = false;
            for (int i = 0; i < 4; i++) {
                if (subRegionMissionButtons[i] != null) subRegionMissionButtons[i].visible = false;
            }
        } else {
            // After LAUNCH ATTACK pressed: show sub-region mission buttons + CONFIRM CAMPAIGN
            launchAttackButton.visible = false;
            defendAreaButton.visible = false;
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
                        subRegionMissionToggled[i] = false;
                        subRegionMissionButtons[i].setMessage(Component.literal(
                                String.format("\u00a7aSub(%d,%d): SECURED", sx, sz)));
                    } else if (!reachable) {
                        subRegionMissionButtons[i].active = false;
                        subRegionMissionToggled[i] = false;
                        subRegionMissionButtons[i].setMessage(Component.literal(
                                String.format("\u00a78Sub(%d,%d): BLOCKED", sx, sz)));
                    } else {
                        subRegionMissionButtons[i].active = true;
                        String status = subRegionMissionToggled[i] ? "\u00a7aACTIVE" : "\u00a77READY";
                        subRegionMissionButtons[i].setMessage(Component.literal(
                                String.format("Sub(%d,%d): %s", sx, sz, status)));
                    }
                }
            }
        }

        if (mapTabButton != null) mapTabButton.active = (activeTab != ActiveTab.MAP_VIEW);
        if (logsTabButton != null) logsTabButton.active = (activeTab != ActiveTab.INTELLIGENCE_LOGS);
    }

    private void onLaunchAttack() {
        // Stage 1: Just reveal mission buttons + CONFIRM CAMPAIGN button; do NOT send server packet yet!
        if (selectedRegion != null
                && (isDebugMap || selectedRegion.isVisited())
                && selectedRegion.owner() != Faction.HUMANITY
                && selectedRegion.owner() != Faction.UNCLAIMED
                && !selectedRegion.underSiege()) {
            long regKey = ChunkPos.asLong(selectedRegion.regionX(), selectedRegion.regionZ());
            activatedRegions.add(regKey);
            // Reset all mission toggles for fresh selection
            for (int i = 0; i < 4; i++) {
                subRegionMissionToggled[i] = false;
                if (subRegionMissionButtons[i] != null) {
                    int sx = i % 2;
                    int sz = i / 2;
                    subRegionMissionButtons[i].setMessage(Component.literal(String.format("Sub(%d,%d): §7READY", sx, sz)));
                }
            }
            updateActionButtons();
        }
    }

    private void onConfirmCampaign() {
        if (selectedRegion == null || !(isDebugMap || selectedRegion.isVisited())) {
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
            if (!isSecured && subRegionMissionToggled[i]) {
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

        // Immediately request fresh region details so selectedRegion updates
        PacketDistributor.sendToServer(new RequestRegionDetailsPayload(
                selectedRegion.regionX(), selectedRegion.regionZ(),
                selectedRegion.subX(), selectedRegion.subZ(), isCommandTerminalMap, isDebugMap));
    }

    private void onDefendArea() {
        if (selectedRegion != null && selectedRegion.underSiege()) {
            long regKey = ChunkPos.asLong(selectedRegion.regionX(), selectedRegion.regionZ());
            activatedRegions.add(regKey);
            for (int i = 0; i < 4; i++) {
                subRegionMissionToggled[i] = false;
            }
            updateActionButtons();
        }
    }

    private String formatMetric(float value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private double leftChunk(MapViewport viewport) {
        return viewCenterX - viewport.mapSize() / (2.0D * chunkTileSize);
    }

    private double topChunk(MapViewport viewport) {
        return viewCenterZ - viewport.mapSize() / (2.0D * chunkTileSize);
    }

    private double minimumChunkTileSize(MapViewport viewport) {
        return viewport.mapSize() / (double) mapChunkDiameter();
    }

    private void clampView(MapViewport viewport) {
        double visibleChunks = viewport.mapSize() / chunkTileSize;
        double minimumCenterX = originChunkX + visibleChunks / 2.0D;
        double maximumCenterX = originChunkX + mapChunkDiameter() - visibleChunks / 2.0D;
        double minimumCenterZ = originChunkZ + visibleChunks / 2.0D;
        double maximumCenterZ = originChunkZ + mapChunkDiameter() - visibleChunks / 2.0D;
        viewCenterX = minimumCenterX > maximumCenterX ? (minimumCenterX + maximumCenterX) / 2.0D
                : Math.clamp(viewCenterX, minimumCenterX, maximumCenterX);
        viewCenterZ = minimumCenterZ > maximumCenterZ ? (minimumCenterZ + maximumCenterZ) / 2.0D
                : Math.clamp(viewCenterZ, minimumCenterZ, maximumCenterZ);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record MapViewport(int frameLeft, int frameTop, int frameWidth, int frameHeight, int mapAreaLeft, int leftPanelLeft, int leftPanelWidth, int rightPanelLeft, int rightPanelWidth, int mapLeft, int mapTop,
            int mapSize) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= mapLeft && mouseX <= mapLeft + mapSize && mouseY >= mapTop && mouseY <= mapTop + mapSize;
        }
    }

    private record SelectedRegion(int regionX, int regionZ, int subX, int subZ, Faction owner, float stability,
            float resistance, BaseType baseType, boolean underSiege, boolean isVisited, long remainingSiegeTicks,
            int dominoThreshold, int reachableMask, boolean regionReachable, int existingSiegeMask, int conqueredMask) {
        public SelectedRegion withRemainingSiegeTicks(long newTicks) {
            return new SelectedRegion(regionX, regionZ, subX, subZ, owner, stability, resistance, baseType, underSiege, isVisited, newTicks, dominoThreshold, reachableMask, regionReachable, existingSiegeMask, conqueredMask);
        }
    }

    private record MapRegion(int regionX, int regionZ) {
    }
}
