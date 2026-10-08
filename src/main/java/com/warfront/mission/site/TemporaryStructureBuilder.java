package com.warfront.mission.site;

import com.warfront.block.WarfrontBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural lightweight temporary structure generator.
 *
 * Constructs compact, visually authentic field installations (Forward Outposts,
 * Scout Lookout Nests, Supply Props) with stepped downward foundations to ensure
 * zero floating structures on uneven terrain.
 *
 * All block modifications are automatically recorded in {@link MissionSiteSnapshot}
 * for clean, seamless restoration on mission completion or cancellation.
 */
public final class TemporaryStructureBuilder {

    private TemporaryStructureBuilder() {
    }

    /**
     * Builds a compact Forward Outpost palisade watchtower around the anchor position.
     * Footprint: ~8x8 to 10x10. Height: ~6 blocks.
     *
     * @param level    ServerLevel
     * @param anchor   Ground center anchor position
     * @param snapshot Active site snapshot for block state tracking
     * @return List of {@link BlockPos} for all non-air blocks placed in the outpost structure
     */
    public static List<BlockPos> buildForwardOutpost(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placedBlocks = new ArrayList<>();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
        BlockState planks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState fence = Blocks.DARK_OAK_FENCE.defaultBlockState();
        BlockState targetBlock = WarfrontBlocks.MISSION_TARGET_CORE.get().defaultBlockState(); // Central Command Core

        int baseY = anchor.getY();
        int minX = anchor.getX() - 3;
        int maxX = anchor.getX() + 3;
        int minZ = anchor.getZ() - 3;
        int maxZ = anchor.getZ() + 3;

        // 1. Foundation: Clear interior air and build perimeter foundations down to solid ground
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean isEdge = (x == minX || x == maxX || z == minZ || z == maxZ);
                boolean isCorner = (x == minX || x == maxX) && (z == minZ || z == maxZ);

                // Build down to solid terrain
                BlockPos colPos = new BlockPos(x, baseY - 1, z);
                int depth = 0;
                while (depth < 8 && (level.getBlockState(colPos).isAir() || level.getBlockState(colPos).liquid())) {
                    snapshot.setBlock(level, colPos, isCorner ? log : cobble, 3);
                    placedBlocks.add(colPos.immutable());
                    colPos = colPos.below();
                    depth++;
                }

                // Clear interior headroom up to Y+8
                for (int yOff = 0; yOff <= 7; yOff++) {
                    BlockPos airPos = new BlockPos(x, baseY + yOff, z);
                    if (!level.getBlockState(airPos).isAir()) {
                        snapshot.setBlock(level, airPos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }

                // Ground floor paving
                BlockPos floorPos = new BlockPos(x, baseY, z);
                snapshot.setBlock(level, floorPos, isEdge ? cobble : planks, 3);
                placedBlocks.add(floorPos.immutable());
            }
        }

        // 2. Corner tower pillar logs and raised platform at Y+4
        int platformY = baseY + 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean isCorner = (x == minX || x == maxX) && (z == minZ || z == maxZ);
                boolean isEdge = (x == minX || x == maxX) || (z == minZ || z == maxZ);

                if (isCorner) {
                    for (int y = baseY + 1; y <= platformY + 2; y++) {
                        BlockPos pillarPos = new BlockPos(x, y, z);
                        snapshot.setBlock(level, pillarPos, log, 3);
                        placedBlocks.add(pillarPos.immutable());
                    }
                } else if (isEdge) {
                    // Lower palisade wall
                    BlockPos palisadePos = new BlockPos(x, baseY + 1, z);
                    snapshot.setBlock(level, palisadePos, fence, 3);
                    placedBlocks.add(palisadePos.immutable());
                }

                // Elevated watch platform
                BlockPos platPos = new BlockPos(x, platformY, z);
                snapshot.setBlock(level, platPos, planks, 3);
                placedBlocks.add(platPos.immutable());

                // Upper guard railing
                if (isEdge && !isCorner) {
                    BlockPos railPos = new BlockPos(x, platformY + 1, z);
                    snapshot.setBlock(level, railPos, fence, 3);
                    placedBlocks.add(railPos.immutable());
                }
            }
        }

        // 3. Central access ladder along the north-central pillar
        BlockPos ladderPillar = new BlockPos(anchor.getX(), baseY + 1, anchor.getZ() - 2);
        for (int y = baseY + 1; y <= platformY; y++) {
            BlockPos lpPos = new BlockPos(ladderPillar.getX(), y, ladderPillar.getZ());
            snapshot.setBlock(level, lpPos, log, 3);
            placedBlocks.add(lpPos.immutable());

            BlockPos ladderPos = new BlockPos(ladderPillar.getX(), y, ladderPillar.getZ() + 1);
            BlockState ladderState = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
            snapshot.setBlock(level, ladderPos, ladderState, 3);
            placedBlocks.add(ladderPos.immutable());
        }

        // 4. Central Command Core objective block on top of elevated watch platform
        BlockPos corePos = new BlockPos(anchor.getX(), platformY + 1, anchor.getZ());
        snapshot.setBlock(level, corePos, targetBlock, 3);
        placedBlocks.add(corePos.immutable());

        // Decorative torch on top of core
        BlockPos torchPos = corePos.above();
        snapshot.setBlock(level, torchPos, Blocks.TORCH.defaultBlockState(), 3);
        placedBlocks.add(torchPos.immutable());

        return placedBlocks;
    }

    /**
     * Builds a compact 3x3 Scout Observation Nest on dry ground.
     *
     * @param level    ServerLevel
     * @param anchor   Ground position
     * @param snapshot Active site snapshot
     * @return {@link BlockPos} of the Observation Relay objective block
     */
    public static BlockPos buildScoutLookoutNest(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
        BlockState planks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState fence = Blocks.DARK_OAK_FENCE.defaultBlockState();
        BlockState relay = WarfrontBlocks.MISSION_TARGET_CORE.get().defaultBlockState(); // Observation Relay

        int baseY = anchor.getY();
        int elevatedY = baseY + 3;

        // 4 Support stilts at the 4 corners
        int[] dx = {-1, 1};
        int[] dz = {-1, 1};

        for (int xOff : dx) {
            for (int zOff : dz) {
                int px = anchor.getX() + xOff;
                int pz = anchor.getZ() + zOff;

                // Build stilt down to ground
                BlockPos colPos = new BlockPos(px, baseY - 1, pz);
                int depth = 0;
                while (depth < 6 && (level.getBlockState(colPos).isAir() || level.getBlockState(colPos).liquid())) {
                    snapshot.setBlock(level, colPos, fence, 3);
                    colPos = colPos.below();
                    depth++;
                }

                // Stilt uprights
                for (int y = baseY; y <= elevatedY; y++) {
                    snapshot.setBlock(level, new BlockPos(px, y, pz), fence, 3);
                }
            }
        }

        // Clear air space at platform level
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int oy = 1; oy <= 4; oy++) {
                    BlockPos p = new BlockPos(anchor.getX() + ox, elevatedY + oy, anchor.getZ() + oz);
                    if (!level.getBlockState(p).isAir()) {
                        snapshot.setBlock(level, p, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                // Platform floor
                snapshot.setBlock(level, new BlockPos(anchor.getX() + ox, elevatedY, anchor.getZ() + oz), planks, 3);
            }
        }

        // Center observation relay block
        BlockPos relayPos = new BlockPos(anchor.getX(), elevatedY + 1, anchor.getZ());
        snapshot.setBlock(level, relayPos, relay, 3);

        // Torch on top
        snapshot.setBlock(level, relayPos.above(), Blocks.TORCH.defaultBlockState(), 3);

        return relayPos;
    }

    /**
     * Places a compact temporary Supply Crate prop (Barrel) with cobblestone/plank base.
     */
    public static BlockPos placeSupplyCrate(ServerLevel level, BlockPos pos, MissionSiteSnapshot snapshot) {
        BlockState barrel = Blocks.BARREL.defaultBlockState();
        snapshot.setBlock(level, pos, barrel, 3);
        return pos;
    }
}
