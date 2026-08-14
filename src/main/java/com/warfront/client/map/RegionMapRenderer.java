package com.warfront.client.map;

import com.warfront.network.RegionMapPayload;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.ChunkPos;

public final class RegionMapRenderer {
    private static final int HEADER_HEIGHT = 24;
    private static final int PANEL_MARGIN = 6;

    private static ResourceLocation nobaseLoc;
    private static ResourceLocation base1Loc;
    private static ResourceLocation base2Loc;
    private static ResourceLocation base3Loc;

    private static ResourceLocation getBaseTexture(BaseType baseType) {
        ensureBaseTexturesLoaded();
        return switch (baseType) {
            case OUTPOST -> base1Loc;
            case HEADQUARTERS -> base2Loc;
            case MEGA_BASE -> base3Loc;
            default -> nobaseLoc;
        };
    }

    private static void ensureBaseTexturesLoaded() {
        if (nobaseLoc != null) return;
        nobaseLoc = loadTexture("pillager_nobase");
        base1Loc = loadTexture("pillager_base1");
        base2Loc = loadTexture("pillager_base2");
        base3Loc = loadTexture("pillager_base3");
    }

    private static ResourceLocation loadTexture(String name) {
        String resPath = "/textures/" + name + ".png";
        String assetRelPath = "textures/gui/map/" + name + ".png";

        try {
            File srcFile = new File("src/main/resources" + resPath);
            File destFile = new File("src/main/resources/assets/warfront/" + assetRelPath);
            if (srcFile.exists() && !destFile.exists()) {
                destFile.getParentFile().mkdirs();
                java.nio.file.Files.copy(srcFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {}

        try (java.io.InputStream is = RegionMapRenderer.class.getResourceAsStream(resPath)) {
            if (is != null) {
                com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(is);
                DynamicTexture dynTex = new DynamicTexture(img);
                return Minecraft.getInstance().getTextureManager().register("warfront_" + name, dynTex);
            }
        } catch (Exception ignored) {}

        return ResourceLocation.fromNamespaceAndPath("warfront", assetRelPath);
    }

    private DynamicTexture mapTexture;
    private ResourceLocation mapTextureLocation;
    private boolean textureNeedsUpdate = true;

    public void markTextureDirty() {
        this.textureNeedsUpdate = true;
    }

    public void renderBackground(GuiGraphics graphics, int width, int height, float partialTick) {
        graphics.fill(0, 0, width, height, 0x90000000);
    }

    public void renderFrame(GuiGraphics graphics, MapViewport viewport) {
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

    public void renderMapView(GuiGraphics graphics, Font font, MapViewport viewport, RegionMapState state, RegionMapCamera camera, int mouseX, int mouseY) {
        graphics.enableScissor(viewport.mapLeft(), viewport.mapTop(),
                viewport.mapLeft() + viewport.mapSize(), viewport.mapTop() + viewport.mapSize());
        renderChunks(graphics, viewport, state, camera);
        renderFrontlineBorders(graphics, viewport);
        renderSiegeArrows(graphics, viewport, state, camera);
        renderRegionMarkers(graphics, viewport, state, camera);
        renderHoveredRegion(graphics, viewport, camera, mouseX, mouseY);
        renderSelectedRegionHighlight(graphics, viewport, state, camera);
        renderPlayerMarker(graphics, viewport, state, camera);
        graphics.disableScissor();

        renderSelectedRegionInfo(graphics, font, viewport, state);
    }

    private void updateDynamicTexture(RegionMapState state) {
        int diameter = state.mapChunkDiameter();
        if (mapTexture == null || mapTexture.getPixels().getWidth() != diameter) {
            mapTexture = new DynamicTexture(diameter, diameter, false);
            mapTextureLocation = Minecraft.getInstance().getTextureManager().register("region_map_texture", mapTexture);
        }

        com.mojang.blaze3d.platform.NativeImage image = mapTexture.getPixels();
        if (image != null) {
            for (int cz = 0; cz < diameter; cz++) {
                for (int cx = 0; cx < diameter; cx++) {
                    int chunkX = state.getOriginChunkX() + cx;
                    int chunkZ = state.getOriginChunkZ() + cz;
                    RegionMapPayload.ChunkData chunk = state.getChunks().get(ChunkPos.asLong(chunkX, chunkZ));
                    int colorABGR;
                    if (chunk == null) {
                        colorABGR = 0xFF000000;
                    } else if (!chunk.isVisited()) {
                        colorABGR = 0xFF181A1D;
                    } else {
                        int rawColor = chunk.biomeColor();
                        int br = (rawColor >> 16) & 0xFF;
                        int bg = (rawColor >> 8) & 0xFF;
                        int bb = rawColor & 0xFF;

                        boolean isSiege = chunk.underSiege();

                        if (chunk.factionId() != Faction.UNCLAIMED.id()) {
                            Faction faction = Faction.byId(chunk.factionId());
                            int fColor = faction.color();
                            int fr = (fColor >> 16) & 0xFF;
                            int fg = (fColor >> 8) & 0xFF;
                            int fb = fColor & 0xFF;

                            br = (br + fr) / 2;
                            bg = (bg + fg) / 2;
                            bb = (bb + fb) / 2;

                            RegionMapPayload.ChunkData north = state.getChunks().get(ChunkPos.asLong(chunkX, chunkZ - 1));
                            RegionMapPayload.ChunkData south = state.getChunks().get(ChunkPos.asLong(chunkX, chunkZ + 1));
                            RegionMapPayload.ChunkData west = state.getChunks().get(ChunkPos.asLong(chunkX - 1, chunkZ));
                            RegionMapPayload.ChunkData east = state.getChunks().get(ChunkPos.asLong(chunkX + 1, chunkZ));

                            boolean isBorder = (north == null || !north.isVisited() || north.factionId() != chunk.factionId())
                                    || (south == null || !south.isVisited() || south.factionId() != chunk.factionId())
                                    || (west == null || !west.isVisited() || west.factionId() != chunk.factionId())
                                    || (east == null || !east.isVisited() || east.factionId() != chunk.factionId());

                            boolean isClusterBorder = !isBorder && (
                                       (north != null && north.clusterId() != chunk.clusterId())
                                    || (south != null && south.clusterId() != chunk.clusterId())
                                    || (west != null && west.clusterId() != chunk.clusterId())
                                    || (east != null && east.clusterId() != chunk.clusterId()));

                            if (isBorder) {
                                // Preserve underlying terrain landshape readability by blending border tint (65% faction + 35% biome)
                                br = (br + fr * 2) / 3;
                                bg = (bg + fg * 2) / 3;
                                bb = (bb + fb * 2) / 3;
                            } else if (isClusterBorder) {
                                // Distinct bold dark charcoal/slate internal province border tint
                                br = br / 3;
                                bg = bg / 3;
                                bb = bb / 3;
                            }
                        }

                        if (isSiege) {
                            br = Math.min(255, br + 80);
                            bg = bg / 2;
                            bb = bb / 2;

                            RegionMapPayload.ChunkData north = state.getChunks().get(ChunkPos.asLong(chunkX, chunkZ - 1));
                            RegionMapPayload.ChunkData south = state.getChunks().get(ChunkPos.asLong(chunkX, chunkZ + 1));
                            RegionMapPayload.ChunkData west = state.getChunks().get(ChunkPos.asLong(chunkX - 1, chunkZ));
                            RegionMapPayload.ChunkData east = state.getChunks().get(ChunkPos.asLong(chunkX + 1, chunkZ));

                            boolean isSiegeBorder = (north == null || !north.underSiege())
                                    || (south == null || !south.underSiege())
                                    || (west == null || !west.underSiege())
                                    || (east == null || !east.underSiege());

                            if (isSiegeBorder) {
                                br = (br + 255 * 2) / 3;
                                bg = (bg + 34 * 2) / 3;
                                bb = (bb + 34 * 2) / 3;
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

    private void renderChunks(GuiGraphics graphics, MapViewport viewport, RegionMapState state, RegionMapCamera camera) {
        if (textureNeedsUpdate || mapTextureLocation == null) {
            updateDynamicTexture(state);
        }

        int diameter = state.mapChunkDiameter();
        double visibleChunks = viewport.mapSize() / camera.getChunkTileSize();

        double srcX = camera.getViewCenterX() - state.getOriginChunkX() - visibleChunks / 2.0D;
        double srcY = camera.getViewCenterZ() - state.getOriginChunkZ() - visibleChunks / 2.0D;

        float uOffset = (float) Math.floor(srcX);
        float vOffset = (float) Math.floor(srcY);

        double subPixelX = srcX - uOffset;
        double subPixelY = srcY - vOffset;

        int srcW = (int) Math.ceil(visibleChunks + subPixelX);
        int srcH = (int) Math.ceil(visibleChunks + subPixelY);

        int destX = (int) Math.round(viewport.mapLeft() - subPixelX * camera.getChunkTileSize());
        int destY = (int) Math.round(viewport.mapTop() - subPixelY * camera.getChunkTileSize());
        int destW = (int) Math.round(srcW * camera.getChunkTileSize());
        int destH = (int) Math.round(srcH * camera.getChunkTileSize());

        graphics.blit(mapTextureLocation,
                destX, destY,
                destW, destH,
                uOffset, vOffset,
                srcW, srcH,
                diameter, diameter);
    }

    private void renderFrontlineBorders(GuiGraphics graphics, MapViewport viewport) {
    }

    private void renderRegionMarkers(GuiGraphics graphics, MapViewport viewport, RegionMapState state, RegionMapCamera camera) {
        Map<Long, RegionMapPayload.RegionMarkerData> markerMap = new HashMap<>();
        for (RegionMapPayload.RegionMarkerData marker : state.getMarkers()) {
            markerMap.put(ChunkPos.asLong(marker.regionX(), marker.regionZ()), marker);
        }

        Set<Long> processedRegions = new HashSet<>();

        // 1. Render explicit base markers (Outpost, HQ, Mega Base)
        for (RegionMapPayload.RegionMarkerData marker : state.getMarkers()) {
            long regKey = ChunkPos.asLong(marker.regionX(), marker.regionZ());
            processedRegions.add(regKey);

            int regionX = marker.regionX();
            int regionZ = marker.regionZ();

            if (!isRegionVisible(state, regionX, regionZ)) {
                continue;
            }

            BaseType baseType = BaseType.byId(marker.baseTypeId());
            renderBaseIcon(graphics, viewport, camera, regionX, regionZ, baseType);
        }

        // 2. Render flag texture (NOBASE) for claimed regions without a base structure
        for (RegionMapPayload.ChunkData chunk : state.getChunks().values()) {
            if (chunk.factionId() != Faction.UNCLAIMED.id() && chunk.isVisited()) {
                int regionX = Math.floorDiv(chunk.chunkX(), 8);
                int regionZ = Math.floorDiv(chunk.chunkZ(), 8);
                long regKey = ChunkPos.asLong(regionX, regionZ);
                if (!processedRegions.contains(regKey)) {
                    processedRegions.add(regKey);
                    if (isRegionVisible(state, regionX, regionZ)) {
                        renderBaseIcon(graphics, viewport, camera, regionX, regionZ, BaseType.NONE);
                    }
                }
            }
        }
    }

    private boolean isRegionVisible(RegionMapState state, int regionX, int regionZ) {
        if (state.isDebugMap()) {
            return true;
        }
        int minCX = regionX * 8;
        int minCZ = regionZ * 8;
        for (int cx = 0; cx < 8; cx++) {
            for (int cz = 0; cz < 8; cz++) {
                RegionMapPayload.ChunkData c = state.getChunks().get(ChunkPos.asLong(minCX + cx, minCZ + cz));
                if (c != null && c.isVisited()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void renderBaseIcon(GuiGraphics graphics, MapViewport viewport, RegionMapCamera camera, int regionX, int regionZ, BaseType baseType) {
        ResourceLocation texture = getBaseTexture(baseType);
        double chunkSize;

        switch (baseType) {
            case OUTPOST -> chunkSize = 4.0D; // Fits within 4x4 chunks
            case HEADQUARTERS -> chunkSize = 4.0D; // Fits within 4x4 chunks
            case MEGA_BASE -> chunkSize = 6.0D; // Fits within 6x6 chunks
            default -> chunkSize = 2.0D; // BaseType.NONE -> Fits within 2x2 chunks (Flag)
        }

        double centerCX = regionX * 8 + 4.0D;
        double centerCZ = regionZ * 8 + 4.0D;

        double startCX = centerCX - (chunkSize / 2.0D);
        double startCZ = centerCZ - (chunkSize / 2.0D);

        int drawX = (int) Math.round(viewport.mapLeft() + (startCX - camera.leftChunk(viewport)) * camera.getChunkTileSize());
        int drawY = (int) Math.round(viewport.mapTop() + (startCZ - camera.topChunk(viewport)) * camera.getChunkTileSize());
        int drawW = (int) Math.round(chunkSize * camera.getChunkTileSize());
        int drawH = (int) Math.round(chunkSize * camera.getChunkTileSize());

        if (drawX + drawW < viewport.mapLeft() || drawX > viewport.mapLeft() + viewport.mapSize()
                || drawY + drawH < viewport.mapTop() || drawY > viewport.mapTop() + viewport.mapSize()) {
            return;
        }

        graphics.blit(texture, drawX, drawY, drawW, drawH, 0.0F, 0.0F, 1, 1, 1, 1);
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

    private void fillTriangle(GuiGraphics graphics, int x0, int y0, int x1, int y1, int x2, int y2, int color) {
        int minX = Math.min(x0, Math.min(x1, x2));
        int maxX = Math.max(x0, Math.max(x1, x2));
        int minY = Math.min(y0, Math.min(y1, y2));
        int maxY = Math.max(y0, Math.max(y1, y2));

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (isPointInTriangle(x, y, x0, y0, x1, y1, x2, y2)) {
                    graphics.fill(x, y, x + 1, y + 1, color);
                }
            }
        }
    }

    private boolean isPointInTriangle(int px, int py, int x0, int y0, int x1, int y1, int x2, int y2) {
        int d1 = (px - x1) * (y0 - y1) - (x0 - x1) * (py - y1);
        int d2 = (px - x2) * (y1 - y2) - (x1 - x2) * (py - y2);
        int d3 = (px - x0) * (y2 - y0) - (x2 - x0) * (py - y0);

        boolean hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);

        return !(hasNeg && hasPos);
    }

    private void drawSingleLine(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int x = x0;
        int y = y0;

        while (true) {
            graphics.fill(x, y, x + 1, y + 1, color);
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

    private void renderSiegeArrows(GuiGraphics graphics, MapViewport viewport, RegionMapState state, RegionMapCamera camera) {
        for (RegionMapPayload.SiegeArrowData arrow : state.getSiegeArrows()) {
            double srcCenterCX = arrow.sourceRegionX() * 8 + 4;
            double srcCenterCZ = arrow.sourceRegionZ() * 8 + 4;
            double tgtCenterCX = arrow.targetRegionX() * 8 + 4;
            double tgtCenterCZ = arrow.targetRegionZ() * 8 + 4;

            double dcx = tgtCenterCX - srcCenterCX;
            double dcz = tgtCenterCZ - srcCenterCZ;

            // Half-length arrow taking a quarter length offset inside each region
            double startCX = srcCenterCX + dcx * 0.25D;
            double startCZ = srcCenterCZ + dcz * 0.25D;
            double endCX = srcCenterCX + dcx * 0.75D;
            double endCZ = srcCenterCZ + dcz * 0.75D;

            int x0 = (int) Math.round(viewport.mapLeft() + (startCX - camera.leftChunk(viewport)) * camera.getChunkTileSize());
            int y0 = (int) Math.round(viewport.mapTop() + (startCZ - camera.topChunk(viewport)) * camera.getChunkTileSize());
            int x1 = (int) Math.round(viewport.mapLeft() + (endCX - camera.leftChunk(viewport)) * camera.getChunkTileSize());
            int y1 = (int) Math.round(viewport.mapTop() + (endCZ - camera.topChunk(viewport)) * camera.getChunkTileSize());

            int lineColor = 0xFFFF2222;
            drawThickLine(graphics, x0, y0, x1, y1, lineColor);

            double vx = x1 - x0;
            double vy = y1 - y0;
            double len = Math.hypot(vx, vy);

            if (len > 0.001D) {
                double ux = vx / len;
                double uy = vy / len;
                double nx = -uy;
                double ny = ux;

                // Scale arrowhead size proportionally with camera zoom like bases
                double arrowHeadLen = Math.max(8.0D, 2.0D * camera.getChunkTileSize());
                double arrowHeadWidth = Math.max(5.0D, 1.2D * camera.getChunkTileSize());

                int tipX = x1;
                int tipY = y1;
                int leftX = (int) Math.round(x1 - ux * arrowHeadLen + nx * arrowHeadWidth);
                int leftY = (int) Math.round(y1 - uy * arrowHeadLen + ny * arrowHeadWidth);
                int rightX = (int) Math.round(x1 - ux * arrowHeadLen - nx * arrowHeadWidth);
                int rightY = (int) Math.round(y1 - uy * arrowHeadLen - ny * arrowHeadWidth);

                int redFill = 0xFFFF2222;
                int redBorder = 0xFFFF0000;
                fillTriangle(graphics, tipX, tipY, leftX, leftY, rightX, rightY, redFill);
                drawSingleLine(graphics, tipX, tipY, leftX, leftY, redBorder);
                drawSingleLine(graphics, tipX, tipY, rightX, rightY, redBorder);
                drawSingleLine(graphics, leftX, leftY, rightX, rightY, redBorder);
            }
        }
    }

    private void renderHoveredRegion(GuiGraphics graphics, MapViewport viewport, RegionMapCamera camera, int mouseX, int mouseY) {
        if (!viewport.contains(mouseX, mouseY)) {
            return;
        }

        int chunkX = (int) Math.floor(camera.screenToChunkX(mouseX, viewport));
        int chunkZ = (int) Math.floor(camera.screenToChunkZ(mouseY, viewport));
        int regionX = Math.floorDiv(chunkX, 8);
        int regionZ = Math.floorDiv(chunkZ, 8);

        int minChunkX = regionX * 8;
        int minChunkZ = regionZ * 8;
        int x0 = (int) Math.round(viewport.mapLeft() + (minChunkX - camera.leftChunk(viewport)) * camera.getChunkTileSize());
        int y0 = (int) Math.round(viewport.mapTop() + (minChunkZ - camera.topChunk(viewport)) * camera.getChunkTileSize());
        int x1 = (int) Math.round(viewport.mapLeft() + (minChunkX + 8 - camera.leftChunk(viewport)) * camera.getChunkTileSize());
        int y1 = (int) Math.round(viewport.mapTop() + (minChunkZ + 8 - camera.topChunk(viewport)) * camera.getChunkTileSize());

        graphics.fill(x0, y0, x1, y0 + 1, 0x80FFFFFF);
        graphics.fill(x0, y1 - 1, x1, y1, 0x80FFFFFF);
        graphics.fill(x0, y0, x0 + 1, y1, 0x80FFFFFF);
        graphics.fill(x1 - 1, y0, x1, y1, 0x80FFFFFF);
    }

    private void renderSelectedRegionHighlight(GuiGraphics graphics, MapViewport viewport, RegionMapState state, RegionMapCamera camera) {
        SelectedRegion selectedRegion = state.getSelectedRegion();
        if (selectedRegion == null) {
            return;
        }

        int minChunkX = selectedRegion.regionX() * 8;
        int minChunkZ = selectedRegion.regionZ() * 8;
        int x0 = (int) Math.round(viewport.mapLeft() + (minChunkX - camera.leftChunk(viewport)) * camera.getChunkTileSize());
        int y0 = (int) Math.round(viewport.mapTop() + (minChunkZ - camera.topChunk(viewport)) * camera.getChunkTileSize());
        int x1 = (int) Math.round(viewport.mapLeft() + (minChunkX + 8 - camera.leftChunk(viewport)) * camera.getChunkTileSize());
        int y1 = (int) Math.round(viewport.mapTop() + (minChunkZ + 8 - camera.topChunk(viewport)) * camera.getChunkTileSize());

        graphics.fill(x0, y0, x1, y0 + 1, 0xFFFFFFFF);
        graphics.fill(x0, y1 - 1, x1, y1, 0xFFFFFFFF);
        graphics.fill(x0, y0, x0 + 1, y1, 0xFFFFFFFF);
        graphics.fill(x1 - 1, y0, x1, y1, 0xFFFFFFFF);

        int subMinCX = minChunkX + selectedRegion.subX() * 4;
        int subMinCZ = minChunkZ + selectedRegion.subZ() * 4;
        int sx0 = (int) Math.round(viewport.mapLeft() + (subMinCX - camera.leftChunk(viewport)) * camera.getChunkTileSize());
        int sy0 = (int) Math.round(viewport.mapTop() + (subMinCZ - camera.topChunk(viewport)) * camera.getChunkTileSize());
        int sx1 = (int) Math.round(viewport.mapLeft() + (subMinCX + 4 - camera.leftChunk(viewport)) * camera.getChunkTileSize());
        int sy1 = (int) Math.round(viewport.mapTop() + (subMinCZ + 4 - camera.topChunk(viewport)) * camera.getChunkTileSize());

        graphics.fill(sx0, sy0, sx1, sy0 + 2, 0xFFFFD700);
        graphics.fill(sx0, sy1 - 2, sx1, sy1, 0xFFFFD700);
        graphics.fill(sx0, sy0, sx0 + 2, sy1, 0xFFFFD700);
        graphics.fill(sx1 - 2, sy0, sx1, sy1, 0xFFFFD700);
    }

    private void renderPlayerMarker(GuiGraphics graphics, MapViewport viewport, RegionMapState state, RegionMapCamera camera) {
        int drawX = (int) Math.round(viewport.mapLeft() + (state.getPlayerChunkX() + 0.5D - camera.leftChunk(viewport)) * camera.getChunkTileSize());
        int drawY = (int) Math.round(viewport.mapTop() + (state.getPlayerChunkZ() + 0.5D - camera.topChunk(viewport)) * camera.getChunkTileSize());

        if (drawX >= viewport.mapLeft() && drawX <= viewport.mapLeft() + viewport.mapSize()
                && drawY >= viewport.mapTop() && drawY <= viewport.mapTop() + viewport.mapSize()) {
            graphics.fill(drawX - 3, drawY - 3, drawX + 4, drawY + 4, 0xFF000000);
            graphics.fill(drawX - 2, drawY - 2, drawX + 3, drawY + 3, 0xFF00FFFF);
        }
    }

    public void renderIntelligenceLogFeed(GuiGraphics graphics, Font font, MapViewport viewport, RegionMapState state) {
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
        List<String> logMessages = state.getLogMessages();
        for (int i = 0; i < logMessages.size(); i++) {
            allWrappedLines.addAll(font.split(Component.literal(logMessages.get(i)), panelWidth - 16));
        }

        int totalLines = allWrappedLines.size();
        int maxScroll = Math.max(0, totalLines - maxVisibleLines);
        int logScrollOffset = Math.clamp(state.getLogScrollOffset(), 0, maxScroll);
        state.setLogScrollOffset(logScrollOffset);

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
            int thumbY = top + 24 + (scrollbarHeight - thumbHeight) * (maxScroll - logScrollOffset) / Math.max(1, maxScroll);

            graphics.fill(scrollbarX, top + 24, scrollbarX + 3, top + 24 + scrollbarHeight, 0xFF2A2D32);
            graphics.fill(scrollbarX, thumbY, scrollbarX + 3, thumbY + thumbHeight, 0xFFCBB985);
        }
    }

    public void renderSelectedRegionInfo(GuiGraphics graphics, Font font, MapViewport viewport, RegionMapState state) {
        int left = viewport.leftPanelLeft();
        int width = viewport.leftPanelWidth();
        int top = viewport.mapTop();
        int availableHeight = viewport.mapSize();

        int boxHeight = Math.clamp((availableHeight - 16) / 5, 24, 40);
        int boxGap = Math.clamp((availableHeight - (boxHeight * 5)) / 4, 2, 6);

        SelectedRegion selectedRegion = state.getSelectedRegion();
        if (selectedRegion == null) {
            renderInfoBox(graphics, font, left, top, width, boxHeight,
                    Component.translatable("screen.warfront.region_coordinates"),
                    Component.literal("--, --"));
            return;
        }

        if (state.getViewType().hasFogOfWar() && !selectedRegion.isVisited()) {
            renderInfoBox(graphics, font, left, top, width, boxHeight,
                    Component.translatable("screen.warfront.region_coordinates"),
                    Component.literal(selectedRegion.regionX() + ", " + selectedRegion.regionZ()));
            renderInfoBox(graphics, font, left, top + (boxHeight + boxGap), width, boxHeight,
                    Component.translatable("screen.warfront.region_owner"),
                    Component.translatable("screen.warfront.undiscovered"));
            renderInfoBox(graphics, font, left, top + (boxHeight + boxGap) * 2, width, boxHeight,
                    Component.translatable("screen.warfront.stability"),
                    Component.translatable("screen.warfront.unknown"));
            renderInfoBox(graphics, font, left, top + (boxHeight + boxGap) * 3, width, boxHeight,
                    Component.translatable("screen.warfront.resistance"),
                    Component.translatable("screen.warfront.unknown"));
            return;
        }

        Component ownerDisplayName = selectedRegion.owner().displayName();
        if (selectedRegion.baseType() != BaseType.NONE) {
            ownerDisplayName = selectedRegion.baseType().getDisplayName(selectedRegion.owner());
        }

        renderInfoBox(graphics, font, left, top, width, boxHeight,
                Component.translatable("screen.warfront.region_coordinates"),
                Component.literal(selectedRegion.regionX() + ", " + selectedRegion.regionZ()));
        renderInfoBox(graphics, font, left, top + (boxHeight + boxGap), width, boxHeight,
                Component.translatable("screen.warfront.region_owner"), ownerDisplayName);
        renderInfoBox(graphics, font, left, top + (boxHeight + boxGap) * 2, width, boxHeight,
                Component.translatable("screen.warfront.stability"),
                Component.literal(formatMetric(selectedRegion.stability())));
        renderInfoBox(graphics, font, left, top + (boxHeight + boxGap) * 3, width, boxHeight,
                Component.translatable("screen.warfront.resistance"),
                Component.literal(formatMetric(selectedRegion.resistance())));

        if (selectedRegion.remainingSiegeTicks() > 0 || selectedRegion.underSiege()) {
            long totalSeconds = selectedRegion.remainingSiegeTicks() / 20L;
            long mins = totalSeconds / 60L;
            long secs = totalSeconds % 60L;
            String headerText = selectedRegion.owner() == Faction.HUMANITY ? "§e§lDEFENSE TIME" : "§c§lATTACK TIME";
            renderInfoBox(graphics, font, left, top + (boxHeight + boxGap) * 4, width, boxHeight,
                    Component.literal(headerText),
                    Component.literal(String.format("§fTime Left: §c%02d:%02d", mins, secs)));
        } else if (selectedRegion.owner() != Faction.HUMANITY && selectedRegion.owner() != Faction.UNCLAIMED) {
            renderInfoBox(graphics, font, left, top + (boxHeight + boxGap) * 4, width, boxHeight,
                    Component.literal("§c§lATTACK TARGET"),
                    Component.literal(String.format("§fReq: §e%d Sectors", selectedRegion.dominoThreshold())));
        }
    }

    private void renderInfoBox(GuiGraphics graphics, Font font, int left, int top, int width, int height, Component title,
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

    private String formatMetric(float value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
