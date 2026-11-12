package com.warfront.region.base.hq;

import com.warfront.Warfront;
import com.warfront.region.base.PillagerHeadquartersGenerator;
import com.warfront.region.base.garrison.PillagerHQGarrisonDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic iron-bar portcullis controller for Pillager Headquarters Fortress gates.
 *
 * <p>Mechanics:</p>
 * <ul>
 *   <li>Governs North Gate ($Z \in \{2, 4\}$) and South Gate ($Z \in \{88, 90\}$),
 *       each comprising 32 {@code minecraft:iron_bars} blocks across two parallel barrier layers.</li>
 *   <li>When an authorized HQ patrol squad approaches to enter or exit, the controller sets intact
 *       iron bars to {@code AIR} so mob pathfinding proceeds unhindered.</li>
 *   <li>Once the squad passes through, the controller restores the cached iron bar {@link BlockState}s
 *       with heavy portcullis slam sound effects.</li>
 *   <li><b>Integrity Rule:</b> If $\ge 25\%$ of a gate's iron bars ($\ge 8$ blocks) are destroyed by a player,
 *       the portcullis mechanism is permanently broken ({@code isBreached = true}) and bars will never reappear.</li>
 * </ul>
 */
public class HQGatePortcullisController {

    public static final int TOTAL_BARS_PER_GATE = 32;
    public static final int BREACH_THRESHOLD_BARS = 8; // 25% of 32
    public static final long AUTO_CLOSE_TIMEOUT_TICKS = 300L; // 15 seconds

    public enum GateId {
        NORTH,
        SOUTH
    }

    public static class GateState {
        private final GateId id;
        private final List<BlockPos> barPositions;
        private final BlockPos centerPos;
        private boolean breached = false;
        private boolean open = false;
        private long openGameTime = 0L;
        private UUID activeSquadId = null;
        private final Map<BlockPos, BlockState> storedBars = new HashMap<>();

        public GateState(GateId id, List<BlockPos> barPositions, BlockPos centerPos) {
            this.id = id;
            this.barPositions = barPositions;
            this.centerPos = centerPos;
        }

        public GateId getId() {
            return id;
        }

        public boolean isBreached() {
            return breached;
        }

        public boolean isOpen() {
            return open;
        }

        public BlockPos getCenterPos() {
            return centerPos;
        }

        public List<BlockPos> getBarPositions() {
            return barPositions;
        }
    }

    private static final Map<BlockPos, HQGatePortcullisController> CONTROLLERS = new ConcurrentHashMap<>();

    private final BlockPos hqAnchor;
    private final Map<GateId, GateState> gates = new EnumMap<>(GateId.class);

    public HQGatePortcullisController(ServerLevel level, BlockPos hqAnchor) {
        this.hqAnchor = hqAnchor;

        boolean isEastWest = PillagerHeadquartersGenerator.isEastWest(hqAnchor);
        BlockPos shellBoxMin = PillagerHeadquartersGenerator.getShellBoxMin(hqAnchor);
        Direction targetShellFacing = isEastWest ? Direction.SOUTH : Direction.WEST;
        Rotation shellRot = PillagerHeadquartersGenerator.getRotationBetween(
                PillagerHeadquartersGenerator.SHELL_INITIAL_FACING, targetShellFacing);
        BlockPos shellOrigin = PillagerHeadquartersGenerator.getRotatedOrigin(
                shellBoxMin, PillagerHQGarrisonDefinition.SHELL_SIZE, shellRot);

        // Initialize North Gate (Z = 2 and 4, X = 24..27, Y = 1..4)
        List<BlockPos> northPositions = calculateWorldPositions(shellOrigin, shellRot, GateId.NORTH);
        BlockPos northCenter = shellOrigin.offset(
                StructureTemplate.transform(new BlockPos(25, 2, 3), Mirror.NONE, shellRot, BlockPos.ZERO));
        gates.put(GateId.NORTH, new GateState(GateId.NORTH, northPositions, northCenter));

        // Initialize South Gate (Z = 88 and 90, X = 24..27, Y = 1..4)
        List<BlockPos> southPositions = calculateWorldPositions(shellOrigin, shellRot, GateId.SOUTH);
        BlockPos southCenter = shellOrigin.offset(
                StructureTemplate.transform(new BlockPos(25, 2, 89), Mirror.NONE, shellRot, BlockPos.ZERO));
        gates.put(GateId.SOUTH, new GateState(GateId.SOUTH, southPositions, southCenter));
    }

    private static List<BlockPos> calculateWorldPositions(BlockPos shellOrigin, Rotation shellRot, GateId id) {
        List<BlockPos> list = new ArrayList<>(TOTAL_BARS_PER_GATE);
        int[] zLayers = (id == GateId.NORTH) ? new int[]{2, 4} : new int[]{88, 90};

        for (int z : zLayers) {
            for (int x = 24; x <= 27; x++) {
                for (int y = 1; y <= 4; y++) {
                    BlockPos local = new BlockPos(x, y, z);
                    BlockPos world = shellOrigin.offset(
                            StructureTemplate.transform(local, Mirror.NONE, shellRot, BlockPos.ZERO));
                    list.add(world.immutable());
                }
            }
        }
        return list;
    }

