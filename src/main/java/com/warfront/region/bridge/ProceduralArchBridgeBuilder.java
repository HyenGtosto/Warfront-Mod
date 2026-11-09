package com.warfront.region.bridge;

import com.warfront.Warfront;
import com.warfront.region.RegionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Procedurally generates an adaptive stone-and-wood arch bridge across a river
 * in a Pillager subregion.
 *
 * Visual & Structural Invariants:
 * - 3-Block Walkway: Dark Oak Planks with directional stairs on elevation transitions.
 * - Parapets & Guardrails: Dark Oak Fences with Cobblestone/Stone Brick Wall posts and lanterns every 4 blocks.
 * - Clean Entrance Architecture: Bridge terminates squarely on solid bank footings without choking posts;
 *   carves a 3x3x2 seamless air clearance corridor in front of both exits.
 * - Stone Supports: Central stone pier embedded into the riverbed with arch corbels;
 *   stone ribs beneath the deck; stepped foundations at bank abutments down to solid ground (zero floating blocks).
 * - Valley / Uneven Elevation Handling: Parabolic arch superimposed on a linear gradient between Bank A and Bank B.
 */
public final class ProceduralArchBridgeBuilder {

    private ProceduralArchBridgeBuilder() {
    }

    /**
     * Builds the procedural arch bridge across the river channel between bankA and bankB.
     */
    public static SubregionBridge buildBridge(
            ServerLevel level,
            int rx,
            int rz,
            int sx,
            int sz,
            Direction.Axis axis,
            BlockPos bankA,
            BlockPos bankB,
            int waterY,
            int bedY) {

        long subKey = RegionData.subRegionKey(rx, rz, sx, sz);

        if (axis == Direction.Axis.X) {
            return buildAlongX(level, subKey, rx, rz, sx, sz, bankA, bankB, waterY, bedY);
        } else {
            return buildAlongZ(level, subKey, rx, rz, sx, sz, bankA, bankB, waterY, bedY);
        }
    }

