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
        long missionSeed,
        String playerFactionName,
        String attackerFactionName,
        boolean isFieldBattle,
        float attackerProgress,
        float defenderProgress,
        boolean playerInFactionRegion,
        String terrainName,
        int subTerrainsPacked,
        int subElevationsPacked
) {
    public SelectedRegion(
            int regionX, int regionZ, int subX, int subZ,
            Faction owner, float stability, float resistance,
            BaseType baseType, boolean underSiege, boolean isVisited,
            long remainingSiegeTicks, int dominoThreshold,
            int reachableMask, boolean regionReachable,
            int existingSiegeMask, int conqueredMask,
            Faction attacker, boolean isAwaitingReinforcements,
            long reinforcementRemainingTicks, boolean isEncircled,
            BlockPos baseAnchor, long missionSeed
    ) {
        this(regionX, regionZ, subX, subZ, owner, stability, resistance, baseType, underSiege, isVisited, remainingSiegeTicks, dominoThreshold, reachableMask, regionReachable, existingSiegeMask, conqueredMask, attacker, isAwaitingReinforcements, reinforcementRemainingTicks, isEncircled, baseAnchor, missionSeed, "", "", false, 0.0F, 0.0F, false, "Plains", 0, 0);
    }

    public SelectedRegion(
            int regionX, int regionZ, int subX, int subZ,
            Faction owner, float stability, float resistance,
            BaseType baseType, boolean underSiege, boolean isVisited,
            long remainingSiegeTicks, int dominoThreshold,
            int reachableMask, boolean regionReachable,
            int existingSiegeMask, int conqueredMask,
            Faction attacker, boolean isAwaitingReinforcements,
            long reinforcementRemainingTicks, boolean isEncircled,
            BlockPos baseAnchor, long missionSeed,
            String playerFactionName, String attackerFactionName,
            boolean isFieldBattle, float attackerProgress, float defenderProgress,
            boolean playerInFactionRegion, String terrainName
    ) {
        this(regionX, regionZ, subX, subZ, owner, stability, resistance, baseType, underSiege, isVisited, remainingSiegeTicks, dominoThreshold, reachableMask, regionReachable, existingSiegeMask, conqueredMask, attacker, isAwaitingReinforcements, reinforcementRemainingTicks, isEncircled, baseAnchor, missionSeed, playerFactionName, attackerFactionName, isFieldBattle, attackerProgress, defenderProgress, playerInFactionRegion, terrainName, 0, 0);
    }

    public com.warfront.region.terrain.TerrainType getSubRegionTerrain(int subIndex) {
        int tOrd = (subTerrainsPacked >> (subIndex * 4)) & 0xF;
        com.warfront.region.terrain.TerrainType[] vals = com.warfront.region.terrain.TerrainType.values();
        return (tOrd < vals.length) ? vals[tOrd] : com.warfront.region.terrain.TerrainType.PLAINS;
    }

    public int getSubRegionElevation(int subIndex) {
        int elev = (subElevationsPacked >> (subIndex * 8)) & 0xFF;
        return elev > 0 ? elev : 68;
    }

    public com.warfront.region.terrain.TerrainType[] getSubRegionTerrains() {
        com.warfront.region.terrain.TerrainType[] res = new com.warfront.region.terrain.TerrainType[4];
        for (int i = 0; i < 4; i++) res[i] = getSubRegionTerrain(i);
        return res;
    }

    public int[] getSubRegionElevations() {
        int[] res = new int[4];
        for (int i = 0; i < 4; i++) res[i] = getSubRegionElevation(i);
        return res;
    }

    public SelectedRegion withRemainingSiegeTicks(long newTicks) {
        return new SelectedRegion(regionX, regionZ, subX, subZ, owner, stability, resistance, baseType, underSiege, isVisited, newTicks, dominoThreshold, reachableMask, regionReachable, existingSiegeMask, conqueredMask, attacker, isAwaitingReinforcements, reinforcementRemainingTicks, isEncircled, baseAnchor, missionSeed, playerFactionName, attackerFactionName, isFieldBattle, attackerProgress, defenderProgress, playerInFactionRegion, terrainName, subTerrainsPacked, subElevationsPacked);
    }

    public SelectedRegion withReinforcementRemainingTicks(long newReinfTicks) {
        return new SelectedRegion(regionX, regionZ, subX, subZ, owner, stability, resistance, baseType, underSiege, isVisited, remainingSiegeTicks, dominoThreshold, reachableMask, regionReachable, existingSiegeMask, conqueredMask, attacker, isAwaitingReinforcements, newReinfTicks, isEncircled, baseAnchor, missionSeed, playerFactionName, attackerFactionName, isFieldBattle, attackerProgress, defenderProgress, playerInFactionRegion, terrainName, subTerrainsPacked, subElevationsPacked);
    }
}
