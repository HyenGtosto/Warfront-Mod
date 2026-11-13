package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerLegionnaireEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerLegionnaireModel extends DefaultedEntityGeoModel<PillagerLegionnaireEntity> {

    public PillagerLegionnaireModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_legionnaire"));
    }

    @Override
    public void setCustomAnimations(PillagerLegionnaireEntity animatable, long instanceId, AnimationState<PillagerLegionnaireEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        byte stance = animatable.getStance();
        byte action = animatable.getActionState();
        boolean broken = animatable.isShieldBroken();

        // 1. Shield bone visibility:
        //    - Broken: BOTH shield bones hidden permanently.
        //    - During equip/unequip transitions: leave both unhidden so stepped 1/24s scale keyframes perform the swap.
        //    - Steady states: back-slung vs front-held according to stance/action.
        if (broken) {
            getBone("shield_held").ifPresent(b -> b.setHidden(true));
            getBone("shield_back").ifPresent(b -> b.setHidden(true));
        } else if (action == PillagerLegionnaireEntity.ACTION_TWOHANDED_TO_SHIELDED
                || action == PillagerLegionnaireEntity.ACTION_SHIELDED_TO_TWOHANDED) {
            getBone("shield_held").ifPresent(b -> b.setHidden(false));
            getBone("shield_back").ifPresent(b -> b.setHidden(false));
        } else {
            boolean shieldOnFront = animatable.isShieldInFront();
            getBone("shield_held").ifPresent(b -> b.setHidden(!shieldOnFront));
            getBone("shield_back").ifPresent(b -> b.setHidden(shieldOnFront));
        }

        // 2. Spear bone visibility:
        //    - During spear_throw & spear_pick_up: keep unhidden so stepped 1/24s scale keyframes hide/unhide on release/grab.
        //    - Otherwise hidden exclusively in SPEARLESS_SHIELD stance.
        if (action == PillagerLegionnaireEntity.ACTION_SPEAR_THROW
                || action == PillagerLegionnaireEntity.ACTION_SPEAR_PICK_UP) {
            getBone("spear").ifPresent(b -> b.setHidden(false));
        } else {
            boolean hideSpear = stance == PillagerLegionnaireEntity.STANCE_SPEARLESS_SHIELD;
            getBone("spear").ifPresent(b -> b.setHidden(hideSpear));
        }
    }
}
