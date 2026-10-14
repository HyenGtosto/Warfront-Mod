package com.warfront.region.base.garrison;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry mapping (Faction, BaseType) pairs to their modular BaseGarrisonDefinition.
 * Highly extensible for future Headquarters and Mega Base garrisons.
 */
public final class BaseGarrisonRegistry {

    private static final Map<Key, BaseGarrisonDefinition> DEFINITIONS = new HashMap<>();

    static {
        register(Faction.PILLAGER_CONQUERORS, BaseType.OUTPOST, OutpostGarrisonDefinition.INSTANCE);
        register(Faction.ZOMBIE_HORDE, BaseType.OUTPOST, ZombieOutpostGarrisonDefinition.INSTANCE);
    }

    private BaseGarrisonRegistry() {
    }

    public static void register(Faction faction, BaseType baseType, BaseGarrisonDefinition definition) {
        DEFINITIONS.put(new Key(faction, baseType), definition);
    }

    @Nullable
    public static BaseGarrisonDefinition getDefinition(Faction faction, BaseType baseType) {
        BaseGarrisonDefinition def = DEFINITIONS.get(new Key(faction, baseType));
        if (def != null) {
            return def;
        }
        // Fallback: if faction is not explicitly mapped, check if outpost
        if (baseType == BaseType.OUTPOST) {
            return faction == Faction.ZOMBIE_HORDE
                    ? ZombieOutpostGarrisonDefinition.INSTANCE
                    : OutpostGarrisonDefinition.INSTANCE;
        }
        return null;
    }

    private record Key(Faction faction, BaseType baseType) {
    }
}
