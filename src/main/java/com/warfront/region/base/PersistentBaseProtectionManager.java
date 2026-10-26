package com.warfront.region.base;

import com.warfront.Warfront;
import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.region.RegionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages structural integrity evaluation and automated interval-based self-repair
 * for persistent enemy base fortifications (outposts, monoliths, etc.).
 *
 * Mechanics:
 * - Structures are 100% susceptible to destruction: players can freely mine, break, and detonate blocks.
 * - Outside of active war, persistent bases periodically check their structural integrity against
 *   their pristine blueprint/template (every 100 ticks / 5 seconds) when near players.
 * - Missing or damaged blocks are gradually reconstructed back to their pristine blueprint state
 *   in batches, with visual and audio feedback (particles and anvil sounds).
 * - During active war / siege campaigns on that subregion, self-repair pauses so players can
 *   assault and demolish the fortress without the structure immediately healing itself.
 */
public final class PersistentBaseProtectionManager {

    /** How often (in server ticks) bases evaluate structural integrity and self-repair (30 ticks = 1.5 seconds). */
    private static final int REPAIR_INTERVAL_TICKS = 30;

    /** Maximum blocks a base will self-repair per interval to provide smooth, cascading reconstruction. */
    private static final int MAX_REPAIRED_BLOCKS_PER_INTERVAL = 16;

    /** Player distance threshold beyond which bases do not tick repair to conserve server CPU. */
    private static final double PLAYER_EVAL_DISTANCE_SQ = 192.0D * 192.0D;

    /** Cache of blueprint blocks: RegionKey -> (BlockPos -> Expected BlockState). */
    private static final Map<Long, Map<BlockPos, BlockState>> BLUEPRINT_CACHE = new ConcurrentHashMap<>();

    private PersistentBaseProtectionManager() {
    }

    /**
     * Checks whether all subregions occupied by the base are currently involved in an active war.
     * If at least one occupied subregion is not at war, this returns false so evaluateAndRepairBase
     * can continue self-repairing the non-active side of the fortress.
     */
    public static boolean isBaseAtWar(ServerLevel level, int rx, int rz, BlockPos anchor, RegionData regions) {
        if (regions == null || anchor == null) {
            return false;
        }

        RegionData.RegionState state = regions.getSavedRegionState(rx, rz);
        com.warfront.region.BaseType baseType = (state != null) ? state.baseType() : com.warfront.region.BaseType.OUTPOST;
        int occupiedMask = com.warfront.mission.MissionProfile.getOccupiedBaseSubRegionsMask(rx, rz, baseType, anchor);
        if (occupiedMask == 0) {
            return false;
        }

        RegionData.SiegeCampaign siege = regions.getSiege(rx, rz);
        if (siege != null && (siege.activeSubRegionsMask() & occupiedMask) == occupiedMask) {
            return true;
        }

        // Check if all occupied subregions have active missions
        for (int bit = 0; bit < 4; bit++) {
            if ((occupiedMask & (1 << bit)) != 0) {
                int sx = bit % 2;
                int sz = bit / 2;
                if (!ActiveCampaignMissionManager.hasActiveMission(rx, rz, sx, sz)) {
                    return false; // Non-active side exists: allow repair loop to run for the non-active side!
                }
            }
        }

        return true;
    }

