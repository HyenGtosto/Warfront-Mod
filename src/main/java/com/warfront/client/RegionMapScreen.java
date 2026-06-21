package com.warfront.client;

import com.warfront.network.RegionMapPayload;
import com.warfront.network.RequestRegionMapPayload;
import com.warfront.region.Faction;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import org.lwjgl.glfw.GLFW;

public final class RegionMapScreen extends Screen {
    private static final int SCREEN_MARGIN = 24;
    private static final int FRAME_SIZE = 10;
    private static final int HEADER_HEIGHT = 22;
    private static final double MAX_CHUNK_TILE_SIZE = 48.0D;

    private final int playerChunkX;
    private final int playerChunkZ;
    private final int originChunkX;
    private final int originChunkZ;
    private final Map<Long, RegionMapPayload.ChunkData> chunks = new HashMap<>();
    private double viewCenterX;
    private double viewCenterZ;
    private double chunkTileSize;
    private boolean panning;
    private double lastPanX;
    private double lastPanY;

    public RegionMapScreen(RegionMapPayload payload) {
        super(Component.translatable("screen.warfront.strategic_map"));
        playerChunkX = payload.centerChunkX();
        playerChunkZ = payload.centerChunkZ();
        originChunkX = playerChunkX - RequestRegionMapPayload.MAP_CHUNK_DIAMETER / 2;
        originChunkZ = playerChunkZ - RequestRegionMapPayload.MAP_CHUNK_DIAMETER / 2;
        viewCenterX = playerChunkX + 0.5D;
        viewCenterZ = playerChunkZ + 0.5D;
        for (RegionMapPayload.ChunkData chunk : payload.chunks()) {
            chunks.put(ChunkPos.asLong(chunk.chunkX(), chunk.chunkZ()), chunk);
        }
    }

