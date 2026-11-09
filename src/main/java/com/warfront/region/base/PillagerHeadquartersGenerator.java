package com.warfront.region.base;

import com.warfront.Warfront;
import com.warfront.mission.MissionProfile;
import com.warfront.mission.MissionType;
import com.warfront.region.RegionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Native NBT template generator for the Pillager Headquarters (Medium Base) with dynamic in-code rotation.
 *
 * <p>Architectural layout & rotation mechanics:</p>
 * <ul>
 *   <li>Single canonical NBT per structure: {@code pillager_hq_shell.nbt} (52x93) and three 40x40 facility templates.</li>
 *   <li>Dynamic Rotation Engine: Calculates the exact {@link Rotation} and closed-form origin offset from each
 *       structure's initial authored facing to its target courtyard facing across East-West and North-South layouts.</li>
 *   <li>Dynamic Column Heightmap Excavation: Carves interior mountain rock to open sky prior to placement.</li>
 *   <li>Stepped Cobblestone Foundation: Extends downward to solid ground so structures never float on slopes.</li>
 *   <li>Full {@link #getPristineBlocks} support applying block state rotations for base protection and instant repair.</li>
 * </ul>
 */
public class PillagerHeadquartersGenerator implements BaseBuildingGenerator {

    public static final int SIZE_X = 93;
    public static final int SIZE_Z = 93;
    public static final int HEIGHT = 26;
    private static final int MAX_FOUNDATION_DEPTH = 36;
    private static final int SET_BLOCK_FLAGS = Block.UPDATE_CLIENTS;

    // Single canonical NBT template identifiers
    public static final ResourceLocation TEMPLATE_SHELL = ResourceLocation.fromNamespaceAndPath(
            Warfront.MOD_ID, "base/headquarters/pillager_hq_shell");
    public static final ResourceLocation TEMPLATE_KEEP = ResourceLocation.fromNamespaceAndPath(
            Warfront.MOD_ID, "base/headquarters/pillager_hq_commander_keep");
    public static final ResourceLocation TEMPLATE_INTEL = ResourceLocation.fromNamespaceAndPath(
            Warfront.MOD_ID, "base/headquarters/pillager_hq_intel_bunker");
    public static final ResourceLocation TEMPLATE_SUPPLY = ResourceLocation.fromNamespaceAndPath(
            Warfront.MOD_ID, "base/headquarters/pillager_hq_supply_depot");

    // Configurable initial authored facings of the single NBT files (main gate / front entrance direction)
    public static final Direction SHELL_INITIAL_FACING = Direction.SOUTH;
    public static final Direction KEEP_INITIAL_FACING = Direction.SOUTH;
    public static final Direction INTEL_INITIAL_FACING = Direction.SOUTH;
    public static final Direction SUPPLY_INITIAL_FACING = Direction.SOUTH;

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

    public static ResourceLocation getTemplateForMission(MissionType type) {
        if (type == null) return TEMPLATE_KEEP;
        return switch (type) {
            case BASE_KILL_COMMANDER -> TEMPLATE_KEEP;
            case BASE_DESTROY_INTEL -> TEMPLATE_INTEL;
            case BASE_DESTROY_SUPPLIES -> TEMPLATE_SUPPLY;
            default -> TEMPLATE_KEEP;
        };
    }

    public static Direction getInitialFacingForMission(MissionType type) {
        if (type == null) return KEEP_INITIAL_FACING;
        return switch (type) {
            case BASE_KILL_COMMANDER -> KEEP_INITIAL_FACING;
            case BASE_DESTROY_INTEL -> INTEL_INITIAL_FACING;
            case BASE_DESTROY_SUPPLIES -> SUPPLY_INITIAL_FACING;
            default -> KEEP_INITIAL_FACING;
        };
    }

    /**
     * Computes the clockwise {@link Rotation} required to rotate a structure from {@code initialFacing}
     * so that its entrance points toward {@code targetFacing}.
     */
    public static Rotation getRotationBetween(Direction initialFacing, Direction targetFacing) {
        int initIdx = initialFacing.get2DDataValue(); // S=0, W=1, N=2, E=3
        int targIdx = targetFacing.get2DDataValue();
        int stepsClockwise = Math.floorMod(targIdx - initIdx, 4);
        return switch (stepsClockwise) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /**
     * Closed-form origin offset transformation ensuring a template of unrotated dimensions {@code size}
     * rotated by {@code rotation} around relative pivot (0,0,0) lands with its bounding box minimum
     * corner exactly at {@code targetBoxMinCorner}.
     */
    public static BlockPos getRotatedOrigin(BlockPos targetBoxMinCorner, Vec3i size, Rotation rotation) {
        return switch (rotation) {
            case NONE -> targetBoxMinCorner;
            case CLOCKWISE_90 -> targetBoxMinCorner.offset(size.getZ() - 1, 0, 0);
            case CLOCKWISE_180 -> targetBoxMinCorner.offset(size.getX() - 1, 0, size.getZ() - 1);
            case COUNTERCLOCKWISE_90 -> targetBoxMinCorner.offset(0, 0, size.getX() - 1);
        };
    }

    @Override
    public boolean place(BasePlacementContext context) {
        ServerLevel level = context.level();
        BlockPos anchor = context.anchor();
        if (level == null || anchor == null) {
            return false;
        }

        int localX = Math.floorMod(anchor.getX(), RegionData.REGION_SIZE_BLOCKS);
        int localZ = Math.floorMod(anchor.getZ(), RegionData.REGION_SIZE_BLOCKS);
        boolean isEastWest = Math.abs(localX - 64) > Math.abs(localZ - 64);

        int subABit;
        int subBBit;
        int minX, maxX, minZ, maxZ;
        BlockPos shellBoxMin;
        BlockPos slotABoxMin;
        BlockPos slotBBoxMin;
        Direction targetShellFacing;
        Direction targetSlotAFacing;
        Direction targetSlotBFacing;

        int baseY = anchor.getY();

        if (isEastWest) {
            // 52 wide (X) by 93 long (Z)
            minX = anchor.getX() - 26;
            maxX = anchor.getX() + 25;
            minZ = anchor.getZ() - 46;
            maxZ = anchor.getZ() + 46;
            shellBoxMin = new BlockPos(minX, baseY, minZ);
            slotABoxMin = shellBoxMin.offset(6, 0, 6);   // North Wing (40x40)
            slotBBoxMin = shellBoxMin.offset(6, 0, 47);  // South Wing (40x40)

            targetShellFacing = Direction.SOUTH;
            targetSlotAFacing = Direction.SOUTH; // North wing faces South into courtyard
            targetSlotBFacing = Direction.NORTH; // South wing faces North into courtyard

            if (localX >= 64) {
                subABit = 1;
                subBBit = 3;
            } else {
                subABit = 0;
                subBBit = 2;
            }
        } else {
            // 93 wide (X) by 52 long (Z)
            minX = anchor.getX() - 46;
            maxX = anchor.getX() + 46;
            minZ = anchor.getZ() - 26;
            maxZ = anchor.getZ() + 25;
            shellBoxMin = new BlockPos(minX, baseY, minZ);
            slotABoxMin = shellBoxMin.offset(6, 0, 6);   // West Wing (40x40)
            slotBBoxMin = shellBoxMin.offset(47, 0, 6);  // East Wing (40x40)

            targetShellFacing = Direction.WEST;
            targetSlotAFacing = Direction.EAST;  // West wing faces East into courtyard
            targetSlotBFacing = Direction.WEST;  // East wing faces West into courtyard

            if (localZ >= 64) {
                subABit = 2;
                subBBit = 3;
            } else {
                subABit = 0;
                subBBit = 1;
            }
        }

        // Query authoritative shared 2-wing contract
        MissionProfile.HQWingContract contract = MissionProfile.getHQWingContract(context.worldSeed(), context.regionX(), context.regionZ(), anchor);
        MissionType missionA = contract.getTypeForBit(subABit);
        MissionType missionB = contract.getTypeForBit(subBBit);
        if (missionA == null) missionA = MissionType.BASE_KILL_COMMANDER;
        if (missionB == null) missionB = MissionType.BASE_DESTROY_INTEL;

        ResourceLocation facilityAId = getTemplateForMission(missionA);
        ResourceLocation facilityBId = getTemplateForMission(missionB);

        StructureTemplateManager manager = level.getStructureManager();
        Optional<StructureTemplate> shellOpt = manager.get(TEMPLATE_SHELL);
        Optional<StructureTemplate> facAOpt = manager.get(facilityAId);
        Optional<StructureTemplate> facBOpt = manager.get(facilityBId);

        if (shellOpt.isEmpty() || facAOpt.isEmpty() || facBOpt.isEmpty()) {
            Warfront.LOGGER.error("[Warfront] Headquarters template loading failed! shell: {}, facA ({}): {}, facB ({}): {}",
                    shellOpt.isPresent(), facilityAId, facAOpt.isPresent(), facilityBId, facBOpt.isPresent());
            return false;
        }

        StructureTemplate shellTemplate = shellOpt.get();
        StructureTemplate facATemplate = facAOpt.get();
        StructureTemplate facBTemplate = facBOpt.get();

        // Compute dynamic rotations and exact origins
        Rotation shellRot = getRotationBetween(SHELL_INITIAL_FACING, targetShellFacing);
        Rotation slotARot = getRotationBetween(getInitialFacingForMission(missionA), targetSlotAFacing);
        Rotation slotBRot = getRotationBetween(getInitialFacingForMission(missionB), targetSlotBFacing);

        BlockPos shellOrigin = getRotatedOrigin(shellBoxMin, shellTemplate.getSize(), shellRot);
        BlockPos slotAOrigin = getRotatedOrigin(slotABoxMin, facATemplate.getSize(), slotARot);
        BlockPos slotBOrigin = getRotatedOrigin(slotBBoxMin, facBTemplate.getSize(), slotBRot);

        // 1. Dynamic Column Heightmap Excavation: Carve interior mountain rock to open sky
        int interiorMinX = minX + 6;
        int interiorMaxX = maxX - 6;
        int interiorMinZ = minZ + 6;
        int interiorMaxZ = maxZ - 6;

        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for (int x = interiorMinX; x <= interiorMaxX; x++) {
            for (int z = interiorMinZ; z <= interiorMaxZ; z++) {
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int clearMaxY = Math.max(surfaceY, baseY + 20);
                for (int y = baseY + 1; y <= clearMaxY; y++) {
                    mpos.set(x, y, z);
                    BlockState state = level.getBlockState(mpos);
                    if (!state.isAir()) {
                        level.setBlock(mpos, Blocks.AIR.defaultBlockState(), SET_BLOCK_FLAGS);
                    }
                }
            }
        }

        // 2. Place Native Structure Templates with Computed Rotations
        StructurePlaceSettings shellSettings = new StructurePlaceSettings()
                .setRotation(shellRot)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false);
        StructurePlaceSettings slotASettings = new StructurePlaceSettings()
                .setRotation(slotARot)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false);
        StructurePlaceSettings slotBSettings = new StructurePlaceSettings()
                .setRotation(slotBRot)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false);

        boolean placedShell = shellTemplate.placeInWorld(level, shellOrigin, BlockPos.ZERO, shellSettings, level.getRandom(), Block.UPDATE_ALL);
        facATemplate.placeInWorld(level, slotAOrigin, BlockPos.ZERO, slotASettings, level.getRandom(), Block.UPDATE_ALL);
        facBTemplate.placeInWorld(level, slotBOrigin, BlockPos.ZERO, slotBSettings, level.getRandom(), Block.UPDATE_ALL);

        if (!placedShell) {
            Warfront.LOGGER.error("[Warfront] Failed to place HQ shell template at {}!", shellOrigin);
            return false;
        }

        // 3. Extend Stepped Foundation Downwards to Terrain
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        int subFloorY = baseY - 1;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                mpos.set(x, baseY, z);
                BlockState floor = level.getBlockState(mpos);
                if (!floor.isAir() && !floor.canBeReplaced()) {
                    for (int depth = 1; depth <= MAX_FOUNDATION_DEPTH; depth++) {
                        int downY = subFloorY - depth + 1;
                        if (downY < level.getMinBuildHeight() + 2) break;
                        mpos.set(x, downY, z);
                        BlockState current = level.getBlockState(mpos);
                        if (isLandfillPenetrable(current)) {
                            level.setBlock(mpos, cobble, SET_BLOCK_FLAGS);
                        } else {
                            break;
                        }
                    }
                }
            }
        }

        // 4. Generate Adaptive Entrance Stairs for Suspended Entrances
        generateAdaptiveStairs(level, anchor, baseY, isEastWest, null);

        Warfront.LOGGER.info("[Warfront] Placed modular Pillager HQ at {} (Orientation: {}, ShellRot: {}, SlotA: {} [{}], SlotB: {} [{}])",
                shellBoxMin, isEastWest ? "East-West" : "North-South", shellRot, missionA, slotARot, missionB, slotBRot);
        return true;
    }

    @Override
    public Map<BlockPos, BlockState> getPristineBlocks(ServerLevel level, BlockPos anchor) {
        if (level == null || anchor == null) {
            return Map.of();
        }

        int localX = Math.floorMod(anchor.getX(), RegionData.REGION_SIZE_BLOCKS);
        int localZ = Math.floorMod(anchor.getZ(), RegionData.REGION_SIZE_BLOCKS);
        boolean isEastWest = Math.abs(localX - 64) > Math.abs(localZ - 64);

        int rx = Math.floorDiv(anchor.getX(), RegionData.REGION_SIZE_BLOCKS);
        int rz = Math.floorDiv(anchor.getZ(), RegionData.REGION_SIZE_BLOCKS);

        int subABit = isEastWest ? ((localX >= 64) ? 1 : 0) : ((localZ >= 64) ? 2 : 0);
        int subBBit = isEastWest ? ((localX >= 64) ? 3 : 2) : ((localZ >= 64) ? 3 : 1);

        BlockPos shellBoxMin;
        BlockPos slotABoxMin;
        BlockPos slotBBoxMin;
        Direction targetShellFacing;
        Direction targetSlotAFacing;
        Direction targetSlotBFacing;

        int baseY = anchor.getY();

        if (isEastWest) {
            shellBoxMin = new BlockPos(anchor.getX() - 26, baseY, anchor.getZ() - 46);
            slotABoxMin = shellBoxMin.offset(6, 0, 6);
            slotBBoxMin = shellBoxMin.offset(6, 0, 47);
            targetShellFacing = Direction.SOUTH;
            targetSlotAFacing = Direction.SOUTH;
            targetSlotBFacing = Direction.NORTH;
        } else {
            shellBoxMin = new BlockPos(anchor.getX() - 46, baseY, anchor.getZ() - 26);
            slotABoxMin = shellBoxMin.offset(6, 0, 6);
            slotBBoxMin = shellBoxMin.offset(47, 0, 6);
            targetShellFacing = Direction.WEST;
            targetSlotAFacing = Direction.EAST;
            targetSlotBFacing = Direction.WEST;
        }

        MissionProfile.HQWingContract contract = MissionProfile.getHQWingContract(level.getSeed(), rx, rz, anchor);
        MissionType missionA = contract.getTypeForBit(subABit);
        MissionType missionB = contract.getTypeForBit(subBBit);
        if (missionA == null) missionA = MissionType.BASE_KILL_COMMANDER;
        if (missionB == null) missionB = MissionType.BASE_DESTROY_INTEL;

        ResourceLocation facilityAId = getTemplateForMission(missionA);
        ResourceLocation facilityBId = getTemplateForMission(missionB);

        Rotation shellRot = getRotationBetween(SHELL_INITIAL_FACING, targetShellFacing);
        Rotation slotARot = getRotationBetween(getInitialFacingForMission(missionA), targetSlotAFacing);
        Rotation slotBRot = getRotationBetween(getInitialFacingForMission(missionB), targetSlotBFacing);

        StructureTemplateManager manager = level.getStructureManager();
        Map<BlockPos, BlockState> result = new HashMap<>();

        manager.get(TEMPLATE_SHELL).ifPresent(t ->
                extractRotatedTemplateBlocks(t, getRotatedOrigin(shellBoxMin, t.getSize(), shellRot), shellRot, level, result));
        manager.get(facilityAId).ifPresent(t ->
                extractRotatedTemplateBlocks(t, getRotatedOrigin(slotABoxMin, t.getSize(), slotARot), slotARot, level, result));
        manager.get(facilityBId).ifPresent(t ->
                extractRotatedTemplateBlocks(t, getRotatedOrigin(slotBBoxMin, t.getSize(), slotBRot), slotBRot, level, result));

        // Include adaptive entrance stairs in pristine blueprint
        generateAdaptiveStairs(level, anchor, baseY, isEastWest, result);

        return result;
    }

    private void extractRotatedTemplateBlocks(
            StructureTemplate template,
            BlockPos rotatedOrigin,
            Rotation rotation,
            ServerLevel level,
            Map<BlockPos, BlockState> result
    ) {
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
                        paletteTag.getCompound(i)).rotate(rotation));
            }
        }

        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag b = blocksTag.getCompound(i);
            ListTag posTag = b.getList("pos", Tag.TAG_INT);
            int lx = posTag.getInt(0);
            int ly = posTag.getInt(1);
            int lz = posTag.getInt(2);
            int stateIdx = b.getInt("state");
            if (stateIdx >= 0 && stateIdx < palette.size()) {
                BlockState rotatedState = palette.get(stateIdx);
                if (!rotatedState.isAir()) {
                    BlockPos localPos = new BlockPos(lx, ly, lz);
                    BlockPos transformedOffset = StructureTemplate.transform(localPos, Mirror.NONE, rotation, BlockPos.ZERO);
                    BlockPos worldPos = rotatedOrigin.offset(transformedOffset);
                    if (worldPos.getY() >= rotatedOrigin.getY()) {
                        result.put(worldPos.immutable(), rotatedState);
                    }
                }
            }
        }
    }

    public static boolean isLandfillPenetrable(@Nullable BlockState state) {
        if (state == null || state.isAir()) {
            return true;
        }
        if (state.liquid() || state.canBeReplaced()) {
            return true;
        }
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
            return true;
        }
        if (state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)) {
            return true;
        }
        return false;
    }

    public record HQGate(
            BlockPos thresholdCenter,
            Direction outwardDir,
            Direction leftDir,
            Direction rightDir
    ) {}

    public static List<HQGate> getHQGates(BlockPos anchor, int baseY, boolean isEastWest) {
        List<HQGate> gates = new ArrayList<>(2);
        if (isEastWest) {
            // East-West: North Gate (-Z) and South Gate (+Z)
            gates.add(new HQGate(
                    new BlockPos(anchor.getX(), baseY, anchor.getZ() - 46),
                    Direction.NORTH,
                    Direction.WEST,
                    Direction.EAST
            ));
            gates.add(new HQGate(
                    new BlockPos(anchor.getX(), baseY, anchor.getZ() + 46),
                    Direction.SOUTH,
                    Direction.EAST,
                    Direction.WEST
            ));
        } else {
            // North-South: West Gate (-X) and East Gate (+X)
            gates.add(new HQGate(
                    new BlockPos(anchor.getX() - 46, baseY, anchor.getZ()),
                    Direction.WEST,
                    Direction.SOUTH,
                    Direction.NORTH
            ));
            gates.add(new HQGate(
                    new BlockPos(anchor.getX() + 46, baseY, anchor.getZ()),
                    Direction.EAST,
                    Direction.NORTH,
                    Direction.SOUTH
            ));
        }
        return gates;
    }

    private void generateAdaptiveStairs(
            ServerLevel level,
            BlockPos anchor,
            int baseY,
            boolean isEastWest,
            @Nullable Map<BlockPos, BlockState> pristineCollector
    ) {
        List<HQGate> gates = getHQGates(anchor, baseY, isEastWest);
        for (HQGate gate : gates) {
            buildAdaptiveStairsForGate(level, gate, baseY, pristineCollector);
        }
    }

    private void buildAdaptiveStairsForGate(
            ServerLevel level,
            HQGate gate,
            int baseY,
            @Nullable Map<BlockPos, BlockState> pristineCollector
    ) {
        BlockPos threshold = gate.thresholdCenter();
        Direction outward = gate.outwardDir();
        Direction left = gate.leftDir();
        Direction right = gate.rightDir();

        // 1. Suspension check: query terrain 2 blocks outward from the gate
        BlockPos checkPos = threshold.relative(outward, 2);
        int exteriorGroundY = getSolidTerrainSurfaceY(level, checkPos.getX(), checkPos.getZ(), baseY);
        if (baseY - exteriorGroundY <= 1) {
            // Gate is already on solid ground; no stairs needed
            return;
        }

        // 2. Diagonal terrain sampling: choose side with closest terrain elevation
        Direction chosenSide = left;
        int firstHitLeft = Integer.MAX_VALUE;
        int firstHitRight = Integer.MAX_VALUE;
        int minDropLeft = Integer.MAX_VALUE;
        int minDropRight = Integer.MAX_VALUE;

        for (int d = 1; d <= 16; d++) {
            BlockPos pL = threshold.relative(outward, d).relative(left, d);
            BlockPos pR = threshold.relative(outward, d).relative(right, d);
            int yL = getSolidTerrainSurfaceY(level, pL.getX(), pL.getZ(), baseY);
            int yR = getSolidTerrainSurfaceY(level, pR.getX(), pR.getZ(), baseY);

            int expectedY = baseY - d;
            if (expectedY <= yL && firstHitLeft == Integer.MAX_VALUE) {
                firstHitLeft = d;
            }
            if (expectedY <= yR && firstHitRight == Integer.MAX_VALUE) {
                firstHitRight = d;
            }
            minDropLeft = Math.min(minDropLeft, Math.max(0, baseY - yL));
            minDropRight = Math.min(minDropRight, Math.max(0, baseY - yR));
        }

        if (firstHitLeft < firstHitRight) {
            chosenSide = left;
        } else if (firstHitRight < firstHitLeft) {
            chosenSide = right;
        } else {
            chosenSide = (minDropLeft <= minDropRight) ? left : right;
        }

        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState stairs = Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, chosenSide.getOpposite());

        // 3. Flat Landing Pad: 3 blocks away from the gate, 3 blocks wide across the gate
        for (int stepOut = 1; stepOut <= 3; stepOut++) {
            for (int lat = -1; lat <= 1; lat++) {
                BlockPos padPos = threshold.relative(outward, stepOut).relative(left, lat);
                setBlockOrCollect(level, padPos, cobble, pristineCollector);
                for (int up = 1; up <= 4; up++) {
                    clearAir(level, padPos.above(up), pristineCollector);
                }

                // Stepped foundation beneath landing pad down to solid terrain
                for (int depth = 1; depth <= MAX_FOUNDATION_DEPTH; depth++) {
                    int downY = baseY - depth;
                    if (downY < level.getMinBuildHeight() + 2) break;
                    BlockPos fPos = new BlockPos(padPos.getX(), downY, padPos.getZ());
                    BlockState current = level.getBlockState(fPos);
                    if (isLandfillPenetrable(current)) {
                        setBlockOrCollect(level, fPos, cobble, pristineCollector);
                    } else {
                        break;
                    }
                }
            }
        }

        // 4. 90° Side Stairs Ramp:
        // Turns 90° towards chosenSide. The 3 blocks along outwardDir form the 3-block tread width.
        // Descends step-by-step (1 Y per step) in chosenSide with 1 block of foundation directly beneath.
        for (int s = 1; s <= 20; s++) {
            int stepY = baseY - s;
            boolean allTreadsGrounded = true;

            for (int stepOut = 1; stepOut <= 3; stepOut++) {
                BlockPos treadPos = threshold.relative(outward, stepOut).relative(chosenSide, 1 + s);
                int groundY = getSolidTerrainSurfaceY(level, treadPos.getX(), treadPos.getZ(), stepY);

                if (stepY > groundY) {
                    allTreadsGrounded = false;
                    BlockPos stairPos = new BlockPos(treadPos.getX(), stepY, treadPos.getZ());
                    setBlockOrCollect(level, stairPos, stairs, pristineCollector);

                    // Exactly 1 block foundation directly below each stair
                    BlockPos foundPos = stairPos.below(1);
                    setBlockOrCollect(level, foundPos, cobble, pristineCollector);

                    // 4 blocks air clearance above for player/squad headroom (clears trees/canopy)
                    for (int up = 1; up <= 4; up++) {
                        clearAir(level, stairPos.above(up), pristineCollector);
                    }
                } else if (stepY == groundY || stepY == groundY + 1) {
                    // Smooth transition block to natural terrain
                    BlockPos transitionPos = new BlockPos(treadPos.getX(), stepY, treadPos.getZ());
                    setBlockOrCollect(level, transitionPos, cobble, pristineCollector);
                    for (int up = 1; up <= 4; up++) {
                        clearAir(level, transitionPos.above(up), pristineCollector);
                    }
                }
            }

            if (allTreadsGrounded) {
                break;
            }
        }
    }

    public static int getSolidTerrainSurfaceY(@Nullable ServerLevel level, int x, int z, int startY) {
        if (level == null) return startY;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos(x, startY + 6, z);
        if (!level.hasChunkAt(mpos)) {
            return startY;
        }
        int minBuild = level.getMinBuildHeight() + 1;
        for (int y = startY + 6; y >= minBuild; y--) {
            mpos.setY(y);
            BlockState state = level.getBlockState(mpos);
            if (!isLandfillPenetrable(state)) {
                return y;
            }
        }
        return startY;
    }

    private static void setBlockOrCollect(
            @Nullable ServerLevel level,
            BlockPos pos,
            BlockState state,
            @Nullable Map<BlockPos, BlockState> pristineCollector
    ) {
        if (pristineCollector != null) {
            pristineCollector.put(pos.immutable(), state);
        }
        if (level != null && pristineCollector == null) {
            level.setBlock(pos, state, SET_BLOCK_FLAGS);
        }
    }

    private static void clearAir(
            @Nullable ServerLevel level,
            BlockPos pos,
            @Nullable Map<BlockPos, BlockState> pristineCollector
    ) {
        if (level != null && pristineCollector == null) {
            BlockState current = level.getBlockState(pos);
            if (!current.isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), SET_BLOCK_FLAGS);
            }
        }
    }
}
