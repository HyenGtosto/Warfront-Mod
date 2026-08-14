package com.warfront.ai.strategy;

public record AttackTarget(int sourceRegionX, int sourceRegionZ, int targetRegionX, int targetRegionZ, long attackerClusterId) {
    public AttackTarget(int sourceRegionX, int sourceRegionZ, int targetRegionX, int targetRegionZ) {
        this(sourceRegionX, sourceRegionZ, targetRegionX, targetRegionZ, 0L);
    }
}
