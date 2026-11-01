package com.warfront.region.terrain;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Categorical tactical terrain types used for dynamic region naming,
 * mission quota scaling, and tactical enemy spawn doctrines.
 */
public enum TerrainType {
    RIVER("Riverlands", "Rivery"),
    PLAINS("Plains", "Open"),
    HILLS("Hills", "Hilly"),
    MOUNTAIN("Highlands", "Mountainous"),
    JUNGLE("Jungle", "Dense"),
    SWAMP("Swamp", "Swampy"),
    DESERT("Desert", "Arid"),
    BADLANDS("Badlands", "Canyon"),
    FOREST("Forest", "Wooded"),
    TAIGA("Taiga", "Boreal"),
    TUNDRA("Tundra", "Glacial"),
    SAVANNA("Savanna", "Dry"),
    OCEAN("Coast", "Coastal");

    private final String noun;
    private final String prefix;

    TerrainType(String noun, String prefix) {
        this.noun = noun;
        this.prefix = prefix;
    }

    public String getNoun() {
        return noun;
    }

    public String getPrefix() {
        return prefix;
    }

    public net.minecraft.network.chat.Component getNounComponent() {
        return net.minecraft.network.chat.Component.translatable("terrain.warfront." + name().toLowerCase(java.util.Locale.ROOT) + ".noun");
    }

    public net.minecraft.network.chat.Component getPrefixComponent() {
        return net.minecraft.network.chat.Component.translatable("terrain.warfront." + name().toLowerCase(java.util.Locale.ROOT) + ".prefix");
    }

    public static TerrainType fromBiome(Holder<Biome> biomeHolder) {
        if (biomeHolder == null) {
            return PLAINS;
        }
        String path = biomeHolder.unwrapKey().map(k -> k.location().getPath().toLowerCase()).orElse("");
        return fromPath(path);
    }

    public static TerrainType fromPath(String path) {
        if (path == null || path.isEmpty()) {
            return PLAINS;
        }
        if (path.contains("river")) {
            return RIVER;
        }
        if (path.contains("ocean") || path.contains("beach") || path.contains("stony_shore")) {
            return OCEAN;
        }
        if (path.contains("swamp") || path.contains("mangrove")) {
            return SWAMP;
        }
        if (path.contains("jungle") || path.contains("bamboo")) {
            return JUNGLE;
        }
        if (path.contains("badlands") || path.contains("eroded")) {
            return BADLANDS;
        }
        if (path.contains("desert")) {
            return DESERT;
        }
        // Specific elevated hill and plateau biomes (Y = 95 - 140)
        if (path.contains("cherry_grove") || path.contains("meadow") || path.contains("windswept") || path.contains("savanna_plateau")) {
            return HILLS;
        }
        // Mountain slopes and peaks (Y = 130 - 256+)
        if (path.contains("peaks") || path.contains("mountain") || path.contains("slopes") || path.contains("grove")) {
            return MOUNTAIN;
        }
        // Glacial and snowy arctic biomes
        if (path.contains("ice") || path.contains("snow") || path.contains("frozen")) {
            return TUNDRA;
        }
        if (path.contains("savanna")) {
            return SAVANNA;
        }
        if (path.contains("taiga")) {
            return TAIGA;
        }
        if (path.contains("forest") || path.contains("birch") || path.contains("wood")) {
            return FOREST;
        }
        if (path.contains("plains")) {
            return PLAINS;
        }
        return PLAINS;
    }
}
