package com.warfront.ai.strategy;

import com.warfront.region.Faction;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Strategy Registry mapping Factions to their corresponding FactionAttackStrategy.
 * Adding a new faction only requires creating a strategy class and registering it here.
 */
public final class FactionStrategyRegistry {

    private static final Map<Faction, FactionAttackStrategy> STRATEGIES = new EnumMap<>(Faction.class);
    private static final FactionAttackStrategy FALLBACK_STRATEGY = new ZombieAttackStrategy();

    static {
        registerStrategy(Faction.ZOMBIE_HORDE, new ZombieAttackStrategy());
        registerStrategy(Faction.PILLAGER_CONQUERORS, new PillagerAttackStrategy());
    }

    private FactionStrategyRegistry() {
    }

    public static void registerStrategy(Faction faction, FactionAttackStrategy strategy) {
        if (faction != null && strategy != null) {
            STRATEGIES.put(faction, strategy);
        }
    }

    public static FactionAttackStrategy getStrategy(Faction faction) {
        return STRATEGIES.getOrDefault(faction, FALLBACK_STRATEGY);
    }
}
