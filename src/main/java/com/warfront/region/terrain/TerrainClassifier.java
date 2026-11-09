package com.warfront.region.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side tactical terrain classification engine.
 *
 * Samples Minecraft's Voronoi multi-noise climate at chunk centers via $O(1)$ lookups
 * without triggering chunk generation or 3D noise router evaluations.
 * Computes subregion-level ground-truth terrain and averages across macro regions
 * to produce dual-element compound names (e.g. "Rivery Plains", "Mountainous Jungle").
 */
public final class TerrainClassifier {

    public record SubRegionTerrainData(TerrainType primary, TerrainType secondary, String displayName) {
    }

    private static final Map<Long, String> REGION_NAME_CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, SubRegionTerrainData> SUBREGION_DATA_CACHE = new ConcurrentHashMap<>();
    private static final Map<Long, TerrainType> CHUNK_TERRAIN_CACHE = new ConcurrentHashMap<>();
    private static volatile long cachedSeed = Long.MIN_VALUE;

    private TerrainClassifier() {
    }

    private static void ensureSeed(ServerLevel level) {
        if (level != null) {
            long seed = level.getSeed();
            if (cachedSeed != seed) {
                cachedSeed = seed;
                REGION_NAME_CACHE.clear();
                SUBREGION_DATA_CACHE.clear();
                CHUNK_TERRAIN_CACHE.clear();
            }
        }
    }

    public static TerrainType getChunkTerrain(ServerLevel level, int cx, int cz, int seaLevel) {
        long cKey = ChunkPos.asLong(cx, cz);
        TerrainType cached = CHUNK_TERRAIN_CACHE.get(cKey);
        if (cached != null) {
            return cached;
        }
        Holder<Biome> biome = level.getNoiseBiome((cx << 2) + 2, seaLevel >> 2, (cz << 2) + 2);
        TerrainType type = TerrainType.fromBiome(biome);
        CHUNK_TERRAIN_CACHE.put(cKey, type);
        return type;
    }

    /**
     * Resolves the authoritative compound terrain name for a macro region (e.g. "Rivery Plains", "Mountainous Jungle").
     */
    public static String getRegionTerrainName(ServerLevel level, int rx, int rz) {
        if (level == null) {
            return "Plains";
        }
        ensureSeed(level);
        long key = ChunkPos.asLong(rx, rz);
        String cached = REGION_NAME_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        Map<TerrainType, Integer> regionHistogram = new EnumMap<>(TerrainType.class);

        // Populate all 4 subregions first (which also warms CHUNK_TERRAIN_CACHE for all 64 chunks)
        for (int sx = 0; sx <= 1; sx++) {
            for (int sz = 0; sz <= 1; sz++) {
                getSubRegionTerrain(level, rx, rz, sx, sz);
            }
        }

        // Tally full 64 chunk counts across the region from CHUNK_TERRAIN_CACHE in O(1)
        int minCX = rx * 8;
        int minCZ = rz * 8;
        int seaLevel = level.getSeaLevel();

        for (int cx = minCX; cx < minCX + 8; cx++) {
            for (int cz = minCZ; cz < minCZ + 8; cz++) {
                TerrainType type = getChunkTerrain(level, cx, cz, seaLevel);
                regionHistogram.merge(type, 1, Integer::sum);
            }
        }

        String compoundName = buildCompoundName(regionHistogram, 64, 13); // 13 / 64 = ~20% threshold
        REGION_NAME_CACHE.put(key, compoundName);
        return compoundName;
    }

    /**
     * Resolves the tactical terrain classification for a $64 \times 64$ block subregion.
     */
    public static SubRegionTerrainData getSubRegionTerrain(ServerLevel level, int rx, int rz, int sx, int sz) {
        ensureSeed(level);
        long subKey = subRegionKey(rx, rz, sx, sz);
        SubRegionTerrainData cached = SUBREGION_DATA_CACHE.get(subKey);
        if (cached != null) {
            return cached;
        }

        if (level == null) {
            SubRegionTerrainData fallback = new SubRegionTerrainData(TerrainType.PLAINS, null, "Plains");
            SUBREGION_DATA_CACHE.put(subKey, fallback);
            return fallback;
        }

        Map<TerrainType, Integer> subHistogram = new EnumMap<>(TerrainType.class);
        int subMinCX = (rx * 8) + (sx * 4);
        int subMinCZ = (rz * 8) + (sz * 4);
        int seaLevel = level.getSeaLevel();

        for (int cx = subMinCX; cx < subMinCX + 4; cx++) {
            for (int cz = subMinCZ; cz < subMinCZ + 4; cz++) {
                TerrainType type = getChunkTerrain(level, cx, cz, seaLevel);
                subHistogram.merge(type, 1, Integer::sum);
            }
        }

        TerrainType primary = TerrainType.PLAINS;
        int primaryCount = -1;
        TerrainType secondary = null;
        int secondaryCount = -1;

        for (Map.Entry<TerrainType, Integer> entry : subHistogram.entrySet()) {
            int count = entry.getValue();
            if (count > primaryCount) {
                secondary = primary;
                secondaryCount = primaryCount;
                primary = entry.getKey();
                primaryCount = count;
            } else if (count > secondaryCount) {
                secondary = entry.getKey();
                secondaryCount = count;
            }
        }

        String displayName;
        if (secondary != null && secondaryCount >= 3) { // 3 / 16 = ~19% threshold
            displayName = secondary.name() + ":" + primary.name();
        } else {
            displayName = primary.name();
        }

        SubRegionTerrainData data = new SubRegionTerrainData(primary, secondary, displayName);
        SUBREGION_DATA_CACHE.put(subKey, data);
        return data;
    }