    /**
     * Server tick hook that evaluates structural integrity of persistent bases and repairs damaged blocks.
     * Enforces the 5-scenario lifecycle:
     * 1. Player destroys base when mission is not active -> base repairs itself on the spot.
     * 2. Player starts mission and enters subregion -> decay and repair are both deactive.
     * 3. Player wins mission but war is ongoing -> base remains stay frozen where they are.
     * 4. Player wins regional war -> base remains decay.
     * 5. Player loses war (timeout / defeat) -> region returns to enemy and bases get repaired.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;

        com.warfront.region.base.garrison.BaseGarrisonManager.onServerTick(level);

        long gameTime = level.getGameTime();
        if (gameTime % REPAIR_INTERVAL_TICKS != 0) return;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        RegionData regions = RegionData.get(level);
        if (regions == null) return;

        // Collect candidate regions around online players (radius 2) and all placed bases
        java.util.Set<Long> candidateRegionKeys = new java.util.HashSet<>();
        for (ServerPlayer player : players) {
            if (player.level() != level) continue;
            int prx = Math.floorDiv(player.getBlockX(), RegionData.REGION_SIZE_BLOCKS);
            int prz = Math.floorDiv(player.getBlockZ(), RegionData.REGION_SIZE_BLOCKS);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    candidateRegionKeys.add(ChunkPos.asLong(prx + dx, prz + dz));
                }
            }
        }

        for (RegionData.Region placed : regions.getAllPlacedBaseRegions()) {
            candidateRegionKeys.add(ChunkPos.asLong(placed.x(), placed.z()));
        }

        for (long key : candidateRegionKeys) {
            int rx = ChunkPos.getX(key);
            int rz = ChunkPos.getZ(key);

            RegionData.Region reg = regions.regionAt(rx, rz);
            if (reg.baseType() == com.warfront.region.BaseType.NONE) {
                continue;
            }

            BlockPos anchor = reg.baseAnchor();
            if (anchor == null) {
                anchor = com.warfront.region.generator.ProceduralRegionGenerator.getInstance()
                        .findPhysicalBaseAnchor(level, level.getSeed(), rx, rz, reg.baseType())
                        .orElse(null);
            }
            if (anchor == null) {
                continue;
            }

            // Check if any player is within evaluation range
            boolean playerNear = false;
            for (ServerPlayer player : players) {
                if (player.level() != level) continue;
                if (player.distanceToSqr(anchor.getX() + 0.5D, anchor.getY() + 0.5D, anchor.getZ() + 0.5D) <= PLAYER_EVAL_DISTANCE_SQ) {
                    playerNear = true;
                    break;
                }
            }
            if (!playerNear) {
                continue;
            }

            // Scenario 4: Player won the war on region -> base remains decay
            if (reg.owner() == com.warfront.region.Faction.HUMANITY) {
                // If there is an active pending salvage site waiting for loot collection in this region, defer decay
                if (com.warfront.mission.site.PendingSalvageManager.hasPendingSiteForRegion(rx, rz)) {
                    continue;
                }
                decayBaseRemainsIfPresent(level, rx, rz, reg, anchor, regions);
                continue;
            }

            // For AI-owned regions:
            if (reg.owner().isAI()) {
                com.warfront.region.base.garrison.BaseGarrisonManager.ensureGarrisonSpawned(level, rx, rz, reg.baseType(), reg.owner(), anchor);

                // Scenario 2 & Scenario 3: If base is actively at war, skip both decay and repair
                if (isBaseAtWar(level, rx, rz, anchor, regions)) {
                    continue;
                }

                // Scenario 1 & Scenario 5: Base is not at war -> self-repair on the spot
                evaluateAndRepairBase(level, rx, rz, reg, anchor, regions);
            }
        }
    }

    /**
     * Decays any remaining enemy base structure blocks in a Humanity-captured region.
     * Includes the base template layer (anchor.getY() - 1) and stepped perimeter foundations
     * so they are backfilled cleanly to natural ground (GRASS_BLOCK/DIRT/STONE) with zero craters.
     */
    public static void decayBaseRemainsIfPresent(ServerLevel level, int rx, int rz, RegionData.Region reg, BlockPos anchor, RegionData regions) {
        if (level == null || anchor == null) return;

        com.warfront.region.Faction owner = (reg != null && reg.owner() != null && reg.owner().isAI())
                ? reg.owner()
                : com.warfront.region.Faction.PILLAGER_CONQUERORS;
        BaseBuildingGenerator generator = BaseBuildingRegistry.getGenerator(owner, reg.baseType());
        Map<BlockPos, BlockState> blueprint = (generator != null) ? generator.getPristineBlocks(level, anchor) : Map.of();

        List<BlockPos> remainingBlocks = new ArrayList<>();
        int foundationMinY = anchor.getY() - 1;

        if (!blueprint.isEmpty()) {
            for (BlockPos pos : blueprint.keySet()) {
                if (pos.getY() >= foundationMinY && level.hasChunkAt(pos)) {
                    BlockState current = level.getBlockState(pos);
                    if (!current.isAir()) {
                        remainingBlocks.add(pos.immutable());
                    }
                }
            }
        } else {
            // Fallback bounding box scan
            int halfX = 14;
            int halfZ = 14;
            int minY = foundationMinY;
            int maxY = Math.min(level.getMaxBuildHeight(), anchor.getY() + 25);
            for (int x = anchor.getX() - halfX; x <= anchor.getX() + halfX; x++) {
                for (int z = anchor.getZ() - halfZ; z <= anchor.getZ() + halfZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (level.hasChunkAt(pos)) {
                            BlockState bs = level.getBlockState(pos);
                            if (!bs.isAir() && !bs.is(Blocks.BEDROCK)
                                    && !bs.is(Blocks.GRASS_BLOCK) && !bs.is(Blocks.DIRT)
                                    && !bs.is(Blocks.STONE) && !bs.is(Blocks.DEEPSLATE)
                                    && bs.getFluidState().isEmpty()) {
                                remainingBlocks.add(pos.immutable());
                            }
                        }
                    }
                }
            }
        }

