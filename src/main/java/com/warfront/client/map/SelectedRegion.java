package com.warfront.client.map;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;
import net.minecraft.core.BlockPos;

public record SelectedRegion(
        int regionX,
        int regionZ,
        int subX,
        int subZ,
        Faction owner,
        float stability,
        float resistance,
        BaseType baseType,
        boolean underSiege,
        boolean isVisited,
        long remainingSiegeTicks,
        int dominoThreshold,
        int reachableMask,
        boolean regionReachable,
        int existingSiegeMask,
        int conqueredMask,
        Faction attacker,
        boolean isAwaitingReinforcements,
        long reinforcementRemainingTicks,
        boolean isEncircled,
        BlockPos baseAnchor,
        long missionSeed
) {
    public SelectedRegion withRemainingSiegeTicks(long newTicks) {
        return new SelectedRegion(regionX, regionZ, subX, subZ, owner, stability, resistance, baseType, underSiege, isVisited, newTicks, dominoThreshold, reachableMask, regionReachable, existingSiegeMask, conqueredMask, attacker, isAwaitingReinforcements, reinforcementRemainingTicks, isEncircled, baseAnchor, missionSeed);
    }

    public SelectedRegion withReinforcementRemainingTicks(long newReinfTicks) {
        return new SelectedRegion(regionX, regionZ, subX, subZ, owner, stability, resistance, baseType, underSiege, isVisited, remainingSiegeTicks, dominoThreshold, reachableMask, regionReachable, existingSiegeMask, conqueredMask, attacker, isAwaitingReinforcements, newReinfTicks, isEncircled, baseAnchor, missionSeed);
    }
}
