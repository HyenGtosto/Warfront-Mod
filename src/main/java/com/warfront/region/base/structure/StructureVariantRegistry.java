package com.warfront.region.base.structure;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of premade base structure variants mapped by {@link Faction} and {@link BaseType}.
 *
 * Supports future multi-variant selection while defaulting to the primary premade blueprint.
 */
public final class StructureVariantRegistry {

    private static final Map<Faction, Map<BaseType, List<PremadeStructure>>> VARIANTS = new EnumMap<>(Faction.class);

    private StructureVariantRegistry() {
    }

    /**
     * Registers a structure blueprint variant for a faction and tier.
     */
    public static synchronized void registerVariant(Faction faction, BaseType baseType, PremadeStructure structure) {
        if (faction == null || baseType == null || structure == null) {
            return;
        }
        VARIANTS.computeIfAbsent(faction, f -> new EnumMap<>(BaseType.class))
                .computeIfAbsent(baseType, b -> new ArrayList<>())
                .add(structure);
    }

    /**
     * Retrieves the default (primary) structure variant for the given faction and tier.
     */
    public static PremadeStructure getDefaultVariant(Faction faction, BaseType baseType) {
        List<PremadeStructure> list = getVariants(faction, baseType);
        if (list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    /**
     * Retrieves all registered structure variants for the given faction and tier.
     */
    public static List<PremadeStructure> getVariants(Faction faction, BaseType baseType) {
        Map<BaseType, List<PremadeStructure>> factionMap = VARIANTS.get(faction);
        if (factionMap == null) {
            return List.of();
        }
        List<PremadeStructure> list = factionMap.get(baseType);
        return list != null ? list : List.of();
    }
}
