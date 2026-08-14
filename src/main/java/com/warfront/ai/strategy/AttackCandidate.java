package com.warfront.ai.strategy;

public record AttackCandidate(int sourceRegionX, int sourceRegionZ, int targetRegionX, int targetRegionZ) {
    public AttackTarget toTarget(long clusterId) {
        return new AttackTarget(sourceRegionX, sourceRegionZ, targetRegionX, targetRegionZ, clusterId);
    }

    public AttackTarget toTarget() {
        return new AttackTarget(sourceRegionX, sourceRegionZ, targetRegionX, targetRegionZ, 0L);
    }
}
