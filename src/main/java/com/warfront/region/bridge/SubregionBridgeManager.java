package com.warfront.region.bridge;

import com.warfront.Warfront;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.generator.ProceduralRegionGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MangroveRootsBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * Orchestrates procedural bridge detection and construction across river subregions.
 *
 * Structural & Placement Invariants:
 * - Operates on ServerTickEvent.Post throttled to 20 ticks (1.0s).
 * - Scans subregions within 250 blocks of players.
 * - Bridges generate strictly in Pillager Conqueror subregions.
 * - Multi-candidate fitness scoring: scans multiple transects (step 4) across the subregion,
 *   selecting the narrowest, flattest, and most natural crossing.
 * - Genuine River Channel Validation: enforces center water depth >= 2 and orthogonal channel flow continuity
 *   at +/-6 and +/-10 blocks, eliminating bank puddles and dead-end trenches.
 * - Adaptive Rugged Shoreline & Hill Profiling: adapts to ragged shorelines by nudging inland until all 5 width positions
 *   are dry land; detects flat terrain aprons vs sudden hills to terminate before hills or adapt smoothly to immediate rises.
 * - Anti-Floating Island Gating: bank heights capped at waterY + 12 with continuous solid ground
 *   pillars down to water level (zero air gaps permitted beneath banks).
 * - Moat Bridging: permits bridges across base perimeter moats while strictly rejecting any
 *   overlap with the physical fortress structure footprint.
 * - Saves results to RegionData NBT to guarantee O(1) evaluation and zero re-computation.
 */
public final class SubregionBridgeManager {

    private static final int EVAL_INTERVAL_TICKS = 20; // 1 second cadence
    private static final double MAX_PLAYER_DISTANCE_BLOCKS = 250.0D;
    private static final int SCAN_RADIUS_REGIONS = 2; // +/-2 regions around player

    private SubregionBridgeManager() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;

        long gameTime = level.getGameTime();
        if (gameTime % EVAL_INTERVAL_TICKS != 0) return;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        RegionData regions = RegionData.get(level);

