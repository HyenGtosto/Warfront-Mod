package com.warfront.region.base;

import com.warfront.Warfront;
import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import com.warfront.region.generator.ProceduralRegionGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * Orchestrates persistent enemy base placement in the world.
 *
 * Performance and stability guarantees:
 * - Operates safely on ServerTickEvent.Post (never during ChunkEvent.Load) to prevent chunk loading deadlocks.
 * - Evaluates every 20 ticks (1 second) using O(1) cached raw region state.
 * - Places structures only when all chunks covering the footprint are already loaded by player view distance.
 * - Permanently records base placement in {@link RegionData} so each base is built exactly once.
 */
public final class BasePlacementManager {

    private static final int EVAL_INTERVAL_TICKS = 10; // Twice per second (0.5s)
    private static final double MAX_PLAYER_DISTANCE_BLOCKS = 350.0D; // Extended distance to eliminate client pop-in
    private static final int SCAN_REGION_RADIUS = 3; // 7x7 regions around player (-3..+3)

    private BasePlacementManager() {
    }

    /**
     * Ticking loop subscribed to server tick. Scans nearby regions around players for unplaced bases.
     */
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

            boolean placedAny = false;

            // Scan 7x7 regions around player (-3..+3)
            for (int drx = -SCAN_REGION_RADIUS; drx <= SCAN_REGION_RADIUS && !placedAny; drx++) {
                for (int drz = -SCAN_REGION_RADIUS; drz <= SCAN_REGION_RADIUS && !placedAny; drz++) {
                    int rx = playerRX + drx;
                    int rz = playerRZ + drz;
                    if (tryPlaceBase(level, regions, rx, rz, false, player.blockPosition())) {
                        placedAny = true; // Rate-limit to max 1 base placement per tick per player for silky performance
                    }
                }
            }
        }
    }

    /**
     * Public entry point for commands or programmatic placement.
     */
    public static boolean tryPlaceBase(ServerLevel level, int regionX, int regionZ, boolean force) {
        if (level == null) return false;
        RegionData regions = RegionData.get(level);
        return tryPlaceBase(level, regions, regionX, regionZ, force, null);
    }

    private static boolean tryPlaceBase(ServerLevel level, RegionData regions, int regionX, int regionZ, boolean force, BlockPos playerPos) {
        // 1. Fast O(1) check if base already placed
        if (!force && regions.isBasePlaced(regionX, regionZ)) {
            return false;
        }

        // 2. Retrieve region state using O(1) raw cache (never triggers heavy initial strength calculations)
        RegionData.RegionState state = regions.getSavedRegionState(regionX, regionZ);
        if (state == null) {
            state = ProceduralRegionGenerator.getInstance().generateRawRegionState(level, level.getSeed(), regionX, regionZ);
        }

        Faction owner = state.owner();
        if (owner != Faction.PILLAGER_CONQUERORS && owner != Faction.ZOMBIE_HORDE) {
            return false;
        }

        BaseType baseType = state.baseType();
        if (baseType == BaseType.NONE) {
            return false;
        }

        if (!force && state.basePlaced()) {
            return false;
        }

        // 3. Resolve anchor position
        BlockPos anchor = state.baseAnchor();
        if (anchor == null) {
            anchor = ProceduralRegionGenerator.getInstance()
                    .findPhysicalBaseAnchor(level, level.getSeed(), regionX, regionZ, baseType)
                    .orElse(null);
        }

        if (anchor == null) {
            // Never force fallback placement into rivers, beaches, or ocean
            return false;
        }

        // Verify anchor is strictly within region bounds
        int regMinX = regionX * RegionData.REGION_SIZE_BLOCKS;
        int regMaxX = regMinX + RegionData.REGION_SIZE_BLOCKS;
        int regMinZ = regionZ * RegionData.REGION_SIZE_BLOCKS;
        int regMaxZ = regMinZ + RegionData.REGION_SIZE_BLOCKS;
        if (anchor.getX() < regMinX || anchor.getX() >= regMaxX || anchor.getZ() < regMinZ || anchor.getZ() >= regMaxZ) {
            Warfront.LOGGER.error("[Warfront] Anchor {} is outside region ({}, {}) bounds [{}..{}, {}..{}]. Aborting placement.",
                    anchor, regionX, regionZ, regMinX, regMaxX, regMinZ, regMaxZ);
            return false;
        }

        // 4. Proximity check: only place if player is within range (unless forced)
        if (!force && playerPos != null) {
            double distSq = playerPos.distSqr(anchor);
            if (distSq > MAX_PLAYER_DISTANCE_BLOCKS * MAX_PLAYER_DISTANCE_BLOCKS) {
                return false;
            }
        }

        BaseBuildingGenerator generator = BaseBuildingRegistry.getGenerator(owner, baseType);

        int halfX = generator.getSizeX() / 2;
        int halfZ = generator.getSizeZ() / 2;
        int minChunkX = (anchor.getX() - halfX) >> 4;
        int maxChunkX = (anchor.getX() + halfX) >> 4;
        int minChunkZ = (anchor.getZ() - halfZ) >> 4;
        int maxChunkZ = (anchor.getZ() + halfZ) >> 4;

        // 5. Ensure all chunks covering footprint are loaded on server thread before client view distance renders them
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                level.getChunk(cx, cz);
            }
        }

        // 6. Refine surface ground anchor Y using actual loaded world heightmap
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, anchor.getX(), anchor.getZ());
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos(anchor.getX(), surfaceY, anchor.getZ());
        int maxScan = 10;
        while (maxScan-- > 0 && surfaceY > level.getMinBuildHeight() && (level.getBlockState(scanPos).isAir() || level.getBlockState(scanPos).is(BlockTags.LEAVES))) {
            surfaceY--;
            scanPos.setY(surfaceY);
        }
        // Prevent building on top of an existing monolith or cobblestone structure (unless forced)
        BlockPos checkGround = new BlockPos(anchor.getX(), surfaceY, anchor.getZ());
        if (!force && level.getBlockState(checkGround).is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) {
            Warfront.LOGGER.warn("[Warfront] Structure already exists at {} in region ({}, {}). Aborting placement to prevent double height stacking.",
                    checkGround, regionX, regionZ);
            regions.markBasePlaced(regionX, regionZ);
            return false;
        }

        if (surfaceY > level.getMinBuildHeight() && surfaceY < level.getMaxBuildHeight()) {
            anchor = new BlockPos(anchor.getX(), surfaceY, anchor.getZ());
        }

        // 7. Place the structure
        BasePlacementContext context = new BasePlacementContext(
                level,
                regionX,
                regionZ,
                owner,
                baseType,
                anchor,
                level.getSeed()
        );

        boolean placed = generator.place(context);
        if (placed) {
            regions.markBasePlaced(regionX, regionZ);
            Warfront.LOGGER.info("[Warfront] Placed persistent base ({}) for {} at {} in region ({}, {})",
                    baseType, owner.displayName().getString(), anchor, regionX, regionZ);
            regions.addLog(level, String.format("§eEnemy %s placed at [%d, %d, %d] in Region (%d, %d)",
                    baseType.name(), anchor.getX(), anchor.getY(), anchor.getZ(), regionX, regionZ));
            return true;
        }

        return false;
    }
}
