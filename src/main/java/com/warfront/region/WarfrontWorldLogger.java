package com.warfront.region;

import com.warfront.Warfront;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

public class WarfrontWorldLogger {
    private static final String LOG_FILENAME = "warfront_war_events.log";

    public static File getLogFile(ServerLevel level) {
        Path worldDir = level.getServer().getWorldPath(LevelResource.ROOT);
        return worldDir.resolve(LOG_FILENAME).toFile();
    }

    public static void logEvent(ServerLevel level, String logMessage) {
        if (level == null || logMessage == null || logMessage.isBlank()) {
            return;
        }

        try {
            File logFile = getLogFile(level);
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
                writer.println(logMessage);
            }
        } catch (Exception e) {
            Warfront.LOGGER.error("Failed to write to Warfront world log file", e);
        }
    }

    public static List<String> readLogs(ServerLevel level) {
        List<String> logs = new ArrayList<>();
        if (level == null) {
            return logs;
        }

        try {
            File logFile = getLogFile(level);
            if (logFile.exists()) {
                List<String> lines = Files.readAllLines(logFile.toPath());
                for (String line : lines) {
                    if (!line.isBlank()) {
                        logs.add(line);
                    }
                }
            }
        } catch (Exception e) {
            Warfront.LOGGER.error("Failed to read Warfront world log file", e);
        }
        return logs;
    }
}