        for (ServerPlayer player : players) {
            int px = player.getBlockX();
            int pz = player.getBlockZ();
            int playerRX = Math.floorDiv(px, RegionData.REGION_SIZE_BLOCKS);
            int playerRZ = Math.floorDiv(pz, RegionData.REGION_SIZE_BLOCKS);

            boolean builtAny = false;

            for (int drx = -SCAN_RADIUS_REGIONS; drx <= SCAN_RADIUS_REGIONS && !builtAny; drx++) {
                for (int drz = -SCAN_RADIUS_REGIONS; drz <= SCAN_RADIUS_REGIONS && !builtAny; drz++) {
                    int rx = playerRX + drx;
                    int rz = playerRZ + drz;

                    for (int sx = 0; sx <= 1 && !builtAny; sx++) {
                        for (int sz = 0; sz <= 1 && !builtAny; sz++) {
                            int centerX = rx * RegionData.REGION_SIZE_BLOCKS + sx * 64 + 32;
                            int centerZ = rz * RegionData.REGION_SIZE_BLOCKS + sz * 64 + 32;

                            double dx = px - centerX;
                            double dz = pz - centerZ;
                            if (dx * dx + dz * dz > MAX_PLAYER_DISTANCE_BLOCKS * MAX_PLAYER_DISTANCE_BLOCKS) {
                                continue;
                            }

                            long subKey = RegionData.subRegionKey(rx, rz, sx, sz);
                            if (regions.hasEvaluatedBridge(subKey)) {
                                continue;
                            }

                            if (tryEvaluateAndBuildBridge(level, regions, rx, rz, sx, sz)) {
                                builtAny = true; // Rate limit to max 1 bridge built per tick per player
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Retrieves the bridge for the specified subregion, or null if none exists.
     */
    public static SubregionBridge getBridge(net.minecraft.world.level.Level level, int rx, int rz, int sx, int sz) {
        if (level instanceof ServerLevel sl) {
            return RegionData.get(sl).getSubregionBridge(RegionData.subRegionKey(rx, rz, sx, sz));
        }
        return null;
    }

    /**
     * Retrieves the bridge at the given world block position, or null if none exists.
     */
    public static SubregionBridge getBridgeAt(net.minecraft.world.level.Level level, BlockPos pos) {
        int rx = Math.floorDiv(pos.getX(), RegionData.REGION_SIZE_BLOCKS);
        int rz = Math.floorDiv(pos.getZ(), RegionData.REGION_SIZE_BLOCKS);
        int localX = Math.floorMod(pos.getX(), RegionData.REGION_SIZE_BLOCKS);
        int localZ = Math.floorMod(pos.getZ(), RegionData.REGION_SIZE_BLOCKS);
        int sx = localX / 64;
        int sz = localZ / 64;
        return getBridge(level, rx, rz, sx, sz);
    }

    private static boolean tryEvaluateAndBuildBridge(
            ServerLevel level,
            RegionData regions,
            int rx,
            int rz,
            int sx,
            int sz) {

        long subKey = RegionData.subRegionKey(rx, rz, sx, sz);

        // 1. Faction check: Pillager territory only
        RegionData.RegionState regState = regions.getSavedRegionState(rx, rz);
        if (regState == null) {
            regState = ProceduralRegionGenerator.getInstance().generateRawRegionState(level, level.getSeed(), rx, rz);
        }

        if (regState.owner() != Faction.PILLAGER_CONQUERORS) {
            regions.recordNoBridge(subKey);
            return false;
        }

        // 2. Ensure all 4 chunks of the subregion are loaded
        int minChunkX = (rx * 8) + (sx * 4);
        int minChunkZ = (rz * 8) + (sz * 4);
        for (int cx = minChunkX; cx < minChunkX + 4; cx++) {
            for (int cz = minChunkZ; cz < minChunkZ + 4; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) {
                    return false; // Chunks not yet loaded, defer evaluation
                }
            }
        }

        int subMinX = rx * RegionData.REGION_SIZE_BLOCKS + sx * 64;
        int subMinZ = rz * RegionData.REGION_SIZE_BLOCKS + sz * 64;
        int subMaxX = subMinX + 63;
        int subMaxZ = subMinZ + 63;

        // Base collision parameters for moat bridging
        BlockPos baseAnchor = regState.baseAnchor();
        BaseType baseType = regState.baseType();

        // 3. Multi-candidate crossing evaluation with dense transects (step 4) across both cardinal axes
        BridgeCandidate bestCandidate = null;

        // Evaluate Along-X Bridge Candidates (East <-> West across North-South river channels)
        for (int testZ = subMinZ + 6; testZ <= subMaxZ - 6; testZ += 4) {
            BridgeCandidate cand = evaluateCandidateAlongX(level, subMinX, subMaxX, testZ, baseAnchor, baseType);
            if (cand != null && (bestCandidate == null || cand.fitness > bestCandidate.fitness)) {
                bestCandidate = cand;
            }
        }

        // Evaluate Along-Z Bridge Candidates (North <-> South across East-West river channels)
        for (int testX = subMinX + 6; testX <= subMaxX - 6; testX += 4) {
            BridgeCandidate cand = evaluateCandidateAlongZ(level, testX, subMinZ, subMaxZ, baseAnchor, baseType);
            if (cand != null && (bestCandidate == null || cand.fitness > bestCandidate.fitness)) {
                bestCandidate = cand;
            }
        }

        if (bestCandidate != null) {
            SubregionBridge bridge = ProceduralArchBridgeBuilder.buildBridge(
                    level, rx, rz, sx, sz,
                    bestCandidate.axis,
                    bestCandidate.bankA,
                    bestCandidate.bankB,
                    bestCandidate.waterY,
                    bestCandidate.bedY);
            regions.recordBridge(bridge);
            return true;
        }

        // No safe, high-fitness river crossing found in this subregion
        regions.recordNoBridge(subKey);
        return false;
    }

    private static record BridgeCandidate(
            Direction.Axis axis,
            BlockPos bankA,
            BlockPos bankB,
            int waterY,
            int bedY,
            int fitness
    ) {}

    private static record BankLanding(
            int coord,
            int y
    ) {}

    /**
     * Evaluates an East <-> West bridge candidate spanning along X at line testZ.
     */
    private static BridgeCandidate evaluateCandidateAlongX(
            ServerLevel level,
            int subMinX,
            int subMaxX,
            int testZ,
            BlockPos baseAnchor,
            BaseType baseType) {

        int waterStart = Integer.MIN_VALUE;
        int waterEnd = Integer.MIN_VALUE;

        // Scan along X to locate the river channel
        for (int x = subMinX + 2; x <= subMaxX - 2; x++) {
            if (isWater(level, x, testZ)) {
                if (waterStart == Integer.MIN_VALUE) {
                    waterStart = x;
                }
                waterEnd = x;
            } else if (waterStart != Integer.MIN_VALUE) {
                int width = waterEnd - waterStart + 1;
                // River width acceptance: 5 to 32 blocks
                if (width >= 5 && width <= 32) {
                    if (waterStart >= subMinX + 2 && waterEnd <= subMaxX - 2) {
                        break;
                    }
                }
                waterStart = Integer.MIN_VALUE;
                waterEnd = Integer.MIN_VALUE;
            }
        }

        if (waterStart == Integer.MIN_VALUE || waterEnd == Integer.MIN_VALUE) {
            return null;
        }

        int width = waterEnd - waterStart + 1;
        if (width < 5 || width > 32) {
            return null;
        }

        int midX = (waterStart + waterEnd) / 2;

        int waterSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, midX, testZ);
        if (waterSurfaceY <= level.getMinBuildHeight()) return null;
        int waterY = waterSurfaceY - 1;
        int bedY = getRiverBedY(level, midX, testZ, waterY);
        int waterDepth = waterY - bedY;

        // Check genuine continuous river channel (depth >= 2 and orthogonal flow continuity at +/-6, +/-10)
        if (!isGenuineRiverChannel(level, midX, testZ, Direction.Axis.X, waterDepth)) {
            return null;
        }

        // Adaptive rugged shoreline & hill slope profiling on both banks
        BankLanding landingA = profileBankAlongX(level, waterStart, testZ, -1, waterY);
        BankLanding landingB = profileBankAlongX(level, waterEnd, testZ, 1, waterY);
        if (landingA == null || landingB == null) {
            return null;
        }

        int bankA_X = landingA.coord();
        int bankB_X = landingB.coord();
        int bankA_Y = landingA.y();
        int bankB_Y = landingB.y();

        // Reject base fortress collisions (permitting moat bridges outside fortress walls)
        if (collidesWithBaseStructure(baseAnchor, baseType, bankA_X - 2, bankB_X + 2, testZ - 3, testZ + 3)) {
            return null;
        }

        int deltaY = Math.abs(bankA_Y - bankB_Y);
        if (deltaY > 6) {
            return null; // Reject steep vertical differentials exceeding arch limits
        }

        int span = bankB_X - bankA_X;
        // Fitness scoring: base 150, penalizing long spans and vertical bank differentials
        int fitness = 150 - span - (deltaY * 8);

        BlockPos bankA = new BlockPos(bankA_X, bankA_Y, testZ);
        BlockPos bankB = new BlockPos(bankB_X, bankB_Y, testZ);

        return new BridgeCandidate(Direction.Axis.X, bankA, bankB, waterY, bedY, fitness);
    }

    /**
     * Evaluates a North <-> South bridge candidate spanning along Z at line testX.
     */
    private static BridgeCandidate evaluateCandidateAlongZ(
            ServerLevel level,
            int testX,
            int subMinZ,
            int subMaxZ,
            BlockPos baseAnchor,
            BaseType baseType) {

        int waterStart = Integer.MIN_VALUE;
        int waterEnd = Integer.MIN_VALUE;

        // Scan along Z to locate the river channel
        for (int z = subMinZ + 2; z <= subMaxZ - 2; z++) {
            if (isWater(level, testX, z)) {
                if (waterStart == Integer.MIN_VALUE) {
                    waterStart = z;
                }
                waterEnd = z;
            } else if (waterStart != Integer.MIN_VALUE) {
                int width = waterEnd - waterStart + 1;
                // River width acceptance: 5 to 32 blocks
                if (width >= 5 && width <= 32) {
                    if (waterStart >= subMinZ + 2 && waterEnd <= subMaxZ - 2) {
                        break;
                    }
                }
                waterStart = Integer.MIN_VALUE;
                waterEnd = Integer.MIN_VALUE;
            }
        }

        if (waterStart == Integer.MIN_VALUE || waterEnd == Integer.MIN_VALUE) {
            return null;
        }

        int width = waterEnd - waterStart + 1;
        if (width < 5 || width > 32) {
            return null;
        }

        int midZ = (waterStart + waterEnd) / 2;

        int waterSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, testX, midZ);
        if (waterSurfaceY <= level.getMinBuildHeight()) return null;
        int waterY = waterSurfaceY - 1;
        int bedY = getRiverBedY(level, testX, midZ, waterY);
        int waterDepth = waterY - bedY;

        // Check genuine continuous river channel
        if (!isGenuineRiverChannel(level, testX, midZ, Direction.Axis.Z, waterDepth)) {
            return null;
        }

        // Adaptive rugged shoreline & hill slope profiling on both banks
        BankLanding landingA = profileBankAlongZ(level, testX, waterStart, -1, waterY);
        BankLanding landingB = profileBankAlongZ(level, testX, waterEnd, 1, waterY);
        if (landingA == null || landingB == null) {
            return null;
        }

        int bankA_Z = landingA.coord();
        int bankB_Z = landingB.coord();
        int bankA_Y = landingA.y();
        int bankB_Y = landingB.y();

        if (collidesWithBaseStructure(baseAnchor, baseType, testX - 3, testX + 3, bankA_Z - 2, bankB_Z + 2)) {
            return null;
        }

        int deltaY = Math.abs(bankA_Y - bankB_Y);
        if (deltaY > 6) {
            return null;
        }

        int span = bankB_Z - bankA_Z;
        int fitness = 150 - span - (deltaY * 8);

        BlockPos bankA = new BlockPos(testX, bankA_Y, bankA_Z);
        BlockPos bankB = new BlockPos(testX, bankB_Y, bankB_Z);

        return new BridgeCandidate(Direction.Axis.Z, bankA, bankB, waterY, bedY, fitness);
    }

    /**
     * Verifies that the candidate water body is a genuine flowing river channel rather than an isolated puddle.
     * Enforces center water depth >= 2 and at least 3 out of 4 orthogonal upstream/downstream points are water.
     */
    private static boolean isGenuineRiverChannel(ServerLevel level, int midX, int midZ, Direction.Axis spanAxis, int waterDepth) {
        if (waterDepth < 2 || waterDepth > 12) {
            return false;
        }

        if (spanAxis == Direction.Axis.X) {
            // River channel flows North <-> South (along Z)
            boolean wNorth1 = isWater(level, midX, midZ - 6);
            boolean wNorth2 = isWater(level, midX, midZ - 10);
            boolean wSouth1 = isWater(level, midX, midZ + 6);
            boolean wSouth2 = isWater(level, midX, midZ + 10);
            int waterPoints = (wNorth1 ? 1 : 0) + (wNorth2 ? 1 : 0) + (wSouth1 ? 1 : 0) + (wSouth2 ? 1 : 0);
            return waterPoints >= 3;
        } else {
            // River channel flows West <-> East (along X)
            boolean wWest1 = isWater(level, midX - 6, midZ);
            boolean wWest2 = isWater(level, midX - 10, midZ);
            boolean wEast1 = isWater(level, midX + 6, midZ);
            boolean wEast2 = isWater(level, midX + 10, midZ);
            int waterPoints = (wWest1 ? 1 : 0) + (wWest2 ? 1 : 0) + (wEast1 ? 1 : 0) + (wEast2 ? 1 : 0);
            return waterPoints >= 3;
        }
    }

    /**
     * Adaptively profiles the bank terrain along X inland from the water edge, accounting for rugged shorelines,
     * flat aprons, and sudden hills.
     */
    private static BankLanding profileBankAlongX(ServerLevel level, int waterEdgeX, int testZ, int dir, int waterY) {
        // 1. Find the first distance inland where all 5 width positions are completely dry
        int dDry = -1;
        for (int d = 1; d <= 4; d++) {
            int x = waterEdgeX + (dir * d);
            boolean allDry = true;
            for (int w = -2; w <= 2; w++) {
                if (isWater(level, x, testZ + w)) {
                    allDry = false;
                    break;
                }
            }
            if (allDry) {
                dDry = d;
                break;
            }
        }
        if (dDry == -1) return null; // Fully submerged or irregular shoreline

        int xDry = waterEdgeX + (dir * dDry);
        if (!isValidBridgeBank(level, xDry, testZ, Direction.Axis.X, waterY)) {
            return null;
        }
        int yDry = findTrueGroundSurfaceY(level, xDry, testZ);

        // 2. Profile the next distance inland for sudden hills vs flat terrain aprons
        int dNext = dDry + 1;
        int xNext = waterEdgeX + (dir * dNext);
        if (isValidBridgeBank(level, xNext, testZ, Direction.Axis.X, waterY)) {
            int yNext = findTrueGroundSurfaceY(level, xNext, testZ);
            int deltaY = yNext - yDry;

            // Check if there is a sudden hill beginning at dThird
            int dThird = dDry + 2;
            int xThird = waterEdgeX + (dir * dThird);
            int yThird = isValidBridgeBank(level, xThird, testZ, Direction.Axis.X, waterY)
                    ? findTrueGroundSurfaceY(level, xThird, testZ)
                    : yNext;

            // Rule A: Flat surface at dDry and dNext, but sudden hill at dThird:
            // Finish bridge on the flat apron before that hill!
            if (Math.abs(deltaY) <= 1 && (yThird - yNext >= 2)) {
                return new BankLanding(xNext, yNext);
            }

            // Rule B: Hill is within these immediate blocks (rises sharply from water):
            // Build according to the hill elevation
            if (deltaY >= 2) {
                return new BankLanding(xNext, yNext);
            }

            // Rule C: Gentle slope or normal flat terrain: land solidly at dNext
            return new BankLanding(xNext, yNext);
        }

        return new BankLanding(xDry, yDry);
    }

    /**
     * Adaptively profiles the bank terrain along Z inland from the water edge.
     */
    private static BankLanding profileBankAlongZ(ServerLevel level, int testX, int waterEdgeZ, int dir, int waterY) {
        int dDry = -1;
        for (int d = 1; d <= 4; d++) {
            int z = waterEdgeZ + (dir * d);
            boolean allDry = true;
            for (int w = -2; w <= 2; w++) {
                if (isWater(level, testX + w, z)) {
                    allDry = false;
                    break;
                }
            }
            if (allDry) {
                dDry = d;
                break;
            }
        }
        if (dDry == -1) return null;

        int zDry = waterEdgeZ + (dir * dDry);
        if (!isValidBridgeBank(level, testX, zDry, Direction.Axis.Z, waterY)) {
            return null;
        }
        int yDry = findTrueGroundSurfaceY(level, testX, zDry);

        int dNext = dDry + 1;
        int zNext = waterEdgeZ + (dir * dNext);
        if (isValidBridgeBank(level, testX, zNext, Direction.Axis.Z, waterY)) {
            int yNext = findTrueGroundSurfaceY(level, testX, zNext);
            int deltaY = yNext - yDry;

            int dThird = dDry + 2;
            int zThird = waterEdgeZ + (dir * dThird);
            int yThird = isValidBridgeBank(level, testX, zThird, Direction.Axis.Z, waterY)
                    ? findTrueGroundSurfaceY(level, testX, zThird)
                    : yNext;

            if (Math.abs(deltaY) <= 1 && (yThird - yNext >= 2)) {
                return new BankLanding(zNext, yNext);
            }

            if (deltaY >= 2) {
                return new BankLanding(zNext, yNext);
            }

            return new BankLanding(zNext, yNext);
        }

        return new BankLanding(zDry, yDry);
    }

    /**
     * Validates that all 5 blocks across the bridge width are solid dry land with realistic bank elevations
     * and a continuous solid ground pillar down to water level (strictly eliminating floating islands).
     */
    private static boolean isValidBridgeBank(ServerLevel level, int bankX, int bankZ, Direction.Axis spanAxis, int waterY) {
        int minGroundY = Integer.MAX_VALUE;
        int maxGroundY = Integer.MIN_VALUE;

        for (int w = -2; w <= 2; w++) {
            int x = (spanAxis == Direction.Axis.X) ? bankX : bankX + w;
            int z = (spanAxis == Direction.Axis.X) ? bankZ + w : bankZ;

            // 1. Must not be in water
            if (isWater(level, x, z)) {
                return false;
            }

            // 2. Sample true ground elevation
            int groundY = findTrueGroundSurfaceY(level, x, z);

            // 3. Bank elevation ceiling: must be near water level (anti-floating island)
            if (groundY > waterY + 12 || groundY < waterY + 1) {
                return false;
            }

            // 4. Continuous ground pillar check (anti-floating island & anti-void defect):
            // Verifies there are zero air or liquid gaps from ground down to water level
            for (int checkY = groundY - 1; checkY >= waterY; checkY--) {
                BlockPos pos = new BlockPos(x, checkY, z);
                BlockState state = level.getBlockState(pos);
                if (state.isAir() || !level.getFluidState(pos).isEmpty()) {
                    return false; // Air gap detected beneath ground!
                }
                if (!state.blocksMotion()) {
                    return false;
                }
            }

            minGroundY = Math.min(minGroundY, groundY);
            maxGroundY = Math.max(maxGroundY, groundY);
        }

        // 5. Cross-bank lateral slope variance must be <= 2 blocks
        return (maxGroundY - minGroundY) <= 2;
    }

    /**
     * Checks if candidate bridge coordinates overlap the physical fortress building footprint.
     */
    private static boolean collidesWithBaseStructure(BlockPos baseAnchor, BaseType baseType, int minX, int maxX, int minZ, int maxZ) {
        if (baseAnchor == null || baseType == null || baseType == BaseType.NONE) {
            return false;
        }

        int halfX = 16;
        int halfZ = 16;
        if (baseType == BaseType.HEADQUARTERS) {
            halfX = 45;
            halfZ = 45;
        } else if (baseType == BaseType.MEGA_BASE) {
            halfX = 50;
            halfZ = 50;
        }

        int baseMinX = baseAnchor.getX() - halfX - 4;
        int baseMaxX = baseAnchor.getX() + halfX + 4;
        int baseMinZ = baseAnchor.getZ() - halfZ - 4;
        int baseMaxZ = baseAnchor.getZ() + halfZ + 4;

        return minX <= baseMaxX && maxX >= baseMinX && minZ <= baseMaxZ && maxZ >= baseMinZ;
    }

    private static boolean isWater(ServerLevel level, int x, int z) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (surfaceY <= level.getMinBuildHeight()) return false;
        if (!level.getFluidState(new BlockPos(x, surfaceY - 1, z)).isEmpty()) return true;
        if (!level.getFluidState(new BlockPos(x, surfaceY, z)).isEmpty()) return true;
        return false;
    }

    private static int getRiverBedY(ServerLevel level, int x, int z, int waterY) {
        int y = waterY - 1;
        while (y > level.getMinBuildHeight()) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getFluidState(pos).isEmpty() && level.getBlockState(pos).blocksMotion()) {
                return y;
            }
            y--;
        }
        return waterY - 2;
    }

