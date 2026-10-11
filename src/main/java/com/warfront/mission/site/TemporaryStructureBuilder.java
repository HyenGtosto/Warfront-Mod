package com.warfront.mission.site;

import com.warfront.block.WarfrontBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.warfront.Warfront;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    // =========================================================================
    // Hard Tier Mission Structures
    // =========================================================================

    public record StrongpointResult(
            List<BlockPos> structureBlocks,
            List<BlockPos> shieldRelayPositions,
            BlockPos vaultPos
    ) {}

    /**
     * Builds a heavily fortified WWII-style concrete & deepslate pillbox redoubt (20x20 footprint).
     * Features narrow embrasure firing slits, angled entrance blast chicane, low-profile roof,
     * 2 volatile shield relays (sabotage targets), and 1 central heavy machinery vault (salvage prize).
     */
    public static StrongpointResult buildStrongpointPillbox(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placedBlocks = new ArrayList<>();
        List<BlockPos> shieldRelays = new ArrayList<>();

        BlockState deepslateBricks = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
        BlockState polishedDeepslate = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        BlockState cobbledDeepslate = Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        BlockState slab = Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState();
        BlockState ironBars = Blocks.IRON_BARS.defaultBlockState();
        BlockState relayBlock = WarfrontBlocks.DEFENSE_SHIELD_RELAY.get().defaultBlockState();
        BlockState vaultBlock = WarfrontBlocks.HEAVY_MACHINERY_VAULT.get().defaultBlockState();

        int baseY = anchor.getY();
        int minX = anchor.getX() - 9;
        int maxX = anchor.getX() + 10; // 20x20
        int minZ = anchor.getZ() - 9;
        int maxZ = anchor.getZ() + 10;

        // 1. Foundation: Stepped downward foundations and interior clearing
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean isEdge = (x == minX || x == maxX || z == minZ || z == maxZ);

                // Build foundation down to solid terrain
                BlockPos colPos = new BlockPos(x, baseY - 1, z);
                int depth = 0;
                while (depth < 8 && (level.getBlockState(colPos).isAir() || level.getBlockState(colPos).liquid())) {
                    snapshot.setBlock(level, colPos, isEdge ? cobbledDeepslate : polishedDeepslate, 3);
                    placedBlocks.add(colPos.immutable());
                    colPos = colPos.below();
                    depth++;
                }

                // Clear interior headroom up to Y+6
                for (int yOff = 0; yOff <= 6; yOff++) {
                    BlockPos airPos = new BlockPos(x, baseY + yOff, z);
                    if (!level.getBlockState(airPos).isAir()) {
                        snapshot.setBlock(level, airPos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }

                // Ground floor paving
                BlockPos floorPos = new BlockPos(x, baseY, z);
                snapshot.setBlock(level, floorPos, polishedDeepslate, 3);
                placedBlocks.add(floorPos.immutable());
            }
        }

        // 2. Thick Outer Walls & Firing Embrasures
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean isOuterWall = (x == minX || x == maxX || z == minZ || z == maxZ);
                boolean isCorner = (x == minX || x == maxX) && (z == minZ || z == maxZ);

                if (isOuterWall) {
                    for (int yOff = 1; yOff <= 4; yOff++) {
                        BlockPos wallPos = new BlockPos(x, baseY + yOff, z);

                        // South wall entrance chicane opening at Z == maxZ, X between anchor.getX()-1 and anchor.getX()+1
                        if (z == maxZ && (x >= anchor.getX() - 1 && x <= anchor.getX() + 1) && yOff <= 3) {
                            continue; // Doorway gap
                        }

                        // Embrasures (firing slits) at Y+2 on North, West, East walls
                        boolean isEmbrasure = (yOff == 2) && !isCorner && (
                                (z == minZ && Math.abs(x - anchor.getX()) % 4 == 2) ||
                                (x == minX && Math.abs(z - anchor.getZ()) % 4 == 2) ||
                                (x == maxX && Math.abs(z - anchor.getZ()) % 4 == 2)
                        );

                        BlockState blockToPlace = isEmbrasure ? ironBars : deepslateBricks;
                        snapshot.setBlock(level, wallPos, blockToPlace, 3);
                        placedBlocks.add(wallPos.immutable());
                    }
                }

                // Low-profile roof at Y+5
                BlockPos roofPos = new BlockPos(x, baseY + 5, z);
                snapshot.setBlock(level, roofPos, deepslateBricks, 3);
                placedBlocks.add(roofPos.immutable());

                // Parapet slabs on roof edges at Y+6
                if (isOuterWall) {
                    BlockPos parapetPos = new BlockPos(x, baseY + 6, z);
                    snapshot.setBlock(level, parapetPos, slab, 3);
                    placedBlocks.add(parapetPos.immutable());
                }
            }
        }

        // 3. Blast Chicane Baffle Wall inside South Entrance
        for (int x = anchor.getX() - 2; x <= anchor.getX() + 2; x++) {
            for (int yOff = 1; yOff <= 3; yOff++) {
                BlockPos chicanePos = new BlockPos(x, baseY + yOff, maxZ - 2);
                snapshot.setBlock(level, chicanePos, deepslateBricks, 3);
                placedBlocks.add(chicanePos.immutable());
            }
        }

        // 4. Central Vault & Objectives
        BlockPos vaultPos = new BlockPos(anchor.getX(), baseY + 1, anchor.getZ());
        snapshot.setBlock(level, vaultPos, vaultBlock, 3);
        placedBlocks.add(vaultPos.immutable());

        // Shield Relays (Volatile Sabotage Targets) flanking the vault
        BlockPos relay1 = new BlockPos(anchor.getX() - 4, baseY + 1, anchor.getZ());
        snapshot.setBlock(level, relay1, relayBlock, 3);
        placedBlocks.add(relay1.immutable());
        shieldRelays.add(relay1);

        BlockPos relay2 = new BlockPos(anchor.getX() + 4, baseY + 1, anchor.getZ());
        snapshot.setBlock(level, relay2, relayBlock, 3);
        placedBlocks.add(relay2.immutable());
        shieldRelays.add(relay2);

        // Moody bunker lighting
        snapshot.setBlock(level, vaultPos.above(), Blocks.LANTERN.defaultBlockState(), 3);
        snapshot.setBlock(level, relay1.above(), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
        snapshot.setBlock(level, relay2.above(), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);

        return new StrongpointResult(placedBlocks, shieldRelays, vaultPos);
    }

    public record OfficerCampResult(
            List<BlockPos> structureBlocks,
            BlockPos warDeskPos,
            BlockPos commanderSpawnPos,
            List<BlockPos> bodyguardSpawnPositions,
            List<BlockPos> outerPatrolSpawnPositions
    ) {}

    /**
     * Builds a 32x32 procedurally generated military encampment with perimeter barriers,
     * 4 outer soldier tents, central mess campfire, and a large central Command Pavilion
     * housing the Pillager Commander and the Commander's War Desk (salvage prize).
     */
    public static OfficerCampResult buildOfficerCamp(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placedBlocks = new ArrayList<>();
        List<BlockPos> bodyguardSpawns = new ArrayList<>();
        List<BlockPos> patrolSpawns = new ArrayList<>();

        BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
        BlockState fence = Blocks.DARK_OAK_FENCE.defaultBlockState();
        BlockState planks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState redWool = Blocks.RED_WOOL.defaultBlockState();
        BlockState blackWool = Blocks.BLACK_WOOL.defaultBlockState();
        BlockState grayWool = Blocks.GRAY_WOOL.defaultBlockState();
        BlockState deskBlock = WarfrontBlocks.COMMANDER_WAR_DESK.get().defaultBlockState();

        int baseY = anchor.getY();
        int minX = anchor.getX() - 15;
        int maxX = anchor.getX() + 16; // 32x32
        int minZ = anchor.getZ() - 15;
        int maxZ = anchor.getZ() + 16;

        // 1. Perimeter barricades (log posts & fences with gate openings at North and South)
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean isEdge = (x == minX || x == maxX || z == minZ || z == maxZ);
                boolean isCorner = (x == minX || x == maxX) && (z == minZ || z == maxZ);

                if (isEdge) {
                    // Checkpoint openings at North and South centers
                    boolean isGate = (z == minZ || z == maxZ) && Math.abs(x - anchor.getX()) <= 1;
                    if (!isGate) {
                        BlockPos barPos = new BlockPos(x, baseY + 1, z);
                        snapshot.setBlock(level, barPos, isCorner ? log : fence, 3);
                        placedBlocks.add(barPos.immutable());

                        // Stepped support down to solid terrain
                        BlockPos under = barPos.below();
                        int d = 0;
                        while (d < 5 && (level.getBlockState(under).isAir() || level.getBlockState(under).liquid())) {
                            snapshot.setBlock(level, under, log, 3);
                            placedBlocks.add(under.immutable());
                            under = under.below();
                            d++;
                        }
                    }
                }
            }
        }

        // Gate Lantern Posts
        snapshot.setBlock(level, new BlockPos(anchor.getX() - 2, baseY + 2, minZ), Blocks.LANTERN.defaultBlockState(), 3);
        snapshot.setBlock(level, new BlockPos(anchor.getX() + 2, baseY + 2, minZ), Blocks.LANTERN.defaultBlockState(), 3);
        snapshot.setBlock(level, new BlockPos(anchor.getX() - 2, baseY + 2, maxZ), Blocks.LANTERN.defaultBlockState(), 3);
        snapshot.setBlock(level, new BlockPos(anchor.getX() + 2, baseY + 2, maxZ), Blocks.LANTERN.defaultBlockState(), 3);

        // 2. Central Mess Campfire
        BlockPos campFirePos = new BlockPos(anchor.getX(), baseY + 1, anchor.getZ() + 7);
        snapshot.setBlock(level, campFirePos, Blocks.CAMPFIRE.defaultBlockState(), 3);
        placedBlocks.add(campFirePos.immutable());
        snapshot.setBlock(level, campFirePos.east(2), log, 3);
        snapshot.setBlock(level, campFirePos.west(2), log, 3);

        // 3. Four Outer Soldier Tents
        int[][] tentOffsets = {{-9, -9}, {9, -9}, {-9, 8}, {9, 8}};
        for (int[] toff : tentOffsets) {
            int tx = anchor.getX() + toff[0];
            int tz = anchor.getZ() + toff[1];
            buildSmallSoldierTent(level, new BlockPos(tx, baseY, tz), grayWool, planks, fence, snapshot, placedBlocks);
            patrolSpawns.add(new BlockPos(tx, baseY + 1, tz));
        }

        // 4. Large Central Command Pavilion Tent (10x8 footprint)
        int pavMinX = anchor.getX() - 5;
        int pavMaxX = anchor.getX() + 4;
        int pavMinZ = anchor.getZ() - 6;
        int pavMaxZ = anchor.getZ() + 1;

        // Clear interior air of Command Pavilion
        for (int x = pavMinX; x <= pavMaxX; x++) {
            for (int z = pavMinZ; z <= pavMaxZ; z++) {
                for (int y = baseY + 1; y <= baseY + 6; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.getBlockState(p).isAir()) {
                        snapshot.setBlock(level, p, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                // Floor
                BlockPos fPos = new BlockPos(x, baseY, z);
                snapshot.setBlock(level, fPos, planks, 3);
                placedBlocks.add(fPos.immutable());
            }
        }

        // Pavilion Corner Posts
        for (int x : new int[]{pavMinX, pavMaxX}) {
            for (int z : new int[]{pavMinZ, pavMaxZ}) {
                for (int y = baseY + 1; y <= baseY + 4; y++) {
                    BlockPos postPos = new BlockPos(x, y, z);
                    snapshot.setBlock(level, postPos, log, 3);
                    placedBlocks.add(postPos.immutable());
                }
            }
        }

        // Pavilion Striped Wool Roof (A-frame peak from Y+3 to Y+5)
        for (int x = pavMinX; x <= pavMaxX; x++) {
            boolean isRedStripe = (Math.abs(x - anchor.getX()) % 2 == 0);
            BlockState roofWool = isRedStripe ? redWool : blackWool;

            for (int z = pavMinZ; z <= pavMaxZ; z++) {
                int distFromCenterZ = Math.min(Math.abs(z - (pavMinZ + 3)), Math.abs(z - (pavMinZ + 4)));
                int roofY = baseY + 5 - Math.min(distFromCenterZ, 2);
                BlockPos rPos = new BlockPos(x, roofY, z);
                snapshot.setBlock(level, rPos, roofWool, 3);
                placedBlocks.add(rPos.immutable());
            }
        }

        // Inside Command Pavilion: Commander War Desk (Salvage Prize)
        BlockPos warDeskPos = new BlockPos(anchor.getX(), baseY + 1, anchor.getZ() - 4);
        snapshot.setBlock(level, warDeskPos, deskBlock, 3);
        placedBlocks.add(warDeskPos.immutable());

        // Decorative props inside pavilion
        snapshot.setBlock(level, warDeskPos.above(), Blocks.LANTERN.defaultBlockState(), 3);
        snapshot.setBlock(level, warDeskPos.west(), Blocks.BOOKSHELF.defaultBlockState(), 3);
        snapshot.setBlock(level, warDeskPos.east(), Blocks.CARTOGRAPHY_TABLE.defaultBlockState(), 3);

        // Spawns
        BlockPos commanderSpawn = new BlockPos(anchor.getX(), baseY + 1, anchor.getZ() - 2);
        bodyguardSpawns.add(new BlockPos(anchor.getX() - 2, baseY + 1, anchor.getZ() - 2));
        bodyguardSpawns.add(new BlockPos(anchor.getX() + 2, baseY + 1, anchor.getZ() - 2));

        return new OfficerCampResult(placedBlocks, warDeskPos, commanderSpawn, bodyguardSpawns, patrolSpawns);
    }

    private static void buildSmallSoldierTent(
            ServerLevel level,
            BlockPos origin,
            BlockState wool,
            BlockState planks,
            BlockState fence,
            MissionSiteSnapshot snapshot,
            List<BlockPos> placedBlocks
    ) {
        int baseY = origin.getY();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                // Clear interior
                for (int dy = 1; dy <= 3; dy++) {
                    BlockPos ap = origin.offset(dx, dy, dz);
                    if (!level.getBlockState(ap).isAir()) {
                        snapshot.setBlock(level, ap, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                // Tent roof peak along X=0
                int roofHeight = 3 - Math.abs(dx);
                if (roofHeight > 0) {
                    BlockPos rp = origin.offset(dx, roofHeight, dz);
                    snapshot.setBlock(level, rp, wool, 3);
                    placedBlocks.add(rp.immutable());
                }
            }
        }
        // Small prop inside
        BlockPos chestPos = origin.offset(0, 1, 1);
        snapshot.setBlock(level, chestPos, Blocks.BARREL.defaultBlockState(), 3);
        placedBlocks.add(chestPos.immutable());
    }

    public record ArtilleryBatteryResult(
            List<BlockPos> structureBlocks,
            List<BlockPos> mortarPositions
    ) {}

    private static final ResourceLocation MORTAR_EMPLACEMENT_TEMPLATE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_mortar_emplacement");

    /**
     * Places the native NBT Pillager Heavy Mortar Emplacement structure template.
     * Placed centered at the anchor with a -5 block vertical offset to bed foundations underground.
     */
    public static ArtilleryBatteryResult buildArtilleryBattery(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> structureBlocks = new ArrayList<>();
        List<BlockPos> mortarPositions = new ArrayList<>();

        StructureTemplateManager manager = level.getStructureManager();
        Optional<StructureTemplate> templateOpt = manager.get(MORTAR_EMPLACEMENT_TEMPLATE);
        if (templateOpt.isEmpty() || templateOpt.get().getSize().getX() <= 0) {
            Warfront.LOGGER.error("[Warfront] Required mortar emplacement template '{}' not found!", MORTAR_EMPLACEMENT_TEMPLATE);
            return new ArtilleryBatteryResult(structureBlocks, mortarPositions);
        }

        StructureTemplate template = templateOpt.get();
        Vec3i size = template.getSize();
        int sizeX = size.getX();
        int sizeY = size.getY();
        int sizeZ = size.getZ();

        // Center template horizontally around anchor, -5 vertical offset so lower chambers sit underground
        int halfX = sizeX / 2;
        int halfZ = sizeZ / 2;
        BlockPos origin = new BlockPos(anchor.getX() - halfX, anchor.getY() - 5, anchor.getZ() - halfZ);

        // Record original block states across structure volume for clean rollback
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockPos p = origin.offset(x, y, z);
                    snapshot.recordOriginalState(p, level.getBlockState(p));
                }
            }
        }

        // Place template
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(Rotation.NONE)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false);

        boolean placed = template.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_ALL);
        if (!placed) {
            Warfront.LOGGER.error("[Warfront] Failed to place mortar emplacement template at {}", origin);
            return new ArtilleryBatteryResult(structureBlocks, mortarPositions);
        }

        // Scan placed volume for all placed blocks and mortar positions
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockPos p = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(p);
                    if (!state.isAir()) {
                        structureBlocks.add(p.immutable());
                        if (state.is(WarfrontBlocks.MORTAR.get())) {
                            mortarPositions.add(p.immutable());
                        }
                    }
                }
            }
        }

        Warfront.LOGGER.info("[Warfront] Placed Mortar Emplacement (size {}x{}x{}) at {}. Located {} active mortar blocks.",
                sizeX, sizeY, sizeZ, origin, mortarPositions.size());

        return new ArtilleryBatteryResult(structureBlocks, mortarPositions);
    }

    public record WarHornTowerResult(
            List<BlockPos> structureBlocks,
            BlockPos fuseRelayPos,
            BlockPos hornBellPos
    ) {}

    /**
     * Builds a 14-block tall Dark Oak signal mast topped with a crow's nest,
     * housing the Signal Fuse Relay (sabotage target) and Acoustic Horn Bell (salvage prize).
     */
    public static WarHornTowerResult buildWarHornTower(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placedBlocks = new ArrayList<>();

        BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
        BlockState fence = Blocks.DARK_OAK_FENCE.defaultBlockState();
        BlockState planks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState fuseBlock = WarfrontBlocks.SIGNAL_FUSE_RELAY.get().defaultBlockState();
        BlockState bellBlock = WarfrontBlocks.ACOUSTIC_HORN_BELL.get().defaultBlockState();

        int baseY = anchor.getY();
        int towerHeight = 14;
        int summitY = baseY + towerHeight;

        // 1. Four corner posts (3x3 base)
        int[] dOffsets = {-1, 1};
        for (int dx : dOffsets) {
            for (int dz : dOffsets) {
                int px = anchor.getX() + dx;
                int pz = anchor.getZ() + dz;

                // Stepped foundation down to solid ground
                BlockPos under = new BlockPos(px, baseY - 1, pz);
                int d = 0;
                while (d < 8 && (level.getBlockState(under).isAir() || level.getBlockState(under).liquid())) {
                    snapshot.setBlock(level, under, log, 3);
                    placedBlocks.add(under.immutable());
                    under = under.below();
                    d++;
                }

                // Vertical corner posts
                for (int y = baseY; y <= summitY; y++) {
                    BlockPos lp = new BlockPos(px, y, pz);
                    snapshot.setBlock(level, lp, log, 3);
                    placedBlocks.add(lp.immutable());
                }
            }
        }

        // Cross-bracing with fences at intermediate heights
        for (int y : new int[]{baseY + 3, baseY + 7, baseY + 11}) {
            for (int dx : dOffsets) {
                BlockPos bp = new BlockPos(anchor.getX() + dx, y, anchor.getZ());
                snapshot.setBlock(level, bp, fence, 3);
                placedBlocks.add(bp.immutable());
            }
            for (int dz : dOffsets) {
                BlockPos bp = new BlockPos(anchor.getX(), y, anchor.getZ() + dz);
                snapshot.setBlock(level, bp, fence, 3);
                placedBlocks.add(bp.immutable());
            }
        }

        // 2. Central access ladder up the north pillar
        for (int y = baseY + 1; y <= summitY; y++) {
            BlockPos ladderPos = new BlockPos(anchor.getX(), y, anchor.getZ() - 1);
            BlockState ladderState = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
            snapshot.setBlock(level, ladderPos, ladderState, 3);
            placedBlocks.add(ladderPos.immutable());
        }

        // 3. Summit Crow's Nest Platform (5x5) at summitY
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos p = new BlockPos(anchor.getX() + dx, summitY, anchor.getZ() + dz);

                // Platform floor
                snapshot.setBlock(level, p, planks, 3);
                placedBlocks.add(p.immutable());

                // Clear headroom
                for (int dy = 1; dy <= 4; dy++) {
                    BlockPos ap = p.above(dy);
                    if (!level.getBlockState(ap).isAir()) {
                        snapshot.setBlock(level, ap, Blocks.AIR.defaultBlockState(), 3);
                    }
                }

                // Outer perimeter railing
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                    BlockPos railPos = p.above();
                    snapshot.setBlock(level, railPos, fence, 3);
                    placedBlocks.add(railPos.immutable());
                }
            }
        }

        // 4. Summit Targets
        // Signal Fuse Relay (Volatile Sabotage Target)
        BlockPos fusePos = new BlockPos(anchor.getX() - 1, summitY + 1, anchor.getZ());
        snapshot.setBlock(level, fusePos, fuseBlock, 3);
        placedBlocks.add(fusePos.immutable());

        // Acoustic Horn Bell (Salvage Prize)
        BlockPos bellPos = new BlockPos(anchor.getX() + 1, summitY + 1, anchor.getZ());
        snapshot.setBlock(level, bellPos, bellBlock, 3);
        placedBlocks.add(bellPos.immutable());

        // Summit Lantern
        snapshot.setBlock(level, bellPos.above(), Blocks.LANTERN.defaultBlockState(), 3);

        return new WarHornTowerResult(placedBlocks, fusePos, bellPos);
    }
}
