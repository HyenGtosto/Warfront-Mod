package com.warfront.ai.strategy;

import com.warfront.region.Faction;
import com.warfront.region.RegionData;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.Optional;
import java.util.Random;

public record AttackContext(
        ServerLevel level,
        RegionData regions,
        Faction faction,
        int playerProximityRadius,
        Random random,
        Map<Long, Optional<HQPos>> evaluationCache
) {
}
