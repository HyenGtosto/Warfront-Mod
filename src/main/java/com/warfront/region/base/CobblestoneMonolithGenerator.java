package com.warfront.region.base;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Concrete base building generator that erects a visible 25x25 footprint by 30 height
 * cobblestone monolith at the region base anchor.
 *
 * Uses high-performance shell generation (thick perimeter walls, solid floor & roof,
 * perimeter foundation down to ground) to ensure instant, freeze-free placement in the world.
 */
public class CobblestoneMonolithGenerator implements BaseBuildingGenerator {

    public static final int SIZE_X = 25;
    public static final int SIZE_Z = 25;
    public static final int HEIGHT = 30;
    private static final int MAX_FOUNDATION_DEPTH = 16;
    private static final int SET_BLOCK_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @Override
    public int getSizeX() {
        return SIZE_X;
    }

    @Override
    public int getSizeZ() {
        return SIZE_Z;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public java.util.Map<BlockPos, BlockState> getPristineBlocks(ServerLevel level, BlockPos anchor) {
        if (level == null || anchor == null) {
            return java.util.Map.of();
        }
        int halfX = SIZE_X / 2;
        int halfZ = SIZE_Z / 2;
        int startX = anchor.getX() - halfX;
        int endX = anchor.getX() + halfX;
        int startZ = anchor.getZ() - halfZ;
        int endZ = anchor.getZ() + halfZ;
        int startY = anchor.getY();
        int endY = startY + HEIGHT - 1;

        java.util.Map<BlockPos, BlockState> map = new java.util.HashMap<>();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();

        for (int x = startX; x <= endX; x++) {
            boolean isWallX = (x <= startX + 1 || x >= endX - 1);
            for (int z = startZ; z <= endZ; z++) {
                boolean isWallZ = (z <= startZ + 1 || z >= endZ - 1);
                boolean isWall = isWallX || isWallZ;

                for (int y = startY; y <= endY; y++) {
                    boolean isRoof = (y >= endY - 1);
                    boolean isFloor = (y <= startY + 1);

                    if (isWall || isRoof || isFloor) {
                        map.put(new BlockPos(x, y, z), cobble);
                    }
                }
            }
        }
        return map;
    }

    @Override
    public boolean place(BasePlacementContext context) {
        ServerLevel level = context.level();
        BlockPos anchor = context.anchor();
        if (level == null || anchor == null) {
            return false;
        }

        int halfX = SIZE_X / 2; // 12
        int halfZ = SIZE_Z / 2; // 12
        int startX = anchor.getX() - halfX;
        int endX = anchor.getX() + halfX; // 12 - (-12) + 1 = 25 blocks
        int startZ = anchor.getZ() - halfZ;
        int endZ = anchor.getZ() + halfZ; // 25 blocks
        int startY = anchor.getY();
        int endY = startY + HEIGHT - 1; // 30 blocks tall

        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = startX; x <= endX; x++) {
            boolean isWallX = (x <= startX + 1 || x >= endX - 1);
            boolean isPerimeterX = (x == startX || x == endX);

            for (int z = startZ; z <= endZ; z++) {
                boolean isWallZ = (z <= startZ + 1 || z >= endZ - 1);
                boolean isPerimeterZ = (z == startZ || z == endZ);
                boolean isWall = isWallX || isWallZ;

                // 1. Structural column placement:
                // Full height walls around the perimeter, plus solid floor and solid roof
                for (int y = startY; y <= endY; y++) {
                    boolean isRoof = (y >= endY - 1);
                    boolean isFloor = (y <= startY + 1);

                    if (isWall || isRoof || isFloor) {
                        pos.set(x, y, z);
                        level.setBlock(pos, cobble, SET_BLOCK_FLAGS);
                    } else {
                        pos.set(x, y, z);
                        level.setBlock(pos, air, SET_BLOCK_FLAGS);
                    }
                }

                // 2. Foundation downwards for perimeter walls to ground uneven terrain
                if (isPerimeterX || isPerimeterZ) {
                    for (int depth = 1; depth <= MAX_FOUNDATION_DEPTH; depth++) {
                        int downY = startY - depth;
                        if (downY < level.getMinBuildHeight()) {
                            break;
                        }
                        pos.set(x, downY, z);
                        BlockState existing = level.getBlockState(pos);
                        if (!existing.isAir() && existing.getFluidState().isEmpty()) {
                            break; // Anchored into solid ground
                        }
                        level.setBlock(pos, cobble, SET_BLOCK_FLAGS);
                    }
                }

                // 3. Clear overhead tree foliage immediately above the monolith roof
                for (int upY = endY + 1; upY <= endY + 2; upY++) {
                    if (upY >= level.getMaxBuildHeight()) {
                        break;
                    }
                    pos.set(x, upY, z);
                    BlockState above = level.getBlockState(pos);
                    if (above.is(BlockTags.LEAVES) || above.is(BlockTags.LOGS)) {
                        level.setBlock(pos, air, SET_BLOCK_FLAGS);
                    }
                }
            }
        }

        return true;
    }
}
