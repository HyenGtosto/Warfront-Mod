package com.warfront.map;

import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

public final class BiomeMapColors {
    public static final int DEFAULT_COLOR = 0x666666;
    private static volatile Map<ResourceLocation, Integer> colors = Map.of();

    private BiomeMapColors() {
    }

    public static int colorFor(Holder<Biome> biome) {
        return biome.unwrapKey()
                .map(key -> colors.getOrDefault(key.location(), DEFAULT_COLOR))
                .orElse(DEFAULT_COLOR);
    }

    static void replace(Map<ResourceLocation, Integer> loadedColors) {
        colors = Map.copyOf(loadedColors);
    }
}
