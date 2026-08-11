package com.warfront.region.generator;

public record FactionGeneratorConfig(
        int separation,
        int minClusterSize,
        int maxClusterSize,
        int minDistanceFromSpawn,
        float defaultStability,
        float defaultResistance,
        long seedSalt
) {
    public FactionGeneratorConfig {
        if (separation <= 0) {
            throw new IllegalArgumentException("Separation must be positive");
        }
        if (minClusterSize < 0 || maxClusterSize < minClusterSize) {
            throw new IllegalArgumentException("Invalid cluster size range");
        }
        if (minDistanceFromSpawn < 0) {
            throw new IllegalArgumentException("Min distance from spawn cannot be negative");
        }
    }
}