    /**
     * Finds the authentic solid ground surface Y coordinate, penetrating downwards through
     * tree canopies, leaves, logs, foliage, and plants to prevent anchoring bridges in mid-air.
     */
    public static int findTrueGroundSurfaceY(ServerLevel level, int x, int z) {
        int startY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (startY <= level.getMinBuildHeight()) return 64;

        for (int y = startY; y > level.getMinBuildHeight(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            FluidState fluid = level.getFluidState(pos);

            // Ignore air and fluids
            if (state.isAir() || !fluid.isEmpty()) {
                continue;
            }

            // Ignore tree leaves, logs, wood, and plant decorations
            if (isTreeOrPlantBlock(state)) {
                continue;
            }

            // First true solid ground block encountered (dirt, stone, sand, gravel, clay, etc.)
            if (state.blocksMotion()) {
                return y + 1; // Walkable surface is one block above the solid block
            }
        }

        return Math.max(64, level.getSeaLevel());
    }

    private static boolean isTreeOrPlantBlock(BlockState state) {
        if (state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)
                || state.is(BlockTags.SAPLINGS)) {
            return true;
        }
        Block b = state.getBlock();
        return b instanceof LeavesBlock
                || b instanceof VineBlock
                || b instanceof BushBlock
                || b instanceof MangroveRootsBlock;
    }
}