        // Also check stepped foundation columns under perimeter down to depth 14 beneath origin
        int sizeX = 29;
        int sizeZ = 29;
        int halfX = sizeX / 2;
        int halfZ = sizeZ / 2;
        BlockPos origin = new BlockPos(anchor.getX() - halfX, anchor.getY() - 1, anchor.getZ() - halfZ);
        for (int x = 0; x < sizeX; x++) {
            boolean isPerimeterX = (x == 0 || x == sizeX - 1);
            for (int z = 0; z < sizeZ; z++) {
                boolean isPerimeterZ = (z == 0 || z == sizeZ - 1);
                if (isPerimeterX || isPerimeterZ) {
                    int wx = origin.getX() + x;
                    int wz = origin.getZ() + z;
                    for (int depth = 1; depth <= 14; depth++) {
                        int wy = origin.getY() - depth;
                        if (wy < level.getMinBuildHeight()) break;
                        BlockPos fPos = new BlockPos(wx, wy, wz);
                        if (level.hasChunkAt(fPos)) {
                            BlockState bs = level.getBlockState(fPos);
                            if (bs.is(Blocks.COBBLESTONE)) {
                                remainingBlocks.add(fPos.immutable());
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (!remainingBlocks.isEmpty()) {
            com.warfront.mission.site.MissionSiteSnapshotManager.queueGradualDemolition(level, remainingBlocks, anchor);
            Warfront.LOGGER.info("[Warfront] Queued {} remaining base blocks in Humanity-captured Region ({}, {}) for gradual decay.",
                    remainingBlocks.size(), rx, rz);
        }

        com.warfront.region.base.garrison.BaseGarrisonManager.cleanupGarrisonOnConquest(level, rx, rz);

        if (regions != null && regions.isBasePlaced(rx, rz)) {
            regions.setBasePlaced(rx, rz, false);
        }
    }

    private static void evaluateAndRepairBase(ServerLevel level, int rx, int rz, RegionData.Region reg, BlockPos anchor, RegionData regions) {
        RegionData.RegionState state = new RegionData.RegionState(
                reg.owner(), reg.stability(), reg.resistance(), reg.baseType(), reg.clusterId(), anchor, reg.basePlaced());

        Map<BlockPos, BlockState> blueprint = getOrComputePristineBlocks(level, rx, rz, state);
        if (blueprint.isEmpty()) {
            Warfront.LOGGER.warn("[Warfront] evaluateAndRepairBase: Blueprint is empty for Region ({}, {}) with baseType {} and anchor {}. Skipping repair.",
                    rx, rz, state.baseType(), anchor);
            return;
        }

        int totalExpected = 0;
        int intactCount = 0;
        List<Map.Entry<BlockPos, BlockState>> damagedEntries = new ArrayList<>();
        RegionData.SiegeCampaign siege = (regions != null) ? regions.getSiege(rx, rz) : null;

        for (Map.Entry<BlockPos, BlockState> entry : blueprint.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState expected = entry.getValue();

            // CAUTION: Foundations beneath building floor (y < anchor.getY() - 1) are NOT added to the repair/decay goal
            if (pos.getY() < anchor.getY() - 1 || expected.isAir()) {
                continue;
            }

            if (!level.hasChunkAt(pos)) {
                continue;
            }

            // Subregion-Aware Repair Partitioning:
            // Evaluate which subregion this block belongs to
            int localX = Math.floorMod(pos.getX(), RegionData.REGION_SIZE_BLOCKS);
            int localZ = Math.floorMod(pos.getZ(), RegionData.REGION_SIZE_BLOCKS);
            int sx = localX / 64;
            int sz = localZ / 64;
            int subBit = sz * 2 + sx;

            boolean subregionAtWar = ActiveCampaignMissionManager.hasActiveMission(rx, rz, sx, sz);
            if (!subregionAtWar && siege != null) {
                subregionAtWar = (siege.activeSubRegionsMask() & (1 << subBit)) != 0;
            }

            if (subregionAtWar) {
                // Active combat mission on this side of the base: skip repair so destruction persists!
                continue;
            }

            totalExpected++;
            BlockState current = level.getBlockState(pos);

            // If the block in world is the same block type, it is intact (preserves connected fences, walls, stairs, chests)
            if (current.is(expected.getBlock())) {
                intactCount++;
            } else {
                damagedEntries.add(entry);
            }
        }

        if (totalExpected == 0) return;
        float integrity = (float) intactCount / totalExpected;

        if (!damagedEntries.isEmpty()) {
            // Sort bottom-to-top so reconstruction builds upwards naturally
            damagedEntries.sort((a, b) -> Integer.compare(a.getKey().getY(), b.getKey().getY()));

            // Repair a batch of damaged/missing blocks back to pristine state
            int repaired = 0;
            for (Map.Entry<BlockPos, BlockState> entry : damagedEntries) {
                if (repaired >= MAX_REPAIRED_BLOCKS_PER_INTERVAL) break;

                BlockPos pos = entry.getKey();
                BlockState expected = entry.getValue();

                level.setBlock(pos, expected, 3);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 4, 0.25D, 0.25D, 0.25D, 0.04D);
                repaired++;
            }

            if (repaired > 0) {
                level.playSound(null, anchor, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.4F, 1.4F);
                Warfront.LOGGER.info("[Warfront] Base at {} in Region ({}, {}) integrity: {}%. Repaired {} damaged blocks.",
                        anchor, rx, rz, (int)(integrity * 100), repaired);
                if (regions != null && !regions.isBasePlaced(rx, rz)) {
                    regions.markBasePlaced(rx, rz, anchor);
                }
            }
        }
    }

    /**
     * Instantly evaluates and repairs all damaged/missing blocks in the base blueprint in a single tick.
     * Invoked when a base mission begins to immediately restore the structure to pristine condition.
     */
    public static int instantRepairBase(ServerLevel level, int rx, int rz, BlockPos anchor, RegionData.RegionState state) {
        if (level == null || anchor == null) {
            return 0;
        }

        Map<BlockPos, BlockState> blueprint = getOrComputePristineBlocks(level, rx, rz, state);
        if (blueprint == null || blueprint.isEmpty()) {
            Warfront.LOGGER.warn("[Warfront] instantRepairBase: Blueprint is empty for Region ({}, {}) with baseType {} and anchor {}.",
                    rx, rz, state != null ? state.baseType() : "null", anchor);
            return 0;
        }

        List<Map.Entry<BlockPos, BlockState>> damagedEntries = new ArrayList<>();
        int floorMinY = anchor.getY() - 1;

        for (Map.Entry<BlockPos, BlockState> entry : blueprint.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState expected = entry.getValue();

            if (pos.getY() < floorMinY || expected.isAir()) {
                continue;
            }

            if (!level.hasChunkAt(pos)) {
                continue;
            }

            BlockState current = level.getBlockState(pos);
            if (!current.is(expected.getBlock())) {
                damagedEntries.add(entry);
            }
        }

        if (damagedEntries.isEmpty()) {
            return 0;
        }

        // Sort bottom-to-top so reconstruction builds upwards naturally
        damagedEntries.sort((a, b) -> Integer.compare(a.getKey().getY(), b.getKey().getY()));

        for (Map.Entry<BlockPos, BlockState> entry : damagedEntries) {
            level.setBlock(entry.getKey(), entry.getValue(), 3);
        }

        level.playSound(null, anchor, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, anchor.getX() + 0.5D, anchor.getY() + 1.0D, anchor.getZ() + 0.5D, 20, 1.5D, 1.0D, 1.5D, 0.05D);
        Warfront.LOGGER.info("[Warfront] Instantly repaired base at {} in Region ({}, {}). Restored {} blocks to pristine state.",
                anchor, rx, rz, damagedEntries.size());

        RegionData regions = RegionData.get(level);
        if (regions != null && !regions.isBasePlaced(rx, rz)) {
            regions.markBasePlaced(rx, rz, anchor);
        }

        return damagedEntries.size();
    }

    /**
     * Retrieves or calculates the pristine blueprint block map for a base.
     */
    public static Map<BlockPos, BlockState> getOrComputePristineBlocks(ServerLevel level, int rx, int rz, RegionData.RegionState state) {
        long key = ChunkPos.asLong(rx, rz);
        Map<BlockPos, BlockState> cached = BLUEPRINT_CACHE.get(key);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        BaseBuildingGenerator generator = BaseBuildingRegistry.getGenerator(state.owner(), state.baseType());
        if (generator != null && state.baseAnchor() != null) {
            Map<BlockPos, BlockState> computed = generator.getPristineBlocks(level, state.baseAnchor());
            if (computed != null && !computed.isEmpty()) {
                BLUEPRINT_CACHE.put(key, computed);
                return computed;
            }
        }
        return Map.of();
    }

    /**
     * Clears cached blueprints (e.g. on world unload/reload).
     */
    public static void clearCache() {
        BLUEPRINT_CACHE.clear();
    }
}
