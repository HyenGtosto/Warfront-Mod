package com.warfront.region.base;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry of building generators mapped by {@link Faction} and {@link BaseType}.
 *
 * Designed for modular expansion: concrete building blueprints (e.g. Pillager Outposts,
 * Zombie Spires, Humanity Fortresses) can be registered per faction and tier.
 * Currently, all enemy base placements are configured to deploy the test cobblestone monolith.
 */
public final class BaseBuildingRegistry {

    private static final BaseBuildingGenerator DEFAULT_MONOLITH = new CobblestoneMonolithGenerator();
    private static final Map<Faction, Map<BaseType, BaseBuildingGenerator>> GENERATORS = new EnumMap<>(Faction.class);

    static {
        // Initialize active generators for all factions with the monolith generator
        for (Faction faction : Faction.values()) {
            if (faction != Faction.UNCLAIMED && faction != Faction.HUMANITY) {
                Map<BaseType, BaseBuildingGenerator> factionMap = new EnumMap<>(BaseType.class);
                factionMap.put(BaseType.OUTPOST, DEFAULT_MONOLITH);
                factionMap.put(BaseType.HEADQUARTERS, DEFAULT_MONOLITH);
                factionMap.put(BaseType.MEGA_BASE, DEFAULT_MONOLITH);
                GENERATORS.put(faction, factionMap);
            }
        }
    }

    private BaseBuildingRegistry() {
    }

    /**
     * Retrieves the building generator registered for the specified faction and base tier.
     * Falls back to the default monolith generator if no specific blueprint is registered.
     */
    public static BaseBuildingGenerator getGenerator(Faction faction, BaseType baseType) {
        if (faction == null || baseType == null || baseType == BaseType.NONE) {
            return DEFAULT_MONOLITH;
        }

        Map<BaseType, BaseBuildingGenerator> factionMap = GENERATORS.get(faction);
        if (factionMap != null) {
            BaseBuildingGenerator generator = factionMap.get(baseType);
            if (generator != null) {
                return generator;
            }
        }

        return DEFAULT_MONOLITH;
    }

    /**
     * Registers a new building generator blueprint for a specific faction and base tier.
     */
    public static void register(Faction faction, BaseType baseType, BaseBuildingGenerator generator) {
        if (faction == null || baseType == null || generator == null) {
            return;
        }
        GENERATORS.computeIfAbsent(faction, f -> new EnumMap<>(BaseType.class)).put(baseType, generator);
    }
}
