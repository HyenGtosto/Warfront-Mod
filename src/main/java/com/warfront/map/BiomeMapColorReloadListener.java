package com.warfront.map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

public final class BiomeMapColorReloadListener extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIRECTORY = "warfront/biome_map_colors";
    public static final BiomeMapColorReloadListener INSTANCE = new BiomeMapColorReloadListener();

    private BiomeMapColorReloadListener() {
        super(new Gson(), DIRECTORY);
    }

    public static void register(AddReloadListenerEvent event) {
        event.addListener(INSTANCE);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, Integer> loadedColors = new HashMap<>();
        files.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> loadFile(entry.getKey(), entry.getValue(), loadedColors));
        BiomeMapColors.replace(loadedColors);
        LOGGER.info("Loaded {} biome map colors from {}", loadedColors.size(), DIRECTORY);
    }

    private static void loadFile(ResourceLocation fileId, JsonElement element, Map<ResourceLocation, Integer> loadedColors) {
        if (!element.isJsonObject()) {
            LOGGER.error("Biome map color file {} must contain a JSON object", fileId);
            return;
        }

        JsonObject root = element.getAsJsonObject();
        JsonElement colorsElement = root.get("colors");
        if (colorsElement == null || !colorsElement.isJsonObject()) {
            LOGGER.error("Biome map color file {} is missing a colors object", fileId);
            return;
        }

        for (Map.Entry<String, JsonElement> colorEntry : colorsElement.getAsJsonObject().entrySet()) {
            ResourceLocation biomeId = ResourceLocation.tryParse(colorEntry.getKey());
            Integer color = parseColor(colorEntry.getValue());
            if (biomeId == null || color == null) {
                LOGGER.error("Invalid biome map color {} in {}", colorEntry.getKey(), fileId);
                continue;
            }
            loadedColors.put(biomeId, color);
        }
    }

    private static Integer parseColor(JsonElement element) {
        if (!element.isJsonPrimitive()) {
            return null;
        }

        String value = element.getAsString();
        if (!value.matches("#[0-9a-fA-F]{6}")) {
            return null;
        }

        return Integer.parseInt(value.substring(1), 16);
    }
}
