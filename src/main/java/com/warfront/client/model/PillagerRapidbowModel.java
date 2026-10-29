package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerRapidbowEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerRapidbowModel extends DefaultedEntityGeoModel<PillagerRapidbowEntity> {

    public PillagerRapidbowModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_rapidbow"));
    }

    @Override
    public void setCustomAnimations(PillagerRapidbowEntity animatable, long instanceId, AnimationState<PillagerRapidbowEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        byte state = animatable.getCombatState();

        if (state == PillagerRapidbowEntity.STATE_MOBILE) {
            // Mobile roaming: ground emplacement hidden; backpack and carried tripod visible
            getBone("bow_mechanism").ifPresent(b -> b.setHidden(true));
            getBone("bow_body_carried").ifPresent(b -> b.setHidden(false));
            getBone("tripod_held").ifPresent(b -> b.setHidden(false));
        } else if (state == PillagerRapidbowEntity.STATE_DEPLOYING || state == PillagerRapidbowEntity.STATE_UNDEPLOYING) {
            // Transition animations: all visible in code so keyframe scale swaps execute organically
            getBone("bow_mechanism").ifPresent(b -> b.setHidden(false));
            getBone("bow_body_carried").ifPresent(b -> b.setHidden(false));
            getBone("tripod_held").ifPresent(b -> b.setHidden(false));
        } else {
            // Deployed operations (AIMING, SHOOTING, RELOAD): ground emplacement visible; carried props hidden
            getBone("bow_mechanism").ifPresent(b -> b.setHidden(false));
            getBone("bow_body_carried").ifPresent(b -> b.setHidden(true));
            getBone("tripod_held").ifPresent(b -> b.setHidden(true));
        }
    }
}
