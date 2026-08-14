package com.warfront.client.map;

public final class RegionMapCamera {
    public static final double MAX_CHUNK_TILE_SIZE = 48.0D;

    private double viewCenterX;
    private double viewCenterZ;
    private double chunkTileSize;
    private boolean panning;
    private boolean dragged;
    private double lastPanX;
    private double lastPanY;

    public RegionMapCamera(double initialCenterX, double initialCenterZ) {
        this.viewCenterX = initialCenterX;
        this.viewCenterZ = initialCenterZ;
    }

    public void init(MapViewport viewport, int mapDiameter) {
        this.chunkTileSize = minimumChunkTileSize(viewport, mapDiameter);
    }

    public double getViewCenterX() {
        return viewCenterX;
    }

    public double getViewCenterZ() {
        return viewCenterZ;
    }

    public double getChunkTileSize() {
        return chunkTileSize;
    }

    public boolean isPanning() {
        return panning;
    }

    public boolean isDragged() {
        return dragged;
    }

    public double leftChunk(MapViewport viewport) {
        return viewCenterX - viewport.mapSize() / (2.0D * chunkTileSize);
    }

    public double topChunk(MapViewport viewport) {
        return viewCenterZ - viewport.mapSize() / (2.0D * chunkTileSize);
    }

    public double screenToChunkX(double screenX, MapViewport viewport) {
        return leftChunk(viewport) + (screenX - viewport.mapLeft()) / chunkTileSize;
    }

    public double screenToChunkZ(double screenY, MapViewport viewport) {
        return topChunk(viewport) + (screenY - viewport.mapTop()) / chunkTileSize;
    }

    public double minimumChunkTileSize(MapViewport viewport, int mapDiameter) {
        return viewport.mapSize() / (double) mapDiameter;
    }

    public void clampView(MapViewport viewport, int mapDiameter, int originChunkX, int originChunkZ) {
        double visibleChunks = viewport.mapSize() / chunkTileSize;
        double minimumCenterX = originChunkX + visibleChunks / 2.0D;
        double maximumCenterX = originChunkX + mapDiameter - visibleChunks / 2.0D;
        double minimumCenterZ = originChunkZ + visibleChunks / 2.0D;
        double maximumCenterZ = originChunkZ + mapDiameter - visibleChunks / 2.0D;
        viewCenterX = minimumCenterX > maximumCenterX ? (minimumCenterX + maximumCenterX) / 2.0D
                : Math.clamp(viewCenterX, minimumCenterX, maximumCenterX);
        viewCenterZ = minimumCenterZ > maximumCenterZ ? (minimumCenterZ + maximumCenterZ) / 2.0D
                : Math.clamp(viewCenterZ, minimumCenterZ, maximumCenterZ);
    }

    public void startPan(double mouseX, double mouseY) {
        this.panning = true;
        this.dragged = false;
        this.lastPanX = mouseX;
        this.lastPanY = mouseY;
    }

    public boolean panTo(double mouseX, double mouseY) {
        if (!panning) {
            return false;
        }
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

    public void stopPan() {
        this.panning = false;
    }

    public boolean handleZoom(double mouseX, double mouseY, double scrollY, MapViewport viewport, int mapDiameter, int originChunkX, int originChunkZ) {
        double mouseChunkX = screenToChunkX(mouseX, viewport);
        double mouseChunkZ = screenToChunkZ(mouseY, viewport);
        double factor = (scrollY > 0) ? 1.15D : 0.87D;
        double newTileSize = Math.clamp(chunkTileSize * factor, minimumChunkTileSize(viewport, mapDiameter), MAX_CHUNK_TILE_SIZE);
        if (newTileSize != chunkTileSize) {
            chunkTileSize = newTileSize;
            viewCenterX = mouseChunkX - (mouseX - viewport.mapLeft()) / chunkTileSize + viewport.mapSize() / (2.0D * chunkTileSize);
            viewCenterZ = mouseChunkZ - (mouseY - viewport.mapTop()) / chunkTileSize + viewport.mapSize() / (2.0D * chunkTileSize);
            clampView(viewport, mapDiameter, originChunkX, originChunkZ);
            return true;
        }
        return false;
    }

    public void moveTo(double chunkX, double chunkZ) {
        this.viewCenterX = chunkX;
        this.viewCenterZ = chunkZ;
    }

    public void animateTo(double targetX, double targetZ, double targetZoom, long durationMs) {
    }
}
