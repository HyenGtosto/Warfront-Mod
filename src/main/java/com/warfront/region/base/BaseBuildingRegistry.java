package com.warfront.region.base;

import com.warfront.region.BaseType;
import com.warfront.region.Faction;

import com.warfront.Warfront;
import com.warfront.region.base.structure.StructureVariantRegistry;
import com.warfront.region.base.structure.TemplatePremadeStructure;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry of building generators mapped by {@link Faction} and {@link BaseType}.
 *
 * Designed for modular expansion: concrete building blueprints (e.g. Pillager Outposts,
 * Zombie Spires, Humanity Fortresses) can be registered per faction and tier.
 * Currently, Pillager Outposts load native NBT structure templates from data/warfront/structure/.
 * All other enemy base tiers deploy the test cobblestone monolith until their NBT templates are created.
 */
public final class BaseBuildingRegistry {

    private static final BaseBuildingGenerator UNIMPLEMENTED_PLACEHOLDER_MONOLITH = new CobblestoneMonolithGenerator();
    private static final Map<Faction, Map<BaseType, BaseBuildingGenerator>> GENERATORS = new EnumMap<>(Faction.class);

    static {
        // Register native NBT structure template for Pillager Outposts
        ResourceLocation outpostTemplate = ResourceLocation.fromNamespaceAndPath(
                Warfront.MOD_ID, "base/outpost/pillager_outpost");
        StructureVariantRegistry.registerVariant(
                Faction.PILLAGER_CONQUERORS,
                BaseType.OUTPOST,
                new TemplatePremadeStructure(outpostTemplate, 27, 27, 23)
        );

        // Initialize placeholder generators for unimplemented base tiers/factions
        for (Faction faction : Faction.values()) {
            if (faction != Faction.UNCLAIMED && faction != Faction.HUMANITY) {
                Map<BaseType, BaseBuildingGenerator> factionMap = new EnumMap<>(BaseType.class);
                factionMap.put(BaseType.OUTPOST, UNIMPLEMENTED_PLACEHOLDER_MONOLITH);
                factionMap.put(BaseType.HEADQUARTERS, UNIMPLEMENTED_PLACEHOLDER_MONOLITH);
                factionMap.put(BaseType.MEGA_BASE, UNIMPLEMENTED_PLACEHOLDER_MONOLITH);
                GENERATORS.put(faction, factionMap);
            }
        }

        // Active concrete structure generators
        register(Faction.PILLAGER_CONQUERORS, BaseType.OUTPOST, new PillagerOutpostGenerator());
    }

    private BaseBuildingRegistry() {
    }

    /**
     * Retrieves the building generator registered for the specified faction and base tier.
     * Returns an unimplemented placeholder building generator if the tier/faction has not yet been implemented.
     * Returns null if baseType is NONE or arguments are null.
     */
    public static BaseBuildingGenerator getGenerator(Faction faction, BaseType baseType) {
        if (faction == null || baseType == null || baseType == BaseType.NONE) {
            return null;
        }

        Map<BaseType, BaseBuildingGenerator> factionMap = GENERATORS.get(faction);
        if (factionMap != null) {
            BaseBuildingGenerator generator = factionMap.get(baseType);
            if (generator != null) {
                return generator;
            }
        }

        return UNIMPLEMENTED_PLACEHOLDER_MONOLITH;
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
