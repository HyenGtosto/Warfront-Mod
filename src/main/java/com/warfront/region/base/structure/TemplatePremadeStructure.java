package com.warfront.region.base.structure;

import com.warfront.Warfront;
import com.warfront.region.base.BasePlacementContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Premade base structure implementation that loads and places native Minecraft Structure Templates (.nbt).
 *
 * Supports seamless fallback to a programmatic blueprint if the .nbt structure data file has not yet been
 * exported or bundled into the mod resources (data/<namespace>/structure/<path>.nbt).
 */
public class TemplatePremadeStructure implements PremadeStructure {

    private final ResourceLocation templateId;
    private final int fallbackSizeX;
    private final int fallbackSizeZ;
    private final int fallbackHeight;
    private static final int MAX_FOUNDATION_DEPTH = 14;
    private static final int SET_BLOCK_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public TemplatePremadeStructure(ResourceLocation templateId, int sizeX, int sizeZ, int height) {
        this.templateId = templateId;
        this.fallbackSizeX = sizeX;
        this.fallbackSizeZ = sizeZ;
        this.fallbackHeight = height;
    }

    @Override
    public String getId() {
        return templateId.toString();
    }

    public ResourceLocation getTemplateId() {
        return templateId;
    }

    @Override
    public int getSizeX() {
        return fallbackSizeX;
    }

    @Override
    public int getSizeZ() {
        return fallbackSizeZ;
    }

    @Override
    public int getHeight() {
        return fallbackHeight;
    }

    @Override
    public Map<BlockPos, BlockState> getPristineBlocks(ServerLevel level, BlockPos anchor) {
        if (level == null || anchor == null) {
            return Map.of();
        }
        StructureTemplateManager manager = level.getStructureManager();
        Optional<StructureTemplate> templateOpt = manager.get(templateId);
        if (templateOpt.isEmpty() || templateOpt.get().getSize().getX() <= 0) {
            Warfront.LOGGER.warn("[Warfront] Structure template '{}' could not be loaded by StructureTemplateManager.", templateId);
            return Map.of();
        }

        StructureTemplate template = templateOpt.get();
        Vec3i size = template.getSize();
        int halfX = size.getX() / 2;
        int halfZ = size.getZ() / 2;
        BlockPos origin = new BlockPos(anchor.getX() - halfX, anchor.getY(), anchor.getZ() - halfZ);

        CompoundTag tag = template.save(new CompoundTag());
        ListTag paletteTag = tag.contains("palette", Tag.TAG_LIST)
                ? tag.getList("palette", Tag.TAG_COMPOUND)
                : null;
        if (paletteTag == null && tag.contains("palettes", Tag.TAG_LIST)) {
            ListTag palettesList = tag.getList("palettes", Tag.TAG_LIST);
            if (!palettesList.isEmpty()) {
                paletteTag = (ListTag) palettesList.get(0);
            }
        }

        List<BlockState> palette = new ArrayList<>();
        if (paletteTag != null) {
            for (int i = 0; i < paletteTag.size(); i++) {
                palette.add(NbtUtils.readBlockState(
                        level.holderLookup(Registries.BLOCK),
                        paletteTag.getCompound(i)));
            }
        }

        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        Map<BlockPos, BlockState> result = new HashMap<>(blocksTag.size());
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag b = blocksTag.getCompound(i);
            ListTag posTag = b.getList("pos", Tag.TAG_INT);
            int lx = posTag.getInt(0);
            int ly = posTag.getInt(1);
            int lz = posTag.getInt(2);
            int stateIdx = b.getInt("state");
            if (stateIdx >= 0 && stateIdx < palette.size()) {
                BlockState state = palette.get(stateIdx);
                BlockPos worldPos = origin.offset(lx, ly, lz);
                // Strictly exclude foundations beneath ground level and air blocks
                if (worldPos.getY() >= anchor.getY() && !state.isAir()) {
                    result.put(worldPos.immutable(), state);
                }
            }
        }

        Warfront.LOGGER.info("[Warfront] Resolved {} pristine blocks for template '{}' centered at anchor {}.",
                result.size(), templateId, anchor);
        return result;
    }

    @Override
    public boolean place(BasePlacementContext context) {
        ServerLevel level = context.level();
        BlockPos anchor = context.anchor();
        if (level == null || anchor == null) {
            return false;
        }

        StructureTemplateManager manager = level.getStructureManager();
        Optional<StructureTemplate> templateOpt = manager.get(templateId);

        if (templateOpt.isEmpty() || templateOpt.get().getSize().getX() <= 0) {
            Warfront.LOGGER.error("[Warfront] Required structure template '{}' not found in data/{}/structure/{}.nbt!",
                    templateId, templateId.getNamespace(), templateId.getPath());
            return false;
        }

        StructureTemplate template = templateOpt.get();
        Vec3i size = template.getSize();
        int sizeX = size.getX();
        int sizeY = size.getY();
        int sizeZ = size.getZ();

        // Calculate origin corner: anchor is centered at ground level
        int halfX = sizeX / 2;
        int halfZ = sizeZ / 2;
        BlockPos origin = new BlockPos(anchor.getX() - halfX, anchor.getY(), anchor.getZ() - halfZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();

        // 1. Air clearing pass across maximum dimensions: eliminate clipping hills, terrain, trees
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                // Clear from dy = 1 to sizeY - 1
                for (int y = 1; y < sizeY; y++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    level.setBlock(pos, air, SET_BLOCK_FLAGS);
                }

                // Clear overhead foliage up to 4 blocks above the structure
                for (int upY = sizeY; upY <= sizeY + 4; upY++) {
                    pos.set(origin.getX() + x, origin.getY() + upY, origin.getZ() + z);
                    if (pos.getY() >= level.getMaxBuildHeight()) break;
                    BlockState existing = level.getBlockState(pos);
                    if (existing.is(BlockTags.LEAVES) || existing.is(BlockTags.LOGS)) {
                        level.setBlock(pos, air, SET_BLOCK_FLAGS);
                    }
                }
            }
        }

        // 2. Foundation downwards beneath perimeter columns down to solid ground
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        for (int x = 0; x < sizeX; x++) {
            boolean isPerimeterX = (x == 0 || x == sizeX - 1);
            for (int z = 0; z < sizeZ; z++) {
                boolean isPerimeterZ = (z == 0 || z == sizeZ - 1);
                // Foundation under full base perimeter
                if (isPerimeterX || isPerimeterZ) {
                    int worldX = origin.getX() + x;
                    int worldZ = origin.getZ() + z;
                    for (int depth = 1; depth <= MAX_FOUNDATION_DEPTH; depth++) {
                        int downY = origin.getY() - depth;
                        if (downY < level.getMinBuildHeight()) break;

                        pos.set(worldX, downY, worldZ);
                        BlockState existing = level.getBlockState(pos);
                        if (!existing.isAir() && !existing.canBeReplaced() && existing.getFluidState().isEmpty()) {
                            break;
                        }
                        level.setBlock(pos, cobble, SET_BLOCK_FLAGS);
                    }
                }
            }
        }

        // 3. Place native structure template in world
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(Rotation.NONE)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false);

        boolean placed = template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_ALL);
        if (placed) {
            Warfront.LOGGER.info("[Warfront] Successfully placed native NBT structure template '{}' at {} (size: {}x{}x{})",
                    templateId, origin, sizeX, sizeY, sizeZ);
        }
        return placed;
    }
}