    public static HQGatePortcullisController getOrCreate(ServerLevel level, BlockPos hqAnchor) {
        return CONTROLLERS.computeIfAbsent(hqAnchor.immutable(), a -> new HQGatePortcullisController(level, a));
    }

    public static boolean isGatePositionManaged(BlockPos pos) {
        for (HQGatePortcullisController controller : CONTROLLERS.values()) {
            for (GateState state : controller.gates.values()) {
                if (state.barPositions.contains(pos)) {
                    if (state.open || state.breached) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void cleanup() {
        CONTROLLERS.clear();
    }

    public GateState getGate(GateId id) {
        return gates.get(id);
    }

    /**
     * Evaluates structural integrity against the 25% destruction threshold.
     */
    public boolean checkIntegrity(ServerLevel level, GateId id) {
        GateState state = gates.get(id);
        if (state == null) return false;
        if (state.breached) return true;

        if (state.open) {
            int missingWhenOpened = TOTAL_BARS_PER_GATE - state.storedBars.size();
            if (missingWhenOpened >= BREACH_THRESHOLD_BARS) {
                state.breached = true;
                return true;
            }
            return false;
        }

        int intactCount = 0;
        for (BlockPos pos : state.barPositions) {
            if (level.hasChunkAt(pos)) {
                BlockState bs = level.getBlockState(pos);
                if (bs.is(Blocks.IRON_BARS)) {
                    intactCount++;
                }
            }
        }

        int missing = TOTAL_BARS_PER_GATE - intactCount;
        if (missing >= BREACH_THRESHOLD_BARS) {
            state.breached = true;
            Warfront.LOGGER.info("[HQ GATE] {} gate at anchor {} BREACHED! ({} / {} iron bars destroyed, >= 25% threshold met). Portcullis mechanism disabled.",
                    id, hqAnchor, missing, TOTAL_BARS_PER_GATE);
            level.playSound(null, state.centerPos, SoundEvents.ANVIL_DESTROY, SoundSource.BLOCKS, 0.9F, 0.7F);
            level.playSound(null, state.centerPos, SoundEvents.IRON_GOLEM_DAMAGE, SoundSource.HOSTILE, 1.0F, 0.6F);
            return true;
        }

        return false;
    }

    /**
     * Requests portcullis opening so patrol squads can pathfind through.
     */
    public boolean requestOpen(ServerLevel level, GateId id, UUID squadId) {
        GateState state = gates.get(id);
        if (state == null) return false;

        if (state.breached) {
            return true; // Path is permanently breached open
        }

        if (state.open) {
            state.openGameTime = level.getGameTime();
            state.activeSquadId = squadId;
            return true;
        }

        if (checkIntegrity(level, id)) {
            return true;
        }

        state.storedBars.clear();
        for (BlockPos pos : state.barPositions) {
            if (level.hasChunkAt(pos)) {
                BlockState bs = level.getBlockState(pos);
                if (bs.is(Blocks.IRON_BARS)) {
                    state.storedBars.put(pos, bs);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        state.open = true;
        state.openGameTime = level.getGameTime();
        state.activeSquadId = squadId;

        level.playSound(null, state.centerPos, SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, 1.2F, 0.6F);
        level.playSound(null, state.centerPos, SoundEvents.CHAIN_FALL, SoundSource.BLOCKS, 1.0F, 0.8F);
        return true;
    }

    /**
     * Requests portcullis closure and restores iron bars once squads have passed through.
     */
    public boolean requestClose(ServerLevel level, GateId id, UUID squadId) {
        GateState state = gates.get(id);
        if (state == null) return false;

        if (state.breached) {
            state.open = false;
            state.storedBars.clear();
            return false;
        }

        if (!state.open) {
            return false;
        }

        for (Map.Entry<BlockPos, BlockState> entry : state.storedBars.entrySet()) {
            BlockPos pos = entry.getKey();
            if (level.hasChunkAt(pos)) {
                level.setBlock(pos, entry.getValue(), Block.UPDATE_ALL);
            }
        }

        state.storedBars.clear();
        state.open = false;
        state.activeSquadId = null;

        level.playSound(null, state.centerPos, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.2F, 0.6F);
        level.playSound(null, state.centerPos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.7F, 0.5F);

        checkIntegrity(level, id);
        return true;
    }

    /**
     * Periodic tick handling auto-close safety timeouts and passive integrity polling.
     */
    public void tick(ServerLevel level) {
        long gameTime = level.getGameTime();
        for (GateState state : gates.values()) {
            if (state.open) {
                if (gameTime - state.openGameTime > AUTO_CLOSE_TIMEOUT_TICKS) {
                    requestClose(level, state.id, state.activeSquadId);
                }
            } else if (!state.breached && gameTime % 40L == 0L) {
                checkIntegrity(level, state.id);
            }
        }
    }
}