    private static SubregionBridge buildAlongX(
            ServerLevel level,
            long subKey,
            int rx,
            int rz,
            int sx,
            int sz,
            BlockPos bankA,
            BlockPos bankB,
            int waterY,
            int bedY) {

        // Guarantee start is West (smaller X), end is East (larger X)
        BlockPos start = (bankA.getX() <= bankB.getX()) ? bankA : bankB;
        BlockPos end = (bankA.getX() <= bankB.getX()) ? bankB : bankA;

        int minX = start.getX();
        int maxX = end.getX();
        int startY = start.getY();
        int endY = end.getY();
        int centerZ = start.getZ();

        int span = maxX - minX;
        int midX = (minX + maxX) / 2;
        int hRise = (span >= 16) ? 3 : 2;

        // Calculate crown anchor elevation at midpoint
        int midLinY = (startY + endY) / 2;
        int anchorY = Math.max(midLinY, waterY + 2) + hRise;
        BlockPos centerAnchor = new BlockPos(midX, anchorY, centerZ);

        // Precompute deck Y profile across the span
        int totalLen = span + 1;
        int[] deckY = new int[totalLen];

        for (int x = minX; x <= maxX; x++) {
            int idx = x - minX;
            double t = (span > 0) ? ((double) idx / (double) span) : 0.0D;
            double yLin = startY + t * (endY - startY);
            double hArch = 4.0D * hRise * t * (1.0D - t);
            int yCalc = (int) Math.round(yLin + hArch);
            deckY[idx] = Math.max(yCalc, waterY + 2);
        }

        // Clamp adjacent elevation steps to maximum 1 block to guarantee smooth mob walking
        for (int i = 1; i < totalLen; i++) {
            if (deckY[i] > deckY[i - 1] + 1) {
                deckY[i] = deckY[i - 1] + 1;
            }
        }
        for (int i = totalLen - 2; i >= 0; i--) {
            if (deckY[i] > deckY[i + 1] + 1) {
                deckY[i] = deckY[i + 1] + 1;
            }
        }

        // 1. Construct Roadway & Parapets
        for (int x = minX; x <= maxX; x++) {
            int idx = x - minX;
            int y = deckY[idx];
            int prevY = (idx > 0) ? deckY[idx - 1] : y;
            int nextY = (idx < totalLen - 1) ? deckY[idx + 1] : y;

            // Lateral offsets w from -2 to +2 (total width 5 blocks)
            for (int w = -2; w <= 2; w++) {
                int z = centerZ + w;

                if (Math.abs(w) <= 1) {
                    // --- 3-Block Wide Walkway Floor ---
                    BlockState floorState;
                    if (y > prevY) {
                        // Ascending from West -> East (facing East)
                        floorState = Blocks.DARK_OAK_STAIRS.defaultBlockState()
                                .setValue(StairBlock.FACING, Direction.EAST);
                    } else if (y > nextY) {
                        // Ascending from East -> West (facing West)
                        floorState = Blocks.DARK_OAK_STAIRS.defaultBlockState()
                                .setValue(StairBlock.FACING, Direction.WEST);
                    } else {
                        floorState = Blocks.DARK_OAK_PLANKS.defaultBlockState();
                    }

                    level.setBlock(new BlockPos(x, y, z), floorState, 3);

                    // Clear 3 blocks of headroom for squads
                    for (int h = 1; h <= 3; h++) {
                        BlockPos airPos = new BlockPos(x, y + h, z);
                        if (!level.getBlockState(airPos).isAir()) {
                            level.setBlock(airPos, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }

                    // Stone arch ribs underneath walkway outer edges (w = -1, +1)
                    if (Math.abs(w) == 1 && y - 1 > waterY) {
                        level.setBlock(new BlockPos(x, y - 1, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                    }
                } else {
                    // --- Parapets & Guardrails (w = -2, +2) ---
                    level.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);

                    // Posts with lanterns placed only along the interior span, keeping entrances open and unclogged
                    boolean isInteriorPost = (x > minX && x < maxX && Math.abs(x - midX) % 4 == 0);
                    if (isInteriorPost) {
                        level.setBlock(new BlockPos(x, y + 1, z), Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, y + 2, z), Blocks.LANTERN.defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, y + 3, z), Blocks.AIR.defaultBlockState(), 3);
                    } else {
                        level.setBlock(new BlockPos(x, y + 1, z), Blocks.DARK_OAK_FENCE.defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, y + 2, z), Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, y + 3, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }

        // 2. Central Stone Pier (embedded into riverbed)
        int pierMinX = midX - 1;
        int pierMaxX = midX + 1;
        for (int px = pierMinX; px <= pierMaxX; px++) {
            int pyDeck = deckY[px - minX];
            for (int w = -1; w <= 1; w++) {
                int pz = centerZ + w;
                // Build stone column from under the deck down into solid riverbed
                int curY = pyDeck - 1;
                while (curY >= bedY - 2 && curY > level.getMinBuildHeight()) {
                    BlockState pierStone = ((px + curY + w) % 3 == 0)
                            ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                            : ((px + curY) % 2 == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
                    level.setBlock(new BlockPos(px, curY, pz), pierStone, 3);
                    curY--;
                }
            }
            // Inverted arch corbel stairs at pier edges
            if (px == pierMinX) {
                for (int w = -1; w <= 1; w++) {
                    level.setBlock(new BlockPos(px, pyDeck - 1, centerZ + w),
                            Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                                    .setValue(StairBlock.HALF, Half.TOP)
                                    .setValue(StairBlock.FACING, Direction.WEST), 3);
                }
            } else if (px == pierMaxX) {
                for (int w = -1; w <= 1; w++) {
                    level.setBlock(new BlockPos(px, pyDeck - 1, centerZ + w),
                            Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                                    .setValue(StairBlock.HALF, Half.TOP)
                                    .setValue(StairBlock.FACING, Direction.EAST), 3);
                }
            }
        }

        // 3. Bank Abutments & Stepped Foundations (Zero Floating Blocks)
        buildSteppedAbutment(level, minX, Math.min(minX + 2, midX - 2), centerZ, minX, deckY);
        buildSteppedAbutment(level, Math.max(maxX - 2, midX + 2), maxX, centerZ, minX, deckY);

        // 4. Natural Seamless Exit Clearance (3 blocks wide x 3 blocks tall x 2 blocks deep)
        clearExitCorridorX(level, minX - 1, minX - 2, centerZ, deckY[0]);
        clearExitCorridorX(level, maxX + 1, maxX + 2, centerZ, deckY[totalLen - 1]);

        BlockPos approachA = new BlockPos(minX, deckY[0], centerZ);
        BlockPos approachB = new BlockPos(maxX, deckY[totalLen - 1], centerZ);

        Warfront.LOGGER.info("[BRIDGE] Procedurally built arch bridge at subregion ({}, {}) [{}, {}] spanning {} blocks along X. Bank A: {}, Crown: {}, Bank B: {}",
                rx, rz, sx, sz, span, approachA, centerAnchor, approachB);

        return new SubregionBridge(subKey, rx, rz, sx, sz, Direction.Axis.X, approachA, centerAnchor, approachB, span, 5);
    }

    private static SubregionBridge buildAlongZ(
            ServerLevel level,
            long subKey,
            int rx,
            int rz,
            int sx,
            int sz,
            BlockPos bankA,
            BlockPos bankB,
            int waterY,
            int bedY) {

        // Guarantee start is North (smaller Z), end is South (larger Z)
        BlockPos start = (bankA.getZ() <= bankB.getZ()) ? bankA : bankB;
        BlockPos end = (bankA.getZ() <= bankB.getZ()) ? bankB : bankA;

        int minZ = start.getZ();
        int maxZ = end.getZ();
        int startY = start.getY();
        int endY = end.getY();
        int centerX = start.getX();

        int span = maxZ - minZ;
        int midZ = (minZ + maxZ) / 2;
        int hRise = (span >= 16) ? 3 : 2;

        int midLinY = (startY + endY) / 2;
        int anchorY = Math.max(midLinY, waterY + 2) + hRise;
        BlockPos centerAnchor = new BlockPos(centerX, anchorY, midZ);

        int totalLen = span + 1;
        int[] deckY = new int[totalLen];

        for (int z = minZ; z <= maxZ; z++) {
            int idx = z - minZ;
            double t = (span > 0) ? ((double) idx / (double) span) : 0.0D;
            double yLin = startY + t * (endY - startY);
            double hArch = 4.0D * hRise * t * (1.0D - t);
            int yCalc = (int) Math.round(yLin + hArch);
            deckY[idx] = Math.max(yCalc, waterY + 2);
        }

        // Clamp adjacent elevation steps
        for (int i = 1; i < totalLen; i++) {
            if (deckY[i] > deckY[i - 1] + 1) {
                deckY[i] = deckY[i - 1] + 1;
            }
        }
        for (int i = totalLen - 2; i >= 0; i--) {
            if (deckY[i] > deckY[i + 1] + 1) {
                deckY[i] = deckY[i + 1] + 1;
            }
        }

        // 1. Construct Roadway & Parapets
        for (int z = minZ; z <= maxZ; z++) {
            int idx = z - minZ;
            int y = deckY[idx];
            int prevY = (idx > 0) ? deckY[idx - 1] : y;
            int nextY = (idx < totalLen - 1) ? deckY[idx + 1] : y;

            for (int w = -2; w <= 2; w++) {
                int x = centerX + w;

                if (Math.abs(w) <= 1) {
                    BlockState floorState;
                    if (y > prevY) {
                        floorState = Blocks.DARK_OAK_STAIRS.defaultBlockState()
                                .setValue(StairBlock.FACING, Direction.SOUTH);
                    } else if (y > nextY) {
                        floorState = Blocks.DARK_OAK_STAIRS.defaultBlockState()
                                .setValue(StairBlock.FACING, Direction.NORTH);
                    } else {
                        floorState = Blocks.DARK_OAK_PLANKS.defaultBlockState();
                    }

                    level.setBlock(new BlockPos(x, y, z), floorState, 3);

                    for (int h = 1; h <= 3; h++) {
                        BlockPos airPos = new BlockPos(x, y + h, z);
                        if (!level.getBlockState(airPos).isAir()) {
                            level.setBlock(airPos, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }

                    if (Math.abs(w) == 1 && y - 1 > waterY) {
                        level.setBlock(new BlockPos(x, y - 1, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
                    }
                } else {
                    level.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);

                    boolean isInteriorPost = (z > minZ && z < maxZ && Math.abs(z - midZ) % 4 == 0);
                    if (isInteriorPost) {
                        level.setBlock(new BlockPos(x, y + 1, z), Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, y + 2, z), Blocks.LANTERN.defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, y + 3, z), Blocks.AIR.defaultBlockState(), 3);
                    } else {
                        level.setBlock(new BlockPos(x, y + 1, z), Blocks.DARK_OAK_FENCE.defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, y + 2, z), Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, y + 3, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }

        // 2. Central Stone Pier
        int pierMinZ = midZ - 1;
        int pierMaxZ = midZ + 1;
        for (int pz = pierMinZ; pz <= pierMaxZ; pz++) {
            int pyDeck = deckY[pz - minZ];
            for (int w = -1; w <= 1; w++) {
                int px = centerX + w;
                int curY = pyDeck - 1;
                while (curY >= bedY - 2 && curY > level.getMinBuildHeight()) {
                    BlockState pierStone = ((pz + curY + w) % 3 == 0)
                            ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                            : ((pz + curY) % 2 == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
                    level.setBlock(new BlockPos(px, curY, pz), pierStone, 3);
                    curY--;
                }
            }
            if (pz == pierMinZ) {
                for (int w = -1; w <= 1; w++) {
                    level.setBlock(new BlockPos(centerX + w, pyDeck - 1, pz),
                            Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                                    .setValue(StairBlock.HALF, Half.TOP)
                                    .setValue(StairBlock.FACING, Direction.NORTH), 3);
                }
            } else if (pz == pierMaxZ) {
                for (int w = -1; w <= 1; w++) {
                    level.setBlock(new BlockPos(centerX + w, pyDeck - 1, pz),
                            Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                                    .setValue(StairBlock.HALF, Half.TOP)
                                    .setValue(StairBlock.FACING, Direction.SOUTH), 3);
                }
            }
        }

        // 3. Bank Abutments & Stepped Foundations
        buildSteppedAbutmentZ(level, minZ, Math.min(minZ + 2, midZ - 2), centerX, minZ, deckY);
        buildSteppedAbutmentZ(level, Math.max(maxZ - 2, midZ + 2), maxZ, centerX, minZ, deckY);

        // 4. Natural Seamless Exit Clearance
        clearExitCorridorZ(level, minZ - 1, minZ - 2, centerX, deckY[0]);
        clearExitCorridorZ(level, maxZ + 1, maxZ + 2, centerX, deckY[totalLen - 1]);

        BlockPos approachA = new BlockPos(centerX, deckY[0], minZ);
        BlockPos approachB = new BlockPos(centerX, deckY[totalLen - 1], maxZ);

        Warfront.LOGGER.info("[BRIDGE] Procedurally built arch bridge at subregion ({}, {}) [{}, {}] spanning {} blocks along Z. Bank A: {}, Crown: {}, Bank B: {}",
                rx, rz, sx, sz, span, approachA, centerAnchor, approachB);

        return new SubregionBridge(subKey, rx, rz, sx, sz, Direction.Axis.Z, approachA, centerAnchor, approachB, span, 5);
    }

    private static void clearExitCorridorX(ServerLevel level, int fromX, int toX, int centerZ, int floorY) {
        int xStart = Math.min(fromX, toX);
        int xEnd = Math.max(fromX, toX);
        for (int ex = xStart; ex <= xEnd; ex++) {
            for (int w = -1; w <= 1; w++) {
                int ez = centerZ + w;
                for (int h = 1; h <= 3; h++) {
                    BlockPos airPos = new BlockPos(ex, floorY + h, ez);
                    if (!level.getBlockState(airPos).isAir()) {
                        level.setBlock(airPos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static void clearExitCorridorZ(ServerLevel level, int fromZ, int toZ, int centerX, int floorY) {
        int zStart = Math.min(fromZ, toZ);
        int zEnd = Math.max(fromZ, toZ);
        for (int ez = zStart; ez <= zEnd; ez++) {
            for (int w = -1; w <= 1; w++) {
                int ex = centerX + w;
                for (int h = 1; h <= 3; h++) {
                    BlockPos airPos = new BlockPos(ex, floorY + h, ez);
                    if (!level.getBlockState(airPos).isAir()) {
                        level.setBlock(airPos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static void buildSteppedAbutment(ServerLevel level, int fromX, int toX, int centerZ, int minX, int[] deckY) {
        for (int x = fromX; x <= toX; x++) {
            int idx = Math.clamp(x - minX, 0, deckY.length - 1);
            int targetFloorY = deckY[idx];
            for (int w = -2; w <= 2; w++) {
                int z = centerZ + w;
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int downY = targetFloorY - 1; downY >= surfaceY - 1 && downY >= targetFloorY - 6; downY--) {
                    BlockPos pos = new BlockPos(x, downY, z);
                    // Abutment foundation safety guard: never generate into river water
                    if (!level.getFluidState(pos).isEmpty()) {
                        break;
                    }
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
                    } else if (level.getBlockState(pos).blocksMotion()) {
                        break; // Solid ground reached
                    }
                }
            }
        }
    }

    private static void buildSteppedAbutmentZ(ServerLevel level, int fromZ, int toZ, int centerX, int minZ, int[] deckY) {
        for (int z = fromZ; z <= toZ; z++) {
            int idx = Math.clamp(z - minZ, 0, deckY.length - 1);
            int targetFloorY = deckY[idx];
            for (int w = -2; w <= 2; w++) {
                int x = centerX + w;
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int downY = targetFloorY - 1; downY >= surfaceY - 1 && downY >= targetFloorY - 6; downY--) {
                    BlockPos pos = new BlockPos(x, downY, z);
                    // Abutment foundation safety guard: never generate into river water
                    if (!level.getFluidState(pos).isEmpty()) {
                        break;
                    }
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
                    } else if (level.getBlockState(pos).blocksMotion()) {
                        break; // Solid ground reached
                    }
                }
            }
        }
    }
}