    private static String buildCompoundName(Map<TerrainType, Integer> histogram, int totalChunks, int secondaryThreshold) {
        TerrainType primary = TerrainType.PLAINS;
        int primaryCount = -1;
        TerrainType secondary = null;
        int secondaryCount = -1;

        for (Map.Entry<TerrainType, Integer> entry : histogram.entrySet()) {
            int count = entry.getValue();
            if (count > primaryCount) {
                secondary = primary;
                secondaryCount = primaryCount;
                primary = entry.getKey();
                primaryCount = count;
            } else if (count > secondaryCount) {
                secondary = entry.getKey();
                secondaryCount = count;
            }
        }

        if (secondary != null && secondaryCount >= secondaryThreshold) {
            return secondary.name() + ":" + primary.name();
        }
        return primary.name();
    }

    /**
     * Resolves a raw terrain descriptor (structured "SEC:PRIM" ID, single enum name, or legacy English string)
     * into a fully localized translatable Component.
     */
    public static net.minecraft.network.chat.Component getLocalizedTerrainComponent(String raw) {
        if (raw == null || raw.isEmpty()) {
            return TerrainType.PLAINS.getNounComponent();
        }
        // Case 1: Structured format "SECONDARY:PRIMARY" (e.g. "RIVER:PLAINS")
        if (raw.contains(":")) {
            String[] parts = raw.split(":", 2);
            try {
                TerrainType sec = TerrainType.valueOf(parts[0].trim().toUpperCase(java.util.Locale.ROOT));
                TerrainType prim = TerrainType.valueOf(parts[1].trim().toUpperCase(java.util.Locale.ROOT));
                return net.minecraft.network.chat.Component.translatable("terrain.warfront.compound", sec.getPrefixComponent(), prim.getNounComponent());
            } catch (Exception ignored) {
            }
        }
        // Case 2: Structured single enum name "PLAINS"
        try {
            TerrainType prim = TerrainType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            return prim.getNounComponent();
        } catch (Exception ignored) {
        }
        // Case 3: Legacy compound English string "Rivery Plains"
        if (raw.contains(" ")) {
            String[] parts = raw.split(" ", 2);
            TerrainType sec = findByPrefix(parts[0]);
            TerrainType prim = findByNoun(parts[1]);
            if (sec != null && prim != null) {
                return net.minecraft.network.chat.Component.translatable("terrain.warfront.compound", sec.getPrefixComponent(), prim.getNounComponent());
            }
        }
        // Case 4: Legacy single English noun "Plains"
        TerrainType prim = findByNoun(raw);
        if (prim != null) {
            return prim.getNounComponent();
        }
        return net.minecraft.network.chat.Component.literal(raw);
    }

    private static TerrainType findByPrefix(String prefix) {
        for (TerrainType t : TerrainType.values()) {
            if (t.getPrefix().equalsIgnoreCase(prefix)) return t;
        }
        return null;
    }

    private static TerrainType findByNoun(String noun) {
        for (TerrainType t : TerrainType.values()) {
            if (t.getNoun().equalsIgnoreCase(noun)) return t;
        }
        return null;
    }

    public static long subRegionKey(int regionX, int regionZ, int subX, int subZ) {
        long reg = ChunkPos.asLong(regionX, regionZ);
        int subIdx = (subZ & 1) << 1 | (subX & 1);
        return (reg << 2) | subIdx;
    }

    public static void clearCaches() {
        REGION_NAME_CACHE.clear();
        SUBREGION_DATA_CACHE.clear();
        CHUNK_TERRAIN_CACHE.clear();
        cachedSeed = Long.MIN_VALUE;
    }
}