    @Override
    protected void init() {
        chunkTileSize = minimumChunkTileSize(viewport());
        clampView(viewport());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        MapViewport viewport = viewport();
        clampView(viewport);

        graphics.fill(0, 0, width, height, 0x90000000);
        renderFrame(graphics, viewport);
        graphics.drawCenteredString(font, title, width / 2, viewport.frameTop() + 7, 0xF4E6C3);

        graphics.enableScissor(viewport.mapLeft(), viewport.mapTop(),
                viewport.mapLeft() + viewport.mapSize(), viewport.mapTop() + viewport.mapSize());
        renderChunks(graphics, viewport);
        renderPlayerMarker(graphics, viewport);
        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && viewport().contains(mouseX, mouseY)) {
            panning = true;
            lastPanX = mouseX;
            lastPanY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panning && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            viewCenterX -= (mouseX - lastPanX) / chunkTileSize;
            viewCenterZ -= (mouseY - lastPanY) / chunkTileSize;
            lastPanX = mouseX;
            lastPanY = mouseY;
            clampView(viewport());
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && panning) {
            panning = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        MapViewport viewport = viewport();
        if (scrollY == 0.0D || !viewport.contains(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        double worldX = screenToChunkX(mouseX, viewport);
        double worldZ = screenToChunkZ(mouseY, viewport);
        double oldTileSize = chunkTileSize;
        chunkTileSize = Math.clamp(chunkTileSize * Math.pow(1.2D, scrollY), minimumChunkTileSize(viewport), MAX_CHUNK_TILE_SIZE);
        if (chunkTileSize == oldTileSize) {
            return true;
        }

        viewCenterX = worldX - (mouseX - viewport.mapLeft()) / chunkTileSize + viewport.mapSize() / (2.0D * chunkTileSize);
        viewCenterZ = worldZ - (mouseY - viewport.mapTop()) / chunkTileSize + viewport.mapSize() / (2.0D * chunkTileSize);
        clampView(viewport);
        return true;
    }

    private MapViewport viewport() {
        int frameSize = Math.max(128, Math.min(width - SCREEN_MARGIN * 2, height - SCREEN_MARGIN * 2));
        int mapSize = frameSize - HEADER_HEIGHT - FRAME_SIZE * 2;
        int frameLeft = (width - frameSize) / 2;
        int frameTop = (height - frameSize) / 2;
        int mapLeft = frameLeft + (frameSize - mapSize) / 2;
        int mapTop = frameTop + HEADER_HEIGHT + FRAME_SIZE;
        return new MapViewport(frameLeft, frameTop, frameSize, mapLeft, mapTop, mapSize);
    }

    private void renderFrame(GuiGraphics graphics, MapViewport viewport) {
        int left = viewport.frameLeft();
        int top = viewport.frameTop();
        int frameSize = viewport.frameSize();
        graphics.fill(left, top, left + frameSize, top + frameSize, 0xFF17191D);
        graphics.fill(left + 2, top + 2, left + frameSize - 2, top + frameSize - 2, 0xFF5A5142);
        graphics.fill(left + 4, top + 4, left + frameSize - 4, top + frameSize - 4, 0xFF302C27);
        graphics.fill(viewport.mapLeft() - 2, viewport.mapTop() - 2,
                viewport.mapLeft() + viewport.mapSize() + 2, viewport.mapTop() + viewport.mapSize() + 2, 0xFF121417);
        graphics.fill(left + 4, top + 4, left + frameSize - 4, top + 5, 0xFFCBB985);
        graphics.fill(left + 4, top + 4, left + 5, top + frameSize - 4, 0xFFCBB985);
    }

    private void renderChunks(GuiGraphics graphics, MapViewport viewport) {
        double leftChunk = viewCenterX - viewport.mapSize() / (2.0D * chunkTileSize);
        double topChunk = viewCenterZ - viewport.mapSize() / (2.0D * chunkTileSize);

        for (RegionMapPayload.ChunkData chunk : chunks.values()) {
            int left = (int) Math.floor(viewport.mapLeft() + (chunk.chunkX() - leftChunk) * chunkTileSize);
            int top = (int) Math.floor(viewport.mapTop() + (chunk.chunkZ() - topChunk) * chunkTileSize);
            int right = (int) Math.ceil(viewport.mapLeft() + (chunk.chunkX() + 1 - leftChunk) * chunkTileSize);
            int bottom = (int) Math.ceil(viewport.mapTop() + (chunk.chunkZ() + 1 - topChunk) * chunkTileSize);
            if (right <= viewport.mapLeft() || bottom <= viewport.mapTop()
                    || left >= viewport.mapLeft() + viewport.mapSize() || top >= viewport.mapTop() + viewport.mapSize()) {
                continue;
            }

            graphics.fill(left, top, right, bottom, blendWithFaction(chunk.biomeColor(), Faction.byId(chunk.factionId())));
        }
    }

    private int blendWithFaction(int biomeColor, Faction faction) {
        int red = biomeColor >> 16 & 0xFF;
        int green = biomeColor >> 8 & 0xFF;
        int blue = biomeColor & 0xFF;
        if (faction == Faction.UNCLAIMED) {
            return 0xFF000000 | red << 16 | green << 8 | blue;
        }

        int factionColor = faction.color();
        red = (red * 2 + (factionColor >> 16 & 0xFF) * 3) / 5;
        green = (green * 2 + (factionColor >> 8 & 0xFF) * 3) / 5;
        blue = (blue * 2 + (factionColor & 0xFF) * 3) / 5;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private void renderPlayerMarker(GuiGraphics graphics, MapViewport viewport) {
        int centerX = (int) Math.round(viewport.mapLeft() + (playerChunkX + 0.5D - leftChunk(viewport)) * chunkTileSize);
        int centerY = (int) Math.round(viewport.mapTop() + (playerChunkZ + 0.5D - topChunk(viewport)) * chunkTileSize);
        graphics.fill(centerX - 1, centerY - 3, centerX + 2, centerY + 4, 0xFFFFFFFF);
        graphics.fill(centerX - 3, centerY - 1, centerX + 4, centerY + 2, 0xFFFFFFFF);
        graphics.fill(centerX, centerY - 2, centerX + 1, centerY + 3, 0xFF17191D);
        graphics.fill(centerX - 2, centerY, centerX + 3, centerY + 1, 0xFF17191D);
    }

    private double screenToChunkX(double screenX, MapViewport viewport) {
        return leftChunk(viewport) + (screenX - viewport.mapLeft()) / chunkTileSize;
    }

    private double screenToChunkZ(double screenY, MapViewport viewport) {
        return topChunk(viewport) + (screenY - viewport.mapTop()) / chunkTileSize;
    }

    private double leftChunk(MapViewport viewport) {
        return viewCenterX - viewport.mapSize() / (2.0D * chunkTileSize);
    }

    private double topChunk(MapViewport viewport) {
        return viewCenterZ - viewport.mapSize() / (2.0D * chunkTileSize);
    }

    private double minimumChunkTileSize(MapViewport viewport) {
        return viewport.mapSize() / (double) RequestRegionMapPayload.MAP_CHUNK_DIAMETER;
    }

    private void clampView(MapViewport viewport) {
        double visibleChunks = viewport.mapSize() / chunkTileSize;
        double minimumCenterX = originChunkX + visibleChunks / 2.0D;
        double maximumCenterX = originChunkX + RequestRegionMapPayload.MAP_CHUNK_DIAMETER - visibleChunks / 2.0D;
        double minimumCenterZ = originChunkZ + visibleChunks / 2.0D;
        double maximumCenterZ = originChunkZ + RequestRegionMapPayload.MAP_CHUNK_DIAMETER - visibleChunks / 2.0D;
        viewCenterX = minimumCenterX > maximumCenterX ? (minimumCenterX + maximumCenterX) / 2.0D
                : Math.clamp(viewCenterX, minimumCenterX, maximumCenterX);
        viewCenterZ = minimumCenterZ > maximumCenterZ ? (minimumCenterZ + maximumCenterZ) / 2.0D
                : Math.clamp(viewCenterZ, minimumCenterZ, maximumCenterZ);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record MapViewport(int frameLeft, int frameTop, int frameSize, int mapLeft, int mapTop, int mapSize) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= mapLeft && mouseX < mapLeft + mapSize && mouseY >= mapTop && mouseY < mapTop + mapSize;
        }
    }
}
