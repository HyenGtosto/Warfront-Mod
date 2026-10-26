package com.warfront.region.base;

import com.warfront.mission.MissionProfile;
import com.warfront.mission.MissionType;
import com.warfront.mission.SubRegionMission;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.HashMap;
import java.util.Map;

/**
 * Concrete script generator for 50x90 Pillager Headquarters (Medium Base).
 *
 * <p>Design specifications:</p>
 * <ul>
 *   <li>50x90 footprint spanning 2 adjacent subregions with 5-block thick outer perimeter walls.</li>
 *   <li>Seamless open central courtyard: No dividing wall or sealed gate between wings;
 *       connected by wide cobblestone/gravel pathways.</li>
 *   <li>Direct data pull from {@link MissionProfile#generateForRegion}:
 *       inspects the missions assigned to the 2 occupied subregions to build the matching
 *       facilities (Garrison Commander Keep, Command Center / Intel Post, or Storage Depot).</li>
 *   <li>Full stepped cobblestone foundation down to terrain so structures never float on slopes.</li>
 *   <li>Full {@link #getPristineBlocks} blueprint map support for intelligent subregion base self-repair.</li>
 * </ul>
 */
public class PillagerHeadquartersGenerator implements BaseBuildingGenerator {

    public static final int SIZE_X = 90;
    public static final int SIZE_Z = 90;
    public static final int HEIGHT = 25;
    private static final int MAX_FOUNDATION_DEPTH = 36;

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
    public boolean place(BasePlacementContext context) {
        ServerLevel level = context.level();
        BlockPos anchor = context.anchor();
        if (level == null || anchor == null) {
            return false;
        }

        Map<BlockPos, BlockState> blueprint = buildBlueprint(
                level, anchor, context.regionX(), context.regionZ(),
                context.faction(), context.baseType(), context.worldSeed()
        );

        if (blueprint.isEmpty()) {
            return false;
        }

        // 1. Dynamic Column Heightmap Excavation: Carve out interior mountain rock to open sky
        int localX = Math.floorMod(anchor.getX(), RegionData.REGION_SIZE_BLOCKS);
        int localZ = Math.floorMod(anchor.getZ(), RegionData.REGION_SIZE_BLOCKS);
        boolean isEastWest = Math.abs(localX - 64) > Math.abs(localZ - 64);
        int minX = isEastWest ? anchor.getX() - 25 : anchor.getX() - 45;
        int maxX = isEastWest ? anchor.getX() + 24 : anchor.getX() + 44;
        int minZ = isEastWest ? anchor.getZ() - 45 : anchor.getZ() - 25;
        int maxZ = isEastWest ? anchor.getZ() + 44 : anchor.getZ() + 24;

        int interiorMinX = minX + 5;
        int interiorMaxX = maxX - 5;
        int interiorMinZ = minZ + 5;
        int interiorMaxZ = maxZ - 5;
        int baseY = anchor.getY();

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for (int x = interiorMinX; x <= interiorMaxX; x++) {
            for (int z = interiorMinZ; z <= interiorMaxZ; z++) {
                int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                int clearMaxY = Math.max(surfaceY, baseY + 14);
                for (int y = baseY + 1; y <= clearMaxY; y++) {
                    mpos.set(x, y, z);
                    BlockState state = level.getBlockState(mpos);
                    if (!state.isAir()) {
                        level.setBlock(mpos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }

        // 2. Place all blueprint blocks
        for (Map.Entry<BlockPos, BlockState> entry : blueprint.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_CLIENTS);
        }

        // 3. Extend stepped foundation down to terrain under perimeter walls and facilities
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        int subFloorY = baseY - 1;

        for (BlockPos pos : blueprint.keySet()) {
            if (pos.getY() == subFloorY) {
                for (int depth = 1; depth <= MAX_FOUNDATION_DEPTH; depth++) {
                    mpos.set(pos.getX(), subFloorY - depth, pos.getZ());
                    if (mpos.getY() < level.getMinBuildHeight() + 2) break;
                    BlockState current = level.getBlockState(mpos);
                    if (current.isAir() || current.liquid() || current.is(Blocks.SHORT_GRASS) || current.is(Blocks.TALL_GRASS)) {
                        level.setBlock(mpos, cobble, Block.UPDATE_CLIENTS);
                    } else {
                        break;
                    }
                }
            }
        }

        return true;
    }

    @Override
    public Map<BlockPos, BlockState> getPristineBlocks(ServerLevel level, BlockPos anchor) {
        if (level == null || anchor == null) {
            return Map.of();
        }
        int rx = Math.floorDiv(anchor.getX(), RegionData.REGION_SIZE_BLOCKS);
        int rz = Math.floorDiv(anchor.getZ(), RegionData.REGION_SIZE_BLOCKS);
        return buildBlueprint(level, anchor, rx, rz, Faction.PILLAGER_CONQUERORS, BaseType.HEADQUARTERS, level.getSeed());
    }

    /**
     * Builds the complete mathematical blueprint of the 50x90 Headquarters and its 2 rolled facilities.
     */
    private Map<BlockPos, BlockState> buildBlueprint(
            ServerLevel level, BlockPos anchor, int rx, int rz,
            Faction faction, BaseType baseType, long worldSeed
    ) {
        Map<BlockPos, BlockState> map = new HashMap<>();

        // Determine orientation from anchor coordinates
        int localX = Math.floorMod(anchor.getX(), RegionData.REGION_SIZE_BLOCKS);
        int localZ = Math.floorMod(anchor.getZ(), RegionData.REGION_SIZE_BLOCKS);

        boolean isEastWest = Math.abs(localX - 64) > Math.abs(localZ - 64);
        int subABit;
        int subBBit;

        int minX, maxX, minZ, maxZ;
        if (isEastWest) {
            // Longitudinal axis is along Z (90 blocks: anchor.getZ() - 45 .. anchor.getZ() + 44)
            // Transverse axis is along X (50 blocks: anchor.getX() - 25 .. anchor.getX() + 24)
            minX = anchor.getX() - 25;
            maxX = anchor.getX() + 24;
            minZ = anchor.getZ() - 45;
            maxZ = anchor.getZ() + 44;

            if (localX >= 64) {
                // EAST: Subregion (1,0) [bit 1] and Subregion (1,1) [bit 3]
                subABit = 1;
                subBBit = 3;
            } else {
                // WEST: Subregion (0,0) [bit 0] and Subregion (0,1) [bit 2]
                subABit = 0;
                subBBit = 2;
            }
        } else {
            // Longitudinal axis is along X (90 blocks: anchor.getX() - 45 .. anchor.getX() + 44)
            // Transverse axis is along Z (50 blocks: anchor.getZ() - 25 .. anchor.getZ() + 24)
            minX = anchor.getX() - 45;
            maxX = anchor.getX() + 44;
            minZ = anchor.getZ() - 25;
            maxZ = anchor.getZ() + 24;

            if (localZ >= 64) {
                // SOUTH: Subregion (0,1) [bit 2] and Subregion (1,1) [bit 3]
                subABit = 2;
                subBBit = 3;
            } else {
                // NORTH: Subregion (0,0) [bit 0] and Subregion (1,0) [bit 1]
                subABit = 0;
                subBBit = 1;
            }
        }

        // Query the authoritative shared 2-wing contract
        MissionProfile.HQWingContract contract = MissionProfile.getHQWingContract(worldSeed, rx, rz, anchor);
        MissionType missionA = contract.getTypeForBit(subABit);
        MissionType missionB = contract.getTypeForBit(subBBit);
        if (missionA == null) missionA = MissionType.BASE_KILL_COMMANDER;
        if (missionB == null) missionB = MissionType.BASE_DESTROY_INTEL;

        // Common BlockStates
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState deepslateBricks = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
        BlockState darkOakLogY = Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState darkOakPlanks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState darkOakFence = Blocks.DARK_OAK_FENCE.defaultBlockState();
        BlockState ironBars = Blocks.IRON_BARS.defaultBlockState();
        BlockState stoneBricks = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        BlockState torch = Blocks.WALL_TORCH.defaultBlockState();

        int baseY = anchor.getY();

        // 1. Build Subsurface Foundation Layer (Y = baseY - 1)
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                map.put(new BlockPos(x, baseY - 1, z), cobble);
            }
        }

        // 2. Build 5-Block Thick Outer Perimeter Walls
        // Wall thickness: 5 blocks from outer edge
        for (int x = minX; x <= maxX; x++) {
            boolean isOuterX = (x <= minX + 4 || x >= maxX - 4);
            boolean isEdgeX = (x == minX || x == maxX);

            for (int z = minZ; z <= maxZ; z++) {
                boolean isOuterZ = (z <= minZ + 4 || z >= maxZ - 4);
                boolean isEdgeZ = (z == minZ || z == maxZ);

                // Check if block is inside the 5-block perimeter wall
                if (isOuterX || isOuterZ) {
                    // Check if corner bastion (8x8 footprint at each of the 4 outer corners)
                    boolean isCornerBastion = (x <= minX + 7 || x >= maxX - 7) && (z <= minZ + 7 || z >= maxZ - 7);
                    int wallHeight = isCornerBastion ? 12 : 7;

                    for (int y = baseY; y <= baseY + wallHeight; y++) {
                        int relY = y - baseY;
                        BlockPos pos = new BlockPos(x, y, z);

                        if (isCornerBastion) {
                            // Bastion corners: Dark oak log pillars at outer vertices
                            if (isEdgeX && isEdgeZ) {
                                map.put(pos, darkOakLogY);
                            } else if (relY == 0) {
                                map.put(pos, cobble);
                            } else if (relY == wallHeight) {
                                // Bastion roof parapet
                                if (isEdgeX || isEdgeZ || x == minX + 7 || x == maxX - 7 || z == minZ + 7 || z == maxZ - 7) {
                                    map.put(pos, darkOakFence);
                                } else {
                                    map.put(pos, darkOakPlanks);
                                }
                            } else if (relY == 6 || relY == 11) {
                                map.put(pos, darkOakPlanks);
                            } else {
                                map.put(pos, deepslateBricks);
                            }
                        } else {
                            // Standard 5-block thick perimeter wall
                            if (relY == 0) {
                                map.put(pos, cobble);
                            } else if (relY <= 5) {
                                // Pillars every 5 blocks along wall
                                boolean isPilaster = (isEastWest ? ((z - minZ) % 5 == 0) : ((x - minX) % 5 == 0));
                                if (isPilaster && (isEdgeX || isEdgeZ)) {
                                    map.put(pos, darkOakLogY);
                                } else if (relY == 3 && (isEdgeX || isEdgeZ) && ((x + z) % 3 == 0)) {
                                    // Arrow slits
                                    map.put(pos, ironBars);
                                } else {
                                    map.put(pos, deepslateBricks);
                                }
                            } else if (relY == 6) {
                                // Elevated 3-block rampart walkway
                                boolean isOuterParapet = (isEdgeX || isEdgeZ);
                                boolean isInnerParapet = (x == minX + 4 || x == maxX - 4 || z == minZ + 4 || z == maxZ - 4);
                                if (isOuterParapet) {
                                    map.put(pos, deepslateBricks);
                                } else if (isInnerParapet) {
                                    map.put(pos, darkOakFence);
                                } else {
                                    map.put(pos, darkOakPlanks);
                                }
                            } else if (relY == 7) {
                                // Crenellations on outer parapet
                                if ((isEdgeX || isEdgeZ) && ((x + z) % 2 == 0)) {
                                    map.put(pos, Blocks.DEEPSLATE_BRICK_WALL.defaultBlockState());
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Build Seamless Open Central Courtyard & Connecting Pathways
        // Center thoroughfare (6 blocks wide) connecting Wing A to Wing B
        int midX = (minX + maxX) / 2;
        int midZ = (minZ + maxZ) / 2;

        if (isEastWest) {
            // Path runs North-South along Z through the center
            for (int z = minZ + 5; z <= maxZ - 5; z++) {
                for (int x = midX - 3; x <= midX + 3; x++) {
                    BlockState roadState = ((x + z) % 3 == 0) ? gravel : (((x + z) % 2 == 0) ? stoneBricks : cobble);
                    map.put(new BlockPos(x, baseY, z), roadState);
                }
            }
        } else {
            // Path runs East-West along X through the center
            for (int x = minX + 5; x <= maxX - 5; x++) {
                for (int z = midZ - 3; z <= midZ + 3; z++) {
                    BlockState roadState = ((x + z) % 3 == 0) ? gravel : (((x + z) % 2 == 0) ? stoneBricks : cobble);
                    map.put(new BlockPos(x, baseY, z), roadState);
                }
            }
        }

        // 4. Procedurally Place the 2 Chosen Mission Facilities
        // Facility A is centered in Wing A; Facility B is centered in Wing B
        int wingACenterX, wingACenterZ;
        int wingBCenterX, wingBCenterZ;

        if (isEastWest) {
            wingACenterX = midX;
            wingACenterZ = minZ + 22; // Center of North half

            wingBCenterX = midX;
            wingBCenterZ = maxZ - 22; // Center of South half
        } else {
            wingACenterX = minX + 22; // Center of West half
            wingACenterZ = midZ;

            wingBCenterX = maxX - 22; // Center of East half
            wingBCenterZ = midZ;
        }

        buildFacility(map, wingACenterX, baseY, wingACenterZ, missionA, isEastWest);
        buildFacility(map, wingBCenterX, baseY, wingBCenterZ, missionB, isEastWest);

        return map;
    }

    /**
     * Builds one of the 3 mission facilities (Keep, Intel Bunker, or Storage Depot) inside its designated wing.
     */
    private void buildFacility(
            Map<BlockPos, BlockState> map,
            int centerX, int baseY, int centerZ,
            MissionType missionType, boolean isEastWest
    ) {
        int halfW = 8;  // 16 wide
        int halfL = 12; // 24 long

        int fMinX = centerX - halfW;
        int fMaxX = centerX + halfW;
        int fMinZ = centerZ - halfL;
        int fMaxZ = centerZ + halfL;

        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState stoneBricks = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState deepslateTiles = Blocks.DEEPSLATE_TILES.defaultBlockState();
        BlockState darkOakLogY = Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState darkOakPlanks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState ironBars = Blocks.IRON_BARS.defaultBlockState();
        BlockState chains = Blocks.CHAIN.defaultBlockState();

        switch (missionType) {
            case BASE_KILL_COMMANDER -> {
                // Garrison Commander's Keep: High grand hall, elevated dais, iron throne, crimson carpet
                int keepHeight = 9;

                // Floor: Polished Deepslate Tiles with central Crimson Carpet
                for (int x = fMinX; x <= fMaxX; x++) {
                    for (int z = fMinZ; z <= fMaxZ; z++) {
                        map.put(new BlockPos(x, baseY, z), deepslateTiles);
                        // Red carpet aisle down the center
                        if (Math.abs(x - centerX) <= 1 && z >= fMinZ + 4 && z <= fMaxZ - 4) {
                            map.put(new BlockPos(x, baseY + 1, z), Blocks.RED_CARPET.defaultBlockState());
                        }
                    }
                }

                // Walls: Stone Bricks with Dark Oak log pillars
                for (int y = baseY + 1; y <= baseY + keepHeight; y++) {
                    for (int x = fMinX; x <= fMaxX; x++) {
                        for (int z = fMinZ; z <= fMaxZ; z++) {
                            boolean isWallX = (x == fMinX || x == fMaxX);
                            boolean isWallZ = (z == fMinZ || z == fMaxZ);
                            if (isWallX || isWallZ) {
                                // Entrance opening (4 blocks wide, 4 blocks high at front)
                                boolean isDoor = (z == fMaxZ && Math.abs(x - centerX) <= 1 && y <= baseY + 4);
                                if (isDoor) continue;

                                if (isWallX && isWallZ) {
                                    map.put(new BlockPos(x, y, z), darkOakLogY);
                                } else if ((x - fMinX) % 4 == 0 || (z - fMinZ) % 4 == 0) {
                                    map.put(new BlockPos(x, y, z), darkOakLogY);
                                } else if (y == baseY + 4 && ((x + z) % 3 == 0)) {
                                    map.put(new BlockPos(x, y, z), ironBars);
                                } else {
                                    map.put(new BlockPos(x, y, z), stoneBricks);
                                }
                            }
                        }
                    }
                }

                // Roof: Dark Oak Timber rafters
                for (int x = fMinX; x <= fMaxX; x++) {
                    for (int z = fMinZ; z <= fMaxZ; z++) {
                        map.put(new BlockPos(x, baseY + keepHeight + 1, z), darkOakPlanks);
                    }
                }

                // The Dais & Iron Throne (at North end of Keep: z = fMinZ + 3)
                int daisZ = fMinZ + 3;
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 1; dz++) {
                        map.put(new BlockPos(centerX + dx, baseY + 1, daisZ + dz), cobble);
                    }
                }
                // Throne: Iron block base, dark oak stairs, wither skeleton skull
                map.put(new BlockPos(centerX, baseY + 2, daisZ), Blocks.IRON_BLOCK.defaultBlockState());
                map.put(new BlockPos(centerX - 1, baseY + 2, daisZ), Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
                map.put(new BlockPos(centerX + 1, baseY + 2, daisZ), Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
                map.put(new BlockPos(centerX, baseY + 3, daisZ), Blocks.WITHER_SKELETON_SKULL.defaultBlockState());

                // Soul Fire Braziers on dais
                map.put(new BlockPos(centerX - 2, baseY + 2, daisZ - 1), Blocks.SOUL_LANTERN.defaultBlockState());
                map.put(new BlockPos(centerX + 2, baseY + 2, daisZ - 1), Blocks.SOUL_LANTERN.defaultBlockState());
            }

            case BASE_DESTROY_INTEL -> {
                // Command Center & Intel Post: War map table, radar antenna mast, bookshelves, cipher chests
                int bunkerHeight = 7;

                // Floor: Cobblestone & Stone Bricks
                for (int x = fMinX; x <= fMaxX; x++) {
                    for (int z = fMinZ; z <= fMaxZ; z++) {
                        map.put(new BlockPos(x, baseY, z), stoneBricks);
                    }
                }

                // Walls: Deepslate Bricks & Iron Bars
                for (int y = baseY + 1; y <= baseY + bunkerHeight; y++) {
                    for (int x = fMinX; x <= fMaxX; x++) {
                        for (int z = fMinZ; z <= fMaxZ; z++) {
                            boolean isWallX = (x == fMinX || x == fMaxX);
                            boolean isWallZ = (z == fMinZ || z == fMaxZ);
                            if (isWallX || isWallZ) {
                                boolean isDoor = (z == fMaxZ && Math.abs(x - centerX) <= 1 && y <= baseY + 3);
                                if (isDoor) continue;

                                if (isWallX && isWallZ) {
                                    map.put(new BlockPos(x, y, z), darkOakLogY);
                                } else if (y == baseY + 3) {
                                    map.put(new BlockPos(x, y, z), ironBars);
                                } else {
                                    map.put(new BlockPos(x, y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                                }
                            }
                        }
                    }
                }

                // Roof: Cobblestone slabs
                for (int x = fMinX; x <= fMaxX; x++) {
                    for (int z = fMinZ; z <= fMaxZ; z++) {
                        map.put(new BlockPos(x, baseY + bunkerHeight + 1, z), cobble);
                    }
                }

                // Strategic War Map Table (Centerpiece: 4x6 table)
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        map.put(new BlockPos(centerX + dx, baseY + 1, centerZ + dz), darkOakPlanks);
                        // Green and brown wool representing territory maps
                        BlockState mapMat = ((dx + dz) % 2 == 0) ? Blocks.GREEN_WOOL.defaultBlockState() : Blocks.BROWN_WOOL.defaultBlockState();
                        map.put(new BlockPos(centerX + dx, baseY + 2, centerZ + dz), mapMat);
                    }
                }
                // Redstone torch on map table representing enemy troop marker
                map.put(new BlockPos(centerX, baseY + 3, centerZ), Blocks.REDSTONE_TORCH.defaultBlockState());

                // Long-Range Antenna Mast (juts through roof into the sky)
                int antX = fMinX + 3;
                int antZ = fMinZ + 3;
                for (int y = baseY + 1; y <= baseY + bunkerHeight + 8; y++) {
                    map.put(new BlockPos(antX, y, antZ), ironBars);
                }
                map.put(new BlockPos(antX, baseY + bunkerHeight + 9, antZ), Blocks.LIGHTNING_ROD.defaultBlockState());

                // Bookshelves & Cipher Lockers
                for (int dx = -4; dx <= -2; dx++) {
                    map.put(new BlockPos(centerX + dx, baseY + 1, fMinZ + 1), Blocks.BOOKSHELF.defaultBlockState());
                    map.put(new BlockPos(centerX + dx, baseY + 2, fMinZ + 1), Blocks.BOOKSHELF.defaultBlockState());
                }
                map.put(new BlockPos(centerX + 3, baseY + 1, fMinZ + 1), Blocks.CHEST.defaultBlockState());
                map.put(new BlockPos(centerX + 4, baseY + 1, fMinZ + 1), Blocks.BARREL.defaultBlockState());
            }

            case BASE_DESTROY_SUPPLIES -> {
                // Storage Depot: Logistics warehouse, cargo hoist crane, stacked crates, TNT munitions cache
                int whHeight = 8;

                // Floor: Packed Mud & Cobblestone
                for (int x = fMinX; x <= fMaxX; x++) {
                    for (int z = fMinZ; z <= fMaxZ; z++) {
                        map.put(new BlockPos(x, baseY, z), ((x + z) % 2 == 0) ? Blocks.PACKED_MUD.defaultBlockState() : cobble);
                    }
                }

                // Walls: Open Timber warehouse frame with stone base
                for (int y = baseY + 1; y <= baseY + whHeight; y++) {
                    for (int x = fMinX; x <= fMaxX; x++) {
                        for (int z = fMinZ; z <= fMaxZ; z++) {
                            boolean isWallX = (x == fMinX || x == fMaxX);
                            boolean isWallZ = (z == fMinZ || z == fMaxZ);
                            if (isWallX || isWallZ) {
                                boolean isDoor = (z == fMaxZ && Math.abs(x - centerX) <= 2 && y <= baseY + 4);
                                if (isDoor) continue;

                                if (isWallX && isWallZ) {
                                    map.put(new BlockPos(x, y, z), darkOakLogY);
                                } else if ((x - fMinX) % 4 == 0 || (z - fMinZ) % 4 == 0) {
                                    map.put(new BlockPos(x, y, z), darkOakLogY);
                                } else if (y <= baseY + 2) {
                                    map.put(new BlockPos(x, y, z), stoneBricks);
                                } else if (y <= baseY + whHeight - 1) {
                                    map.put(new BlockPos(x, y, z), Blocks.DARK_OAK_FENCE.defaultBlockState());
                                } else {
                                    map.put(new BlockPos(x, y, z), darkOakLogY);
                                }
                            }
                        }
                    }
                }

                // Roof: Dark Oak Planks
                for (int x = fMinX; x <= fMaxX; x++) {
                    for (int z = fMinZ; z <= fMaxZ; z++) {
                        map.put(new BlockPos(x, baseY + whHeight + 1, z), darkOakPlanks);
                    }
                }

                // Cargo Hoist Crane (mounted in center)
                map.put(new BlockPos(centerX, baseY + whHeight, centerZ), darkOakLogY);
                map.put(new BlockPos(centerX, baseY + whHeight - 1, centerZ), chains);
                map.put(new BlockPos(centerX, baseY + whHeight - 2, centerZ), chains);
                // Suspended cargo pallet
                map.put(new BlockPos(centerX, baseY + whHeight - 3, centerZ), Blocks.BARREL.defaultBlockState());

                // Stacked Supply Barrels & Crates
                for (int dz = -2; dz <= 0; dz++) {
                    map.put(new BlockPos(fMinX + 2, baseY + 1, centerZ + dz), Blocks.BARREL.defaultBlockState());
                    map.put(new BlockPos(fMinX + 3, baseY + 1, centerZ + dz), Blocks.CHEST.defaultBlockState());
                    map.put(new BlockPos(fMinX + 2, baseY + 2, centerZ + dz), Blocks.BARREL.defaultBlockState());
                }

                // Armored TNT Munitions Cache (locked behind iron bars in corner)
                int tntX = fMaxX - 3;
                int tntZ = fMinZ + 3;
                map.put(new BlockPos(tntX, baseY + 1, tntZ), Blocks.TNT.defaultBlockState());
                map.put(new BlockPos(tntX + 1, baseY + 1, tntZ), Blocks.TNT.defaultBlockState());
                map.put(new BlockPos(tntX, baseY + 1, tntZ + 1), Blocks.TNT.defaultBlockState());
                map.put(new BlockPos(tntX + 1, baseY + 1, tntZ + 1), Blocks.TNT.defaultBlockState());
                // Cage around TNT
                map.put(new BlockPos(tntX - 1, baseY + 1, tntZ), ironBars);
                map.put(new BlockPos(tntX - 1, baseY + 1, tntZ + 1), ironBars);
                map.put(new BlockPos(tntX, baseY + 1, tntZ + 2), ironBars);
                map.put(new BlockPos(tntX + 1, baseY + 1, tntZ + 2), ironBars);
            }

            default -> {
                // Fallback basic depot
                for (int x = fMinX; x <= fMaxX; x++) {
                    for (int z = fMinZ; z <= fMaxZ; z++) {
                        map.put(new BlockPos(x, baseY, z), stoneBricks);
                    }
                }
            }
        }
    }
}
