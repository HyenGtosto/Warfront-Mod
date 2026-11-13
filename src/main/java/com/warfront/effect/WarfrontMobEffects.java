package com.warfront.effect;

import com.warfront.Warfront;
import com.warfront.stim.StimConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry of custom MobEffects for Warfront bio-stims.
 */
public final class WarfrontMobEffects {

    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, Warfront.MOD_ID);

    public static final DeferredHolder<MobEffect, MobEffect> COMBAT_STIM = MOB_EFFECTS.register(
            "combat_stim",
            () -> new StimMobEffect(MobEffectCategory.BENEFICIAL, 0xD93838)
                    .addAttributeModifier(
                            Attributes.MAX_HEALTH,
                            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "combat_stim_max_health"),
                            StimConstants.STIM_MAX_HEALTH_COST,
                            AttributeModifier.Operation.ADD_VALUE
                    )
    );

    public static final DeferredHolder<MobEffect, MobEffect> ACROBATICS_STIM = MOB_EFFECTS.register(
            "acrobatics_stim",
            () -> new StimMobEffect(MobEffectCategory.BENEFICIAL, 0x22D3EE)
                    .addAttributeModifier(
                            Attributes.MAX_HEALTH,
                            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "acrobatics_stim_max_health"),
                            StimConstants.STIM_MAX_HEALTH_COST,
                            AttributeModifier.Operation.ADD_VALUE
                    )
    );

    public static final DeferredHolder<MobEffect, MobEffect> HEALING_STIM = MOB_EFFECTS.register(
            "healing_stim",
            () -> new StimMobEffect(MobEffectCategory.BENEFICIAL, 0x22C55E)
    );

    public static final DeferredHolder<MobEffect, MobEffect> STIM_USAGE = MOB_EFFECTS.register(
            "stim_usage",
            () -> new StimMobEffect(MobEffectCategory.NEUTRAL, 0xF59E0B)
    );

    private WarfrontMobEffects() {
    }
}
