package com.warfront.region;

/**
 * Categorical classification of Minecraft biomes used by the strategic map,
 * regional strength modifiers, and future in-war enemy spawn variants.
 */
public enum BiomeCategory {
    STANDARD,
    DESERT,
    ICE;

    /**
     * Resolves the {@link BiomeCategory} from a biome's resource location path.
     *
     * @param path lowercase path string (e.g. "desert", "plains", "snowy_slopes")
     * @return matching BiomeCategory (DESERT, ICE, or STANDARD for all others)
     */
    public static BiomeCategory fromPath(String path) {
        if (path == null) {
            return STANDARD;
        }
        String p = path.toLowerCase();
        if (p.contains("desert") || p.contains("badlands")) {
            return DESERT;
        }
        if (p.contains("snow") || p.contains("ice") || p.contains("frozen") || p.contains("grove")) {
            return ICE;
        }
        return STANDARD;
    }
}
