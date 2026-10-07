package com.warfront.client.map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.ChunkPos;
import org.joml.Matrix4f;

public final class RegionMapRenderer {
    private static final int HEADER_HEIGHT = 24;
    private static final int PANEL_MARGIN = 6;

    private static ResourceLocation nobaseLoc;
    private static ResourceLocation base1Loc;
    private static ResourceLocation base2Loc;
    private static ResourceLocation base3Loc;
    private static ResourceLocation missionLogoLoc;
    private static ResourceLocation warAttackLoc;
    private static ResourceLocation warDefenseLoc;

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
        missionLogoLoc = loadTexture("mission_logo");
        warAttackLoc = loadTexture("war_attack");
        warDefenseLoc = loadTexture("war_defense");
    }

    private static ResourceLocation loadTexture(String name) {
        return ResourceLocation.fromNamespaceAndPath("warfront", "textures/gui/map/" + name + ".png");
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
        renderFrontlineBorders(graphics, viewport, state, camera);
        renderSiegeArrows(graphics, viewport, state, camera);
        renderRegionMarkers(graphics, viewport, state, camera);
        renderWarStatusIcons(graphics, viewport, state, camera);
        renderHoveredRegion(graphics, viewport, camera, mouseX, mouseY);
        renderSelectedRegionHighlight(graphics, viewport, state, camera);
        renderSelectedSubRegionFilter(graphics, viewport, state, camera);
        renderMissionIcons(graphics, viewport, state, camera);
        renderPlayerMarker(graphics, viewport, state, camera);
        graphics.disableScissor();

        renderSelectedRegionInfo(graphics, font, viewport, state);
    }

    private boolean isChunkSieged(RegionMapPayload.ChunkData chunk, int chunkX, int chunkZ, RegionMapState state) {
        if (chunk == null) return false;
        if (chunk.underSiege()) return true;
        if (state.getSelectedRegion() != null) {
            SelectedRegion sel = state.getSelectedRegion();
            int rx = Math.floorDiv(chunkX, 8);
            int rz = Math.floorDiv(chunkZ, 8);
            if (sel.regionX() == rx && sel.regionZ() == rz) {
                int subX = Math.floorMod(chunkX, 8) >= 4 ? 1 : 0;
                int subZ = Math.floorMod(chunkZ, 8) >= 4 ? 1 : 0;
                int bit = subZ * 2 + subX;
                return state.isSubRegionConfirmed(rx, rz, bit) && (sel.conqueredMask() & (1 << bit)) == 0;
            }
        }
        return false;
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

                        boolean isSiege = isChunkSieged(chunk, chunkX, chunkZ, state);

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

                            boolean isClusterBorder = (chunk.factionId() != Faction.HUMANITY.id() && chunk.factionId() != Faction.UNCLAIMED.id()) && !isBorder && (
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

                            boolean isSiegeBorder = !isChunkSieged(north, chunkX, chunkZ - 1, state)
                                    || !isChunkSieged(south, chunkX, chunkZ + 1, state)
                                    || !isChunkSieged(west, chunkX - 1, chunkZ, state)
                                    || !isChunkSieged(east, chunkX + 1, chunkZ, state);

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

    private void renderFrontlineBorders(GuiGraphics graphics, MapViewport viewport, RegionMapState state, RegionMapCamera camera) {
        Set<Long> warRegions = new HashSet<>();
        for (RegionMapPayload.ActiveWarData war : state.getActiveWars()) {
            warRegions.add(ChunkPos.asLong(war.regionX(), war.regionZ()));
        }
        warRegions.addAll(state.getActivatedRegions());
        if (state.getSelectedRegion() != null && state.getSelectedRegion().underSiege()) {
            warRegions.add(ChunkPos.asLong(state.getSelectedRegion().regionX(), state.getSelectedRegion().regionZ()));
        }

        if (warRegions.isEmpty()) return;

        long timeMs = System.currentTimeMillis();
        float pulse = (float) (Math.sin(timeMs / 200.0D) * 0.5D + 0.5D);
        int alpha = (int) (120 + pulse * 135);
        int borderColor = (alpha << 24) | 0xFF2222;
        int glowColor = ((alpha / 3) << 24) | 0xFF3333;

        for (long regKey : warRegions) {
            int rx = ChunkPos.getX(regKey);
            int rz = ChunkPos.getZ(regKey);

            int minCX = rx * 8;
            int minCZ = rz * 8;

            int x0 = (int) Math.round(viewport.mapLeft() + (minCX - camera.leftChunk(viewport)) * camera.getChunkTileSize());
            int y0 = (int) Math.round(viewport.mapTop() + (minCZ - camera.topChunk(viewport)) * camera.getChunkTileSize());
            int x1 = (int) Math.round(viewport.mapLeft() + (minCX + 8 - camera.leftChunk(viewport)) * camera.getChunkTileSize());
            int y1 = (int) Math.round(viewport.mapTop() + (minCZ + 8 - camera.topChunk(viewport)) * camera.getChunkTileSize());

            // 1. Strict frustum culling FIRST before any lookups
            if (x1 < viewport.mapLeft() || x0 > viewport.mapLeft() + viewport.mapSize()
                    || y1 < viewport.mapTop() || y0 > viewport.mapTop() + viewport.mapSize()) {
                continue;
            }

            // 2. Fast O(1) fog-of-war check
            if (!state.isRegionVisible(rx, rz)) {
                continue;
            }

            // 1px subtle glow
            graphics.fill(x0 - 1, y0 - 1, x1 + 1, y0, glowColor);
            graphics.fill(x0 - 1, y1, x1 + 1, y1 + 1, glowColor);
            graphics.fill(x0 - 1, y0, x0, y1, glowColor);
            graphics.fill(x1, y0, x1 + 1, y1, glowColor);

            // 2px pulsating combat border
            graphics.fill(x0, y0, x1, y0 + 2, borderColor);
            graphics.fill(x0, y1 - 2, x1, y1, borderColor);
            graphics.fill(x0, y0, x0 + 2, y1, borderColor);
            graphics.fill(x1 - 2, y0, x1, y1, borderColor);
        }
    }

    private void renderWarStatusIcons(GuiGraphics graphics, MapViewport viewport, RegionMapState state, RegionMapCamera camera) {
        ensureBaseTexturesLoaded();
        if (warAttackLoc == null || warDefenseLoc == null) return;

        Set<Long> warRegions = new HashSet<>();
        for (RegionMapPayload.ActiveWarData war : state.getActiveWars()) {
            warRegions.add(ChunkPos.asLong(war.regionX(), war.regionZ()));
        }
        warRegions.addAll(state.getActivatedRegions());
        if (state.getSelectedRegion() != null && state.getSelectedRegion().underSiege()) {
            warRegions.add(ChunkPos.asLong(state.getSelectedRegion().regionX(), state.getSelectedRegion().regionZ()));
        }

        if (warRegions.isEmpty()) return;

        SelectedRegion sel = state.getSelectedRegion();

        for (long regKey : warRegions) {
            int rx = ChunkPos.getX(regKey);
            int rz = ChunkPos.getZ(regKey);

            double centerCX = rx * 8 + 4.0D;
            double centerCZ = rz * 8 + 4.0D;
            double iconChunks = 3.5D;
            double startCX = centerCX - (iconChunks / 2.0D);
            double startCZ = centerCZ - (iconChunks / 2.0D);

            int drawX = (int) Math.round(viewport.mapLeft() + (startCX - camera.leftChunk(viewport)) * camera.getChunkTileSize());
            int drawY = (int) Math.round(viewport.mapTop() + (startCZ - camera.topChunk(viewport)) * camera.getChunkTileSize());
            int drawW = (int) Math.round(iconChunks * camera.getChunkTileSize());
            int drawH = (int) Math.round(iconChunks * camera.getChunkTileSize());

            // 1. Strict frustum culling FIRST
            if (drawX + drawW < viewport.mapLeft() || drawX > viewport.mapLeft() + viewport.mapSize()
                    || drawY + drawH < viewport.mapTop() || drawY > viewport.mapTop() + viewport.mapSize()) {
                continue;
            }

            // 2. Fast O(1) fog-of-war check
            if (!state.isRegionVisible(rx, rz)) {
                continue;
            }

            // Disappear when inspecting and selecting missions on this region; appear when no longer selected
            boolean isSelected = (sel != null && sel.regionX() == rx && sel.regionZ() == rz);
            if (isSelected) {
                boolean isDefense = (sel.underSiege() && sel.attacker() != Faction.HUMANITY && sel.attacker() != Faction.UNCLAIMED)
                        || (sel.owner() == Faction.HUMANITY && sel.underSiege());
                boolean isActivated = isDefense || state.getActivatedRegions().contains(regKey) || sel.underSiege();
                if (isActivated) {
                    continue;
                }
            }

            RegionMapPayload.ActiveWarData activeWar = state.getActiveWar(rx, rz);
            boolean isDefense = false;
            if (activeWar != null) {
                isDefense = activeWar.isDefense();
            } else if (isSelected) {
                isDefense = (sel.underSiege() && sel.attacker() != Faction.HUMANITY && sel.attacker() != Faction.UNCLAIMED)
                        || (sel.owner() == Faction.HUMANITY && sel.underSiege());
            }

            ResourceLocation texture = isDefense ? warDefenseLoc : warAttackLoc;
            graphics.blit(texture, drawX, drawY, drawW, drawH, 0.0F, 0.0F, 1, 1, 1, 1);
        }
    }

    private void renderRegionMarkers(GuiGraphics graphics, MapViewport viewport, RegionMapState state, RegionMapCamera camera) {
        double tileSize = camera.getChunkTileSize();
        double visibleChunks = viewport.mapSize() / tileSize;
        double leftChunk = camera.leftChunk(viewport);
        double topChunk = camera.topChunk(viewport);

        int minRX = (int) Math.floor(leftChunk / 8.0D);
        int maxRX = (int) Math.ceil((leftChunk + visibleChunks) / 8.0D);
        int minRZ = (int) Math.floor(topChunk / 8.0D);
        int maxRZ = (int) Math.ceil((topChunk + visibleChunks) / 8.0D);

        for (int rx = minRX; rx <= maxRX; rx++) {
            for (int rz = minRZ; rz <= maxRZ; rz++) {
                if (!state.isRegionVisible(rx, rz)) {
                    continue;
                }
                BaseType baseType = state.getRegionBase(rx, rz);
                if (baseType != null) {
                    renderBaseIcon(graphics, viewport, camera, rx, rz, baseType);
                }
            }
        }
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

    private static void drawQuad(VertexConsumer vc, Matrix4f mat,
            float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int color) {
        vc.addVertex(mat, x1, y1, 0.0f).setColor(color);
        vc.addVertex(mat, x2, y2, 0.0f).setColor(color);
        vc.addVertex(mat, x3, y3, 0.0f).setColor(color);
        vc.addVertex(mat, x4, y4, 0.0f).setColor(color);
    }

    private static void drawThickLineQuad(VertexConsumer vc, Matrix4f mat,
            float x0, float y0, float x1, float y1, float thickness, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        if (len < 0.001f) return;
        float nx = -dy / len;
        float ny = dx / len;
        float h = thickness * 0.5f;
        drawQuad(vc, mat,
                x0 + nx * h, y0 + ny * h,
                x1 + nx * h, y1 + ny * h,
                x1 - nx * h, y1 - ny * h,
                x0 - nx * h, y0 - ny * h,
                color);
    }

    private void renderSiegeArrows(GuiGraphics graphics, MapViewport viewport, RegionMapState state, RegionMapCamera camera) {
        List<RegionMapPayload.SiegeArrowData> arrows = state.getSiegeArrows();
        if (arrows.isEmpty()) return;

        double tileSize = camera.getChunkTileSize();
        double leftChunk = camera.leftChunk(viewport);
        double topChunk = camera.topChunk(viewport);
        int mapLeft = viewport.mapLeft();
        int mapTop = viewport.mapTop();
        int mapSize = viewport.mapSize();

        int vMinX = mapLeft;
        int vMaxX = mapLeft + mapSize;
        int vMinY = mapTop;
        int vMaxY = mapTop + mapSize;

        Matrix4f matrix = graphics.pose().last().pose();
        VertexConsumer vc = graphics.bufferSource().getBuffer(RenderType.gui());

        int lineColor = 0xFFFF2222;
        int redFill = 0xFFFF2222;
        int redBorder = 0xFFFF0000;

        for (RegionMapPayload.SiegeArrowData arrow : arrows) {
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

            float x0 = (float) (mapLeft + (startCX - leftChunk) * tileSize);
            float y0 = (float) (mapTop + (startCZ - topChunk) * tileSize);
            float x1 = (float) (mapLeft + (endCX - leftChunk) * tileSize);
            float y1 = (float) (mapTop + (endCZ - topChunk) * tileSize);

            // Strict viewport frustum culling: skip immediately if arrow bounding box is off-screen
            float minX = Math.min(x0, x1) - 32.0f;
            float maxX = Math.max(x0, x1) + 32.0f;
            float minY = Math.min(y0, y1) - 32.0f;
            float maxY = Math.max(y0, y1) + 32.0f;

            if (maxX < vMinX || minX > vMaxX || maxY < vMinY || minY > vMaxY) {
                continue;
            }

            float vx = x1 - x0;
            float vy = y1 - y0;
            float len = (float) Math.hypot(vx, vy);

            if (len > 0.001f) {
                float ux = vx / len;
                float uy = vy / len;
                float nx = -uy;
                float ny = ux;

                // Scale arrowhead size proportionally with zoom, but capped to sleek tactical proportions
                float arrowHeadLen = (float) Math.clamp(1.8D * tileSize, 8.0D, 22.0D);
                float arrowHeadWidth = (float) Math.clamp(1.0D * tileSize, 5.0D, 13.0D);
                float shaftThickness = (float) Math.clamp(0.2D * tileSize, 2.0D, 4.0D);

                // Shaft stops at the base of the arrowhead (overlapping slightly into head to avoid seam)
                float shaftEndLen = Math.max(0.0f, len - arrowHeadLen + shaftThickness * 0.5f);
                float sx1 = x0 + ux * shaftEndLen;
                float sy1 = y0 + uy * shaftEndLen;

                // 1. Draw shaft as single quad
                float hThick = shaftThickness * 0.5f;
                drawQuad(vc, matrix,
                        x0 + nx * hThick, y0 + ny * hThick,
                        sx1 + nx * hThick, sy1 + ny * hThick,
                        sx1 - nx * hThick, sy1 - ny * hThick,
                        x0 - nx * hThick, y0 - ny * hThick,
                        lineColor);

                // 2. Arrowhead geometry
                float tipX = x1;
                float tipY = y1;
                float leftX = x1 - ux * arrowHeadLen + nx * arrowHeadWidth;
                float leftY = y1 - uy * arrowHeadLen + ny * arrowHeadWidth;
                float rightX = x1 - ux * arrowHeadLen - nx * arrowHeadWidth;
                float rightY = y1 - uy * arrowHeadLen - ny * arrowHeadWidth;
                float midX = (leftX + rightX) * 0.5f;
                float midY = (leftY + rightY) * 0.5f;

                // 3. Arrowhead solid fill (non-degenerate quad covering both halves of the triangle, rendered in both winding orders)
                drawQuad(vc, matrix, tipX, tipY, rightX, rightY, midX, midY, leftX, leftY, redFill);
                drawQuad(vc, matrix, tipX, tipY, leftX, leftY, midX, midY, rightX, rightY, redFill);

                // 4. Arrowhead borders (3 crisp quads)
                drawThickLineQuad(vc, matrix, tipX, tipY, leftX, leftY, 1.5f, redBorder);
                drawThickLineQuad(vc, matrix, leftX, leftY, rightX, rightY, 1.5f, redBorder);
                drawThickLineQuad(vc, matrix, rightX, rightY, tipX, tipY, 1.5f, redBorder);
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

    /**
     * Draws a semi-transparent dark overlay over each subregion that is currently
     * toggled (selected by the player) on an activated region. Provides a map-level
     * visual cue consistent with the existing faction-color filter approach.
     */
    private void renderSelectedSubRegionFilter(GuiGraphics graphics, MapViewport viewport, RegionMapState state, RegionMapCamera camera) {
        SelectedRegion sel = state.getSelectedRegion();
        if (sel == null) return;

        long regKey = net.minecraft.world.level.ChunkPos.asLong(sel.regionX(), sel.regionZ());
        boolean isActivated = state.getActivatedRegions().contains(regKey) || sel.underSiege();
        if (!isActivated) return;

        for (int i = 0; i < 4; i++) {
            boolean isSelectedOrActive = state.isSubRegionMissionToggled(i) || state.isSubRegionConfirmed(sel.regionX(), sel.regionZ(), i);
            if (!isSelectedOrActive) continue;

            int subX = i % 2;
            int subZ = i / 2;
            int bit = subZ * 2 + subX;
            if ((sel.conqueredMask() & (1 << bit)) != 0) continue; // skip conquered

            int subMinCX = sel.regionX() * 8 + subX * 4;
            int subMinCZ = sel.regionZ() * 8 + subZ * 4;

            int sx0 = (int) Math.round(viewport.mapLeft() + (subMinCX - camera.leftChunk(viewport)) * camera.getChunkTileSize());
            int sy0 = (int) Math.round(viewport.mapTop() + (subMinCZ - camera.topChunk(viewport)) * camera.getChunkTileSize());
            int sx1 = (int) Math.round(viewport.mapLeft() + (subMinCX + 4 - camera.leftChunk(viewport)) * camera.getChunkTileSize());
            int sy1 = (int) Math.round(viewport.mapTop() + (subMinCZ + 4 - camera.topChunk(viewport)) * camera.getChunkTileSize());

            // Semi-transparent dark overlay (~32% opacity)
            graphics.fill(sx0, sy0, sx1, sy1, 0x50000000);
        }
    }

    private static final Map<com.warfront.mission.MissionType, ResourceLocation> MISSION_TYPE_TEXTURES = new java.util.EnumMap<>(com.warfront.mission.MissionType.class);

    private static ResourceLocation getMissionTexture(com.warfront.mission.MissionType type) {
        ensureBaseTexturesLoaded();
        if (type == null) return missionLogoLoc;
        return MISSION_TYPE_TEXTURES.computeIfAbsent(type, t ->
                loadTexture("mission_" + t.name().toLowerCase(java.util.Locale.ROOT)));
    }

    /**
     * Renders the mission logo icon centered inside each non-conquered subregion
     * of the currently activated selected region.
     * Only draws when the region has been activated (LAUNCH ATTACK clicked) and
     * missions have been generated into the state cache.
     */
    private void renderMissionIcons(GuiGraphics graphics, MapViewport viewport, RegionMapState state, RegionMapCamera camera) {
        ensureBaseTexturesLoaded(); // also initialises missionLogoLoc
        if (missionLogoLoc == null) return;

        SelectedRegion sel = state.getSelectedRegion();
        if (sel == null) return;

        long regKey = net.minecraft.world.level.ChunkPos.asLong(sel.regionX(), sel.regionZ());
        boolean isActivated = state.getActivatedRegions().contains(regKey) || sel.underSiege();
        if (!isActivated) return;

        com.warfront.mission.SubRegionMission[] missions = state.getCachedMissions(sel.regionX(), sel.regionZ());
        if (missions == null) return;

        for (int i = 0; i < 4; i++) {
            int subX = i % 2;
            int subZ = i / 2;
            int bit = subZ * 2 + subX;
            if ((sel.conqueredMask() & (1 << bit)) != 0) continue; // conquered — no icon

            // Center of this subregion in chunk coordinates
            double centerCX = sel.regionX() * 8 + subX * 4 + 2.0;
            double centerCZ = sel.regionZ() * 8 + subZ * 4 + 2.0;

            double iconChunks = 2.0; // icon occupies 2×2 chunk space, centered in the 4×4 subregion
            double startCX = centerCX - iconChunks / 2.0;
            double startCZ = centerCZ - iconChunks / 2.0;

            int drawX = (int) Math.round(viewport.mapLeft() + (startCX - camera.leftChunk(viewport)) * camera.getChunkTileSize());
            int drawY = (int) Math.round(viewport.mapTop() + (startCZ - camera.topChunk(viewport)) * camera.getChunkTileSize());
            int drawW = (int) Math.round(iconChunks * camera.getChunkTileSize());
            int drawH = (int) Math.round(iconChunks * camera.getChunkTileSize());

            // Cull icons that are entirely outside the map area
            if (drawX + drawW < viewport.mapLeft() || drawX > viewport.mapLeft() + viewport.mapSize()
                    || drawY + drawH < viewport.mapTop() || drawY > viewport.mapTop() + viewport.mapSize()) {
                continue;
            }

            com.warfront.mission.SubRegionMission mission = (i < missions.length) ? missions[i] : null;
            ResourceLocation iconLoc = (mission != null && mission.type() != null)
                    ? getMissionTexture(mission.type())
                    : missionLogoLoc;

            graphics.blit(iconLoc, drawX, drawY, drawW, drawH, 0.0F, 0.0F, 1, 1, 1, 1);
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
        } else if (selectedRegion.isAwaitingReinforcements()) {
            if (selectedRegion.isEncircled()) {
                renderInfoBox(graphics, font, left, top + (boxHeight + boxGap) * 4, width, boxHeight,
                        Component.literal("§c§lENCIRCLED"),
                        Component.literal("§7Reinforcements Blocked"));
            } else {
                long totalSecs = selectedRegion.reinforcementRemainingTicks() / 20L;
                long mins = totalSecs / 60L;
                long secs = totalSecs % 60L;
                renderInfoBox(graphics, font, left, top + (boxHeight + boxGap) * 4, width, boxHeight,
                        Component.literal("§6§lREINFORCING"),
                        Component.literal(String.format("§fArrival: §e%02d:%02d", mins, secs)));
            }
        } else if (selectedRegion.owner() != Faction.HUMANITY && selectedRegion.owner() != Faction.UNCLAIMED) {
            String reqText = (selectedRegion.baseType() != BaseType.NONE)
                    ? String.format("§fReq: §e%d Sec §c(Base Req)", selectedRegion.dominoThreshold())
                    : String.format("§fReq: §e%d Sectors", selectedRegion.dominoThreshold());
            renderInfoBox(graphics, font, left, top + (boxHeight + boxGap) * 4, width, boxHeight,
                    Component.literal("§c§lATTACK TARGET"),
                    Component.literal(reqText));
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
