package com.warfront.client.map;

public record MapViewport(
        int frameLeft,
        int frameTop,
        int frameWidth,
        int frameHeight,
        int mapAreaLeft,
        int leftPanelLeft,
        int leftPanelWidth,
        int rightPanelLeft,
        int rightPanelWidth,
        int mapLeft,
        int mapTop,
        int mapSize
) {
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= mapLeft && mouseX <= mapLeft + mapSize && mouseY >= mapTop && mouseY <= mapTop + mapSize;
    }
}
