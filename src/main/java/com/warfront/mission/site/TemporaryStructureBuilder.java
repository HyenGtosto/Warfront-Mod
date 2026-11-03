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

    private static final ResourceLocation COMMANDER_CAMP_TEMPLATE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_commander_camp");

    public record OfficerCampResult(
            List<BlockPos> structureBlocks,
            BlockPos warDeskPos,
            List<BlockPos> warDeskPositions,
            BlockPos commanderSpawnPos,
            List<BlockPos> bodyguardSpawnPositions,
            List<BlockPos> outerPatrolSpawnPositions
    ) {}

    /**
     * Places the native NBT Pillager Commander Camp structure template (27x13x31).
     * Placed centered at the anchor with a -2 block vertical offset to bed foundation blocks underground.
     * Populates soldier tent chests and commander double chests while leaving prop barrels empty.
     */
    public static OfficerCampResult buildOfficerCamp(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> structureBlocks = new ArrayList<>();
        List<BlockPos> warDeskPositions = new ArrayList<>();
        List<BlockPos> bodyguardSpawns = new ArrayList<>();
        List<BlockPos> patrolSpawns = new ArrayList<>();

        StructureTemplateManager manager = level.getStructureManager();
        Optional<StructureTemplate> templateOpt = manager.get(COMMANDER_CAMP_TEMPLATE);
        if (templateOpt.isEmpty() || templateOpt.get().getSize().getX() <= 0) {
            Warfront.LOGGER.error("[Warfront] Required commander camp template '{}' not found!", COMMANDER_CAMP_TEMPLATE);
            return new OfficerCampResult(structureBlocks, anchor, warDeskPositions, anchor, bodyguardSpawns, patrolSpawns);
        }

        StructureTemplate template = templateOpt.get();
        Vec3i size = template.getSize();
        int sizeX = size.getX();
        int sizeY = size.getY();
        int sizeZ = size.getZ();

        // 27x13x31 footprint. Center template horizontally around anchor, -2 vertical offset
        int halfX = sizeX / 2;
        int halfZ = sizeZ / 2;
        BlockPos origin = new BlockPos(anchor.getX() - halfX, anchor.getY() - 2, anchor.getZ() - halfZ);

        // Record pre-existing non-air block states across structure volume for clean rollback
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockPos p = origin.offset(x, y, z);
                    BlockState existing = level.getBlockState(p);
                    if (!existing.isAir()) {
                        snapshot.recordOriginalState(p, existing);
                    }
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
            Warfront.LOGGER.error("[Warfront] Failed to place commander camp template at {}", origin);
            return new OfficerCampResult(structureBlocks, anchor, warDeskPositions, anchor, bodyguardSpawns, patrolSpawns);
        }

        // Scan placed volume for all placed blocks, chests, and commander desks
        List<BlockPos> soldierChestPositions = new ArrayList<>();
        List<BlockPos> commanderChestPositions = new ArrayList<>();

        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockPos p = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(p);
                    if (!state.isAir()) {
                        structureBlocks.add(p.immutable());
                        snapshot.recordOriginalState(p, Blocks.AIR.defaultBlockState());
                        if (state.is(WarfrontBlocks.COMMANDER_WAR_DESK.get())) {
                            warDeskPositions.add(p.immutable());
                        } else if (state.is(Blocks.CHEST)) {
                            // Big tent is at local z >= 20; soldier tents are at z < 20
                            if (z >= 20) {
                                commanderChestPositions.add(p.immutable());
                            } else {
                                soldierChestPositions.add(p.immutable());
                            }
                        }
                    }
                }
            }
        }

        // Populate soldier chests (3 tents) with military supplies
        net.minecraft.util.RandomSource random = level.getRandom();
        for (BlockPos cp : soldierChestPositions) {
            if (level.getBlockEntity(cp) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                populateSoldierChest(chest, random);
            }
        }

        // Populate commander double chests in the big tent with high-value tactical loot
        for (BlockPos cp : commanderChestPositions) {
            if (level.getBlockEntity(cp) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
                populateCommanderChest(chest, random);
            }
        }
        // Prop barrels in build are intentionally left empty with no loot

        // Midpoint of War Desk
        BlockPos warDeskMid;
        if (!warDeskPositions.isEmpty()) {
            int sumX = 0, sumY = 0, sumZ = 0;
            for (BlockPos p : warDeskPositions) {
                sumX += p.getX();
                sumY += p.getY();
                sumZ += p.getZ();
            }
            warDeskMid = new BlockPos(sumX / warDeskPositions.size(), sumY / warDeskPositions.size(), sumZ / warDeskPositions.size());
        } else {
            warDeskMid = origin.offset(13, 2, 24);
        }

        // Commander spawn pos: inside big tent right in front of tactical war desk
        BlockPos commanderSpawn = origin.offset(13, 2, 22);

        // Bodyguards: flanking inside the big tent
        bodyguardSpawns.add(origin.offset(10, 2, 23));
        bodyguardSpawns.add(origin.offset(16, 2, 23));

        // Outer patrol spawns: near soldier tents & central campfire
        patrolSpawns.add(origin.offset(13, 2, 6));  // North soldier tent
        patrolSpawns.add(origin.offset(6, 2, 13));  // West soldier tent
        patrolSpawns.add(origin.offset(20, 2, 13)); // East soldier tent
        patrolSpawns.add(origin.offset(13, 2, 15)); // Central campfire

        Warfront.LOGGER.info("[Warfront] Placed Commander Camp (size {}x{}x{}) at {}. Located {} war desk blocks.",
                sizeX, sizeY, sizeZ, origin, warDeskPositions.size());

        return new OfficerCampResult(structureBlocks, warDeskMid, warDeskPositions, commanderSpawn, bodyguardSpawns, patrolSpawns);
    }

    private static void populateSoldierChest(net.minecraft.world.level.block.entity.ChestBlockEntity chest, net.minecraft.util.RandomSource random) {
        chest.clearContent();
        chest.setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW, 16 + random.nextInt(17)));
        chest.setItem(4, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 4 + random.nextInt(5)));
        chest.setItem(11, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 3 + random.nextInt(6)));
        chest.setItem(15, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 1 + random.nextInt(4)));
        if (random.nextBoolean()) {
            chest.setItem(20, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CROSSBOW));
        }
    }

    private static void populateCommanderChest(net.minecraft.world.level.block.entity.ChestBlockEntity chest, net.minecraft.util.RandomSource random) {
        chest.clearContent();
        chest.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_APPLE, 1 + random.nextInt(2)));
        chest.setItem(3, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 1 + random.nextInt(3)));
        chest.setItem(5, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 5 + random.nextInt(9)));
        chest.setItem(10, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_BLOCK, 1 + random.nextInt(2)));
        chest.setItem(13, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TNT, 2 + random.nextInt(3)));
        chest.setItem(16, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GUNPOWDER, 6 + random.nextInt(7)));
        chest.setItem(22, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EXPERIENCE_BOTTLE, 4 + random.nextInt(5)));
        if (random.nextFloat() < 0.7F) {
            chest.setItem(24, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));
        }
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

        // Record pre-existing non-air block states across structure volume for clean rollback
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockPos p = origin.offset(x, y, z);
                    BlockState existing = level.getBlockState(p);
                    if (!existing.isAir()) {
                        snapshot.recordOriginalState(p, existing);
                    }
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
                        snapshot.recordOriginalState(p, Blocks.AIR.defaultBlockState());
                        if (state.is(WarfrontBlocks.MORTAR.get())) {
                            mortarPositions.add(p.immutable());
                            // Clear vertical airspace above mortar so firing line is completely clear
                            for (int yUp = 1; yUp <= 8; yUp++) {
                                BlockPos skyP = p.above(yUp);
                                if (!level.getBlockState(skyP).isAir()) {
                                    snapshot.recordOriginalState(skyP, level.getBlockState(skyP));
                                    snapshot.setBlock(level, skyP, Blocks.AIR.defaultBlockState(), 3);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Perimeter walkability: ensure perimeter edges don't form impassable 2+ block vertical dirt walls
        for (int x = 0; x < sizeX; x++) {
            for (int z : new int[]{0, sizeZ - 1}) {
                BlockPos rim = origin.offset(x, sizeY - 1, z);
                BlockPos outer = (z == 0) ? rim.north() : rim.south();
                int groundBelow = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, outer.getX(), outer.getZ());
                if (rim.getY() - groundBelow >= 2) {
                    // Place gentle step down so mobs can traverse
                    BlockPos step = outer.atY(rim.getY() - 1);
                    if (level.getBlockState(step).isAir()) {
                        snapshot.recordOriginalState(step, level.getBlockState(step));
                        snapshot.setBlock(level, step, Blocks.COARSE_DIRT.defaultBlockState(), 3);
                        structureBlocks.add(step.immutable());
                    }
                }
            }
        }

        Warfront.LOGGER.info("[Warfront] Placed Mortar Emplacement (size {}x{}x{}) at {}. Located {} active mortar blocks.",
                sizeX, sizeY, sizeZ, origin, mortarPositions.size());

        return new ArtilleryBatteryResult(structureBlocks, mortarPositions);
    }

    private static final ResourceLocation BROADCAST_TOWER_TEMPLATE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_broadcast_tower");

    public record WarHornTowerResult(
            List<BlockPos> structureBlocks,
            List<BlockPos> fuseRelayPositions,
            List<BlockPos> hornBellPositions
    ) {
        public BlockPos fuseRelayPos() {
            return fuseRelayPositions.isEmpty() ? null : fuseRelayPositions.get(0);
        }

        public BlockPos hornBellPos() {
            return hornBellPositions.isEmpty() ? null : hornBellPositions.get(0);
        }
    }

    /**
     * Places the native NBT Pillager Broadcast Warhorn Tower structure template (7x30x7 footprint).
     * Placed directly on the anchor at ground level with 0 vertical offset.
     * Contains 4 Signal Fuse Relays (sabotage targets) and 4 Acoustic Horn Bells (salvage prizes).
     */
    public static WarHornTowerResult buildWarHornTower(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> structureBlocks = new ArrayList<>();
        List<BlockPos> fusePositions = new ArrayList<>();
        List<BlockPos> hornBellPositions = new ArrayList<>();

        StructureTemplateManager manager = level.getStructureManager();
        Optional<StructureTemplate> templateOpt = manager.get(BROADCAST_TOWER_TEMPLATE);
        if (templateOpt.isEmpty() || templateOpt.get().getSize().getX() <= 0) {
            Warfront.LOGGER.error("[Warfront] Required broadcast tower template '{}' not found!", BROADCAST_TOWER_TEMPLATE);
            return new WarHornTowerResult(structureBlocks, fusePositions, hornBellPositions);
        }

        StructureTemplate template = templateOpt.get();
        Vec3i size = template.getSize();
        int sizeX = size.getX();
        int sizeY = size.getY();
        int sizeZ = size.getZ();

        // 7x30x7 footprint. Centered on anchor horizontally, 0 vertical offset
        int halfX = sizeX / 2;
        int halfZ = sizeZ / 2;
        BlockPos origin = new BlockPos(anchor.getX() - halfX, anchor.getY(), anchor.getZ() - halfZ);

        // Record pre-existing non-air block states across structure volume for clean rollback
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockPos p = origin.offset(x, y, z);
                    BlockState existing = level.getBlockState(p);
                    if (!existing.isAir()) {
                        snapshot.recordOriginalState(p, existing);
                    }
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
            Warfront.LOGGER.error("[Warfront] Failed to place broadcast tower template at {}", origin);
            return new WarHornTowerResult(structureBlocks, fusePositions, hornBellPositions);
        }

        // Scan placed volume for all placed blocks, fuses, and acoustic bells
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockPos p = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(p);
                    if (!state.isAir()) {
                        structureBlocks.add(p.immutable());
                        snapshot.recordOriginalState(p, Blocks.AIR.defaultBlockState());
                        if (state.is(WarfrontBlocks.SIGNAL_FUSE_RELAY.get())) {
                            fusePositions.add(p.immutable());
                        } else if (state.is(WarfrontBlocks.ACOUSTIC_HORN_BELL.get())) {
                            hornBellPositions.add(p.immutable());
                        }
                    }
                }
            }
        }

        Warfront.LOGGER.info("[Warfront] Placed Warhorn Tower (size {}x{}x{}) at {}. Located {} fuses, {} horn bells.",
                sizeX, sizeY, sizeZ, origin, fusePositions.size(), hornBellPositions.size());

        return new WarHornTowerResult(structureBlocks, fusePositions, hornBellPositions);
    }

    // =========================================================================
    // Biome-Specific Procedural Temporary Structures
    // =========================================================================

    public record GenericStructureResult(
            List<BlockPos> structureBlocks,
            List<BlockPos> targetPositions
    ) {}

    public static void buildFoundationColumn(ServerLevel level, int x, int z, int startY, BlockState state, List<BlockPos> placed, MissionSiteSnapshot snapshot) {
        BlockPos pos = new BlockPos(x, startY, z);
        int depth = 0;
        while (depth < 8 && (level.getBlockState(pos).isAir() || level.getBlockState(pos).liquid())) {
            snapshot.setBlock(level, pos, state, 3);
            placed.add(pos.immutable());
            pos = pos.below();
            depth++;
        }
    }

    // --- 1. Highlands & Peaks (Mountain & Hills) ---

    public static GenericStructureResult buildMountainLookoutPost(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState stone = Blocks.COBBLESTONE.defaultBlockState();
        BlockState fence = Blocks.DARK_OAK_FENCE.defaultBlockState();
        BlockState planks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState core = WarfrontBlocks.MISSION_TARGET_CORE.get().defaultBlockState();

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, stone, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, (Math.abs(x) == 3 || Math.abs(z) == 3) ? stone : planks, 3);
                placed.add(fl);

                if (Math.abs(x) == 3 || Math.abs(z) == 3) {
                    if (!(x == 0 && z == -3)) { // Entrance gap
                        BlockPos fPos = new BlockPos(bx + x, by + 1, bz + z);
                        snapshot.setBlock(level, fPos, fence, 3);
                        placed.add(fPos);
                    }
                }
            }
        }

        BlockPos t1 = new BlockPos(bx - 1, by + 1, bz + 1);
        BlockPos t2 = new BlockPos(bx + 1, by + 1, bz + 1);
        snapshot.setBlock(level, t1, core, 3);
        snapshot.setBlock(level, t2, core, 3);
        placed.add(t1);
        placed.add(t2);
        targets.add(t1);
        targets.add(t2);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildMountainPassBarricade(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState core = WarfrontBlocks.MISSION_TARGET_CORE.get().defaultBlockState();

        for (int x = -4; x <= 4; x++) {
            for (int z = -1; z <= 1; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, cobble, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                for (int y = 0; y <= 2; y++) {
                    BlockPos p = new BlockPos(bx + x, by + y, bz + z);
                    snapshot.setBlock(level, p, (Math.abs(x) == 4 || y == 0) ? cobble : log, 3);
                    placed.add(p);
                }
            }
        }

        BlockPos cPos = new BlockPos(bx, by + 1, bz);
        snapshot.setBlock(level, cPos, core, 3);
        placed.add(cPos);
        targets.add(cPos);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildSummitBeaconBastion(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState fuse = WarfrontBlocks.SIGNAL_FUSE_RELAY.get().defaultBlockState();

        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (x * x + z * z <= 18) {
                    buildFoundationColumn(level, bx + x, bz + z, by - 1, stone, placed, snapshot);
                    for (int y = 0; y <= 5; y++) {
                        snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                    }
                    BlockPos fl = new BlockPos(bx + x, by, bz + z);
                    snapshot.setBlock(level, fl, stone, 3);
                    placed.add(fl);

                    boolean isWall = x * x + z * z >= 10;
                    if (isWall && !(z == -4 && Math.abs(x) <= 1)) {
                        for (int y = 1; y <= 3; y++) {
                            BlockPos w = new BlockPos(bx + x, by + y, bz + z);
                            snapshot.setBlock(level, w, stone, 3);
                            placed.add(w);
                        }
                    }
                }
            }
        }

        BlockPos center = new BlockPos(bx, by + 1, bz);
        snapshot.setBlock(level, center, fuse, 3);
        placed.add(center);
        targets.add(center);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildMountainRidgeBattery(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState ammo = WarfrontBlocks.AMMUNITION_CRATE_TARGET.get().defaultBlockState();
        BlockState mortar = WarfrontBlocks.MORTAR_CANNON_MOUNT.get().defaultBlockState();

        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, cobble, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, cobble, 3);
                placed.add(fl);

                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    BlockPos w = new BlockPos(bx + x, by + 1, bz + z);
                    snapshot.setBlock(level, w, cobble, 3);
                    placed.add(w);
                }
            }
        }

        BlockPos mPos = new BlockPos(bx, by + 1, bz);
        BlockPos a1 = new BlockPos(bx - 2, by + 1, bz);
        BlockPos a2 = new BlockPos(bx + 2, by + 1, bz);
        snapshot.setBlock(level, mPos, mortar, 3);
        snapshot.setBlock(level, a1, ammo, 3);
        snapshot.setBlock(level, a2, ammo, 3);
        placed.add(mPos); placed.add(a1); placed.add(a2);
        targets.add(mPos); targets.add(a1); targets.add(a2);

        return new GenericStructureResult(placed, targets);
    }

    // --- 2. Dense Woodlands (Forest & Taiga) ---

    public static GenericStructureResult buildForestLoggingDepot(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState core = WarfrontBlocks.MISSION_TARGET_CORE.get().defaultBlockState();

        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, planks, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, planks, 3);
                placed.add(fl);
            }
        }

        // 3 Sawbench / Timber Stack target positions
        BlockPos t1 = new BlockPos(bx - 2, by + 1, bz - 1);
        BlockPos t2 = new BlockPos(bx + 2, by + 1, bz - 1);
        BlockPos t3 = new BlockPos(bx, by + 1, bz + 2);
        snapshot.setBlock(level, t1, core, 3);
        snapshot.setBlock(level, t2, core, 3);
        snapshot.setBlock(level, t3, core, 3);
        placed.add(t1); placed.add(t2); placed.add(t3);
        targets.add(t1); targets.add(t2); targets.add(t3);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildForestHuntingStash(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState ammo = WarfrontBlocks.AMMUNITION_CRATE_TARGET.get().defaultBlockState();

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, log, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, planks, 3);
                placed.add(fl);
                if (Math.abs(x) == 3 || Math.abs(z) == 3) {
                    if (!(z == -3 && x == 0)) {
                        BlockPos w = new BlockPos(bx + x, by + 1, bz + z);
                        snapshot.setBlock(level, w, log, 3);
                        placed.add(w);
                    }
                }
            }
        }

        BlockPos a1 = new BlockPos(bx - 1, by + 1, bz + 1);
        BlockPos a2 = new BlockPos(bx + 1, by + 1, bz + 1);
        snapshot.setBlock(level, a1, ammo, 3);
        snapshot.setBlock(level, a2, ammo, 3);
        placed.add(a1); placed.add(a2);
        targets.add(a1); targets.add(a2);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildForestCanopyBlinds(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
        BlockState planks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState fence = Blocks.DARK_OAK_FENCE.defaultBlockState();
        BlockState core = WarfrontBlocks.MISSION_TARGET_CORE.get().defaultBlockState();

        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, planks, placed, snapshot);
                for (int y = 0; y <= 7; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, planks, 3);
                placed.add(fl);
                if (Math.abs(x) == 5 || Math.abs(z) == 5) {
                    BlockPos p = new BlockPos(bx + x, by + 1, bz + z);
                    snapshot.setBlock(level, p, fence, 3);
                    placed.add(p);
                }
            }
        }

        // Twin watch towers at opposite corners (Y+4)
        int[][] towerOffsets = {{-3, -3}, {3, 3}};
        for (int[] off : towerOffsets) {
            int tx = bx + off[0];
            int tz = bz + off[1];
            for (int y = 1; y <= 4; y++) {
                BlockPos pillar = new BlockPos(tx, by + y, tz);
                snapshot.setBlock(level, pillar, log, 3);
                placed.add(pillar);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos plat = new BlockPos(tx + dx, by + 4, tz + dz);
                    snapshot.setBlock(level, plat, planks, 3);
                    placed.add(plat);
                }
            }
        }

        BlockPos cPos = new BlockPos(bx, by + 1, bz);
        snapshot.setBlock(level, cPos, core, 3);
        placed.add(cPos);
        targets.add(cPos);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildForestCommandCamp(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
        BlockState fence = Blocks.DARK_OAK_FENCE.defaultBlockState();
        BlockState desk = WarfrontBlocks.COMMANDER_WAR_DESK.get().defaultBlockState();

        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, log, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, Blocks.COARSE_DIRT.defaultBlockState(), 3);
                placed.add(fl);
                if (Math.abs(x) == 5 || Math.abs(z) == 5) {
                    if (!(z == -5 && Math.abs(x) <= 1)) {
                        BlockPos f = new BlockPos(bx + x, by + 1, bz + z);
                        snapshot.setBlock(level, f, fence, 3);
                        placed.add(f);
                    }
                }
            }
        }

        BlockPos dPos = new BlockPos(bx, by + 1, bz);
        snapshot.setBlock(level, dPos, desk, 3);
        placed.add(dPos);
        targets.add(dPos);

        return new GenericStructureResult(placed, targets);
    }

    // --- 3. Wetlands & Murk (Swamp & Jungle) ---

    public static GenericStructureResult buildSwampStrandedBarge(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState ammo = WarfrontBlocks.AMMUNITION_CRATE_TARGET.get().defaultBlockState();

        for (int x = -5; x <= 5; x++) {
            for (int z = -2; z <= 2; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, log, placed, snapshot);
                for (int y = 0; y <= 3; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, (Math.abs(z) == 2 || Math.abs(x) == 5) ? log : planks, 3);
                placed.add(fl);
            }
        }

        BlockPos t1 = new BlockPos(bx - 3, by + 1, bz);
        BlockPos t2 = new BlockPos(bx, by + 1, bz);
        BlockPos t3 = new BlockPos(bx + 3, by + 1, bz);
        snapshot.setBlock(level, t1, ammo, 3);
        snapshot.setBlock(level, t2, ammo, 3);
        snapshot.setBlock(level, t3, ammo, 3);
        placed.add(t1); placed.add(t2); placed.add(t3);
        targets.add(t1); targets.add(t2); targets.add(t3);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildSwampFuelStill(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState ammo = WarfrontBlocks.AMMUNITION_CRATE_TARGET.get().defaultBlockState();

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, planks, placed, snapshot);
                for (int y = 0; y <= 3; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, planks, 3);
                placed.add(fl);
            }
        }

        BlockPos a1 = new BlockPos(bx - 1, by + 1, bz);
        BlockPos a2 = new BlockPos(bx + 1, by + 1, bz);
        snapshot.setBlock(level, a1, ammo, 3);
        snapshot.setBlock(level, a2, ammo, 3);
        placed.add(a1); placed.add(a2);
        targets.add(a1); targets.add(a2);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildSwampStiltRedoubt(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState log = Blocks.DARK_OAK_LOG.defaultBlockState();
        BlockState planks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState fence = Blocks.DARK_OAK_FENCE.defaultBlockState();
        BlockState core = WarfrontBlocks.MISSION_TARGET_CORE.get().defaultBlockState();

        int stiltY = by + 3;
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                boolean isStiltLeg = (Math.abs(x) == 4 || x == 0) && (Math.abs(z) == 4 || z == 0);
                if (isStiltLeg) {
                    buildFoundationColumn(level, bx + x, bz + z, stiltY - 1, log, placed, snapshot);
                    for (int y = 0; y < 3; y++) {
                        BlockPos leg = new BlockPos(bx + x, by + y, bz + z);
                        snapshot.setBlock(level, leg, log, 3);
                        placed.add(leg);
                    }
                }
                for (int y = 3; y <= 7; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos deck = new BlockPos(bx + x, stiltY, bz + z);
                snapshot.setBlock(level, deck, planks, 3);
                placed.add(deck);

                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    if (!(z == -4 && Math.abs(x) <= 1)) {
                        BlockPos f = new BlockPos(bx + x, stiltY + 1, bz + z);
                        snapshot.setBlock(level, f, fence, 3);
                        placed.add(f);
                    }
                }
            }
        }

        BlockPos cPos = new BlockPos(bx, stiltY + 1, bz);
        snapshot.setBlock(level, cPos, core, 3);
        placed.add(cPos);
        targets.add(cPos);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildSwampIroncladHold(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState mud = Blocks.MUD_BRICKS.defaultBlockState();
        BlockState ammo = WarfrontBlocks.AMMUNITION_CRATE_TARGET.get().defaultBlockState();

        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, mud, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, mud, 3);
                placed.add(fl);
                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    BlockPos berm = new BlockPos(bx + x, by + 1, bz + z);
                    snapshot.setBlock(level, berm, mud, 3);
                    placed.add(berm);
                }
            }
        }

        BlockPos a1 = new BlockPos(bx - 2, by + 1, bz);
        BlockPos a2 = new BlockPos(bx + 2, by + 1, bz);
        snapshot.setBlock(level, a1, ammo, 3);
        snapshot.setBlock(level, a2, ammo, 3);
        placed.add(a1); placed.add(a2);
        targets.add(a1); targets.add(a2);

        return new GenericStructureResult(placed, targets);
    }

    // --- 4. Arid Wastes & Canyons (Desert & Badlands) ---

    public static GenericStructureResult buildDesertRoadCheckpoint(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState sand = Blocks.CUT_SANDSTONE.defaultBlockState();
        BlockState core = WarfrontBlocks.MISSION_TARGET_CORE.get().defaultBlockState();

        for (int x = -4; x <= 4; x++) {
            for (int z = -2; z <= 2; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, sand, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, sand, 3);
                placed.add(fl);
                if (Math.abs(x) == 4 || (Math.abs(x) >= 2 && Math.abs(z) == 2)) {
                    for (int y = 1; y <= 2; y++) {
                        BlockPos w = new BlockPos(bx + x, by + y, bz + z);
                        snapshot.setBlock(level, w, sand, 3);
                        placed.add(w);
                    }
                }
            }
        }

        BlockPos cPos = new BlockPos(bx, by + 1, bz);
        snapshot.setBlock(level, cPos, core, 3);
        placed.add(cPos);
        targets.add(cPos);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildDesertCisternDepot(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState sand = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        BlockState ammo = WarfrontBlocks.AMMUNITION_CRATE_TARGET.get().defaultBlockState();

        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, sand, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, sand, 3);
                placed.add(fl);
                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    if (!(z == -4 && Math.abs(x) <= 1)) {
                        BlockPos w = new BlockPos(bx + x, by + 1, bz + z);
                        snapshot.setBlock(level, w, sand, 3);
                        placed.add(w);
                    }
                }
            }
        }

        BlockPos t1 = new BlockPos(bx - 2, by + 1, bz);
        BlockPos t2 = new BlockPos(bx + 2, by + 1, bz);
        BlockPos t3 = new BlockPos(bx, by + 1, bz + 2);
        snapshot.setBlock(level, t1, ammo, 3);
        snapshot.setBlock(level, t2, ammo, 3);
        snapshot.setBlock(level, t3, ammo, 3);
        placed.add(t1); placed.add(t2); placed.add(t3);
        targets.add(t1); targets.add(t2); targets.add(t3);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildBadlandsMesaBattery(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState redSand = Blocks.CUT_RED_SANDSTONE.defaultBlockState();
        BlockState ballista = WarfrontBlocks.PILLAGER_BALLISTA.get().defaultBlockState();

        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, redSand, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, redSand, 3);
                placed.add(fl);
                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    BlockPos parapet = new BlockPos(bx + x, by + 1, bz + z);
                    snapshot.setBlock(level, parapet, redSand, 3);
                    placed.add(parapet);
                }
            }
        }

        BlockPos b1 = new BlockPos(bx - 2, by + 1, bz);
        BlockPos b2 = new BlockPos(bx + 2, by + 1, bz);
        snapshot.setBlock(level, b1, ballista, 3);
        snapshot.setBlock(level, b2, ballista, 3);
        placed.add(b1); placed.add(b2);
        targets.add(b1); targets.add(b2);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildDesertCanyonStrongpoint(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState sand = Blocks.CUT_SANDSTONE.defaultBlockState();
        BlockState desk = WarfrontBlocks.COMMANDER_WAR_DESK.get().defaultBlockState();

        for (int x = -5; x <= 5; x++) {
            for (int z = -4; z <= 4; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, sand, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, sand, 3);
                placed.add(fl);
                if (Math.abs(x) == 5 || Math.abs(z) == 4) {
                    if (!(z == -4 && Math.abs(x) <= 1)) {
                        for (int y = 1; y <= 2; y++) {
                            BlockPos w = new BlockPos(bx + x, by + y, bz + z);
                            snapshot.setBlock(level, w, sand, 3);
                            placed.add(w);
                        }
                    }
                }
            }
        }

        BlockPos dPos = new BlockPos(bx, by + 1, bz);
        snapshot.setBlock(level, dPos, desk, 3);
        placed.add(dPos);
        targets.add(dPos);

        return new GenericStructureResult(placed, targets);
    }

    // --- 5. Open Grasslands (Plains & Savanna) ---

    public static GenericStructureResult buildPlainsScoutPaddock(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        BlockState ammo = WarfrontBlocks.AMMUNITION_CRATE_TARGET.get().defaultBlockState();

        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, Blocks.DIRT.defaultBlockState(), placed, snapshot);
                for (int y = 0; y <= 3; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    if (!(z == -4 && x == 0)) {
                        BlockPos f = new BlockPos(bx + x, by + 1, bz + z);
                        snapshot.setBlock(level, f, fence, 3);
                        placed.add(f);
                    }
                }
            }
        }

        BlockPos a1 = new BlockPos(bx - 2, by + 1, bz);
        BlockPos a2 = new BlockPos(bx + 2, by + 1, bz);
        snapshot.setBlock(level, a1, ammo, 3);
        snapshot.setBlock(level, a2, ammo, 3);
        placed.add(a1); placed.add(a2);
        targets.add(a1); targets.add(a2);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildPlainsSignalMast(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState core = WarfrontBlocks.MISSION_TARGET_CORE.get().defaultBlockState();

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, cobble, placed, snapshot);
                for (int y = 0; y <= 7; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, cobble, 3);
                placed.add(fl);
            }
        }

        for (int y = 1; y <= 6; y++) {
            BlockPos mast = new BlockPos(bx, by + y, bz);
            snapshot.setBlock(level, mast, log, 3);
            placed.add(mast);
        }

        BlockPos cPos = new BlockPos(bx, by + 1, bz + 1);
        snapshot.setBlock(level, cPos, core, 3);
        placed.add(cPos);
        targets.add(cPos);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildPlainsTrenchRedoubt(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState core = WarfrontBlocks.MISSION_TARGET_CORE.get().defaultBlockState();

        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, cobble, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, planks, 3);
                placed.add(fl);

                if (Math.abs(x) == 5 || Math.abs(z) == 5) {
                    BlockPos p = new BlockPos(bx + x, by + 1, bz + z);
                    snapshot.setBlock(level, p, cobble, 3);
                    placed.add(p);
                }
            }
        }

        BlockPos cPos = new BlockPos(bx, by + 1, bz);
        snapshot.setBlock(level, cPos, core, 3);
        placed.add(cPos);
        targets.add(cPos);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildPlainsFieldCommand(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        BlockState desk = WarfrontBlocks.COMMANDER_WAR_DESK.get().defaultBlockState();

        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, cobble, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, cobble, 3);
                placed.add(fl);
                if (Math.abs(x) == 5 || Math.abs(z) == 5) {
                    if (!(z == -5 && Math.abs(x) <= 1)) {
                        BlockPos f = new BlockPos(bx + x, by + 1, bz + z);
                        snapshot.setBlock(level, f, fence, 3);
                        placed.add(f);
                    }
                }
            }
        }

        BlockPos dPos = new BlockPos(bx, by + 1, bz);
        snapshot.setBlock(level, dPos, desk, 3);
        placed.add(dPos);
        targets.add(dPos);

        return new GenericStructureResult(placed, targets);
    }

    // --- 6. Glacial Arctic (Tundra) ---

    public static GenericStructureResult buildTundraSupplyCache(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState ammo = WarfrontBlocks.AMMUNITION_CRATE_TARGET.get().defaultBlockState();

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, planks, placed, snapshot);
                for (int y = 0; y <= 3; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, planks, 3);
                placed.add(fl);
            }
        }

        BlockPos a1 = new BlockPos(bx - 1, by + 1, bz);
        BlockPos a2 = new BlockPos(bx + 1, by + 1, bz);
        snapshot.setBlock(level, a1, ammo, 3);
        snapshot.setBlock(level, a2, ammo, 3);
        placed.add(a1); placed.add(a2);
        targets.add(a1); targets.add(a2);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildTundraFrozenWatchpost(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState stone = Blocks.COBBLESTONE.defaultBlockState();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        BlockState core = WarfrontBlocks.MISSION_TARGET_CORE.get().defaultBlockState();

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, stone, placed, snapshot);
                for (int y = 0; y <= 7; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, stone, 3);
                placed.add(fl);
                if (Math.abs(x) == 3 && Math.abs(z) == 3) {
                    for (int y = 1; y <= 5; y++) {
                        BlockPos pillar = new BlockPos(bx + x, by + y, bz + z);
                        snapshot.setBlock(level, pillar, log, 3);
                        placed.add(pillar);
                    }
                }
            }
        }

        BlockPos cPos = new BlockPos(bx, by + 1, bz);
        snapshot.setBlock(level, cPos, core, 3);
        placed.add(cPos);
        targets.add(cPos);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildTundraMortarPit(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState snow = Blocks.SNOW_BLOCK.defaultBlockState();
        BlockState ammo = WarfrontBlocks.AMMUNITION_CRATE_TARGET.get().defaultBlockState();
        BlockState mortar = WarfrontBlocks.MORTAR_CANNON_MOUNT.get().defaultBlockState();

        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, cobble, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, cobble, 3);
                placed.add(fl);
                if (Math.abs(x) == 4 || Math.abs(z) == 4) {
                    BlockPos p = new BlockPos(bx + x, by + 1, bz + z);
                    snapshot.setBlock(level, p, snow, 3);
                    placed.add(p);
                }
            }
        }

        BlockPos mPos = new BlockPos(bx, by + 1, bz);
        BlockPos a1 = new BlockPos(bx - 2, by + 1, bz);
        BlockPos a2 = new BlockPos(bx + 2, by + 1, bz);
        snapshot.setBlock(level, mPos, mortar, 3);
        snapshot.setBlock(level, a1, ammo, 3);
        snapshot.setBlock(level, a2, ammo, 3);
        placed.add(mPos); placed.add(a1); placed.add(a2);
        targets.add(mPos); targets.add(a1); targets.add(a2);

        return new GenericStructureResult(placed, targets);
    }

    public static GenericStructureResult buildTundraGarrisonRedoubt(ServerLevel level, BlockPos anchor, MissionSiteSnapshot snapshot) {
        List<BlockPos> placed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        int bx = anchor.getX();
        int by = anchor.getY();
        int bz = anchor.getZ();

        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        BlockState stone = Blocks.COBBLESTONE.defaultBlockState();
        BlockState desk = WarfrontBlocks.COMMANDER_WAR_DESK.get().defaultBlockState();

        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                buildFoundationColumn(level, bx + x, bz + z, by - 1, stone, placed, snapshot);
                for (int y = 0; y <= 4; y++) {
                    snapshot.setBlock(level, new BlockPos(bx + x, by + y, bz + z), Blocks.AIR.defaultBlockState(), 3);
                }
                BlockPos fl = new BlockPos(bx + x, by, bz + z);
                snapshot.setBlock(level, fl, stone, 3);
                placed.add(fl);
                if (Math.abs(x) == 5 || Math.abs(z) == 5) {
                    if (!(z == -5 && Math.abs(x) <= 1)) {
                        for (int y = 1; y <= 2; y++) {
                            BlockPos w = new BlockPos(bx + x, by + y, bz + z);
                            snapshot.setBlock(level, w, log, 3);
                            placed.add(w);
                        }
                    }
                }
            }
        }

        BlockPos dPos = new BlockPos(bx, by + 1, bz);
        snapshot.setBlock(level, dPos, desk, 3);
        placed.add(dPos);
        targets.add(dPos);

        return new GenericStructureResult(placed, targets);
    }
}
