package com.warfront.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * Custom MobEffect subclass exposing the protected vanilla constructor for Warfront bio-stim effects.
 */
public class StimMobEffect extends MobEffect {

    public StimMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }
}
