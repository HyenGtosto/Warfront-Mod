package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerRiflemanEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerRiflemanModel extends DefaultedEntityGeoModel<PillagerRiflemanEntity> {

    public PillagerRiflemanModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_rifleman"));
    }

    @Override
    public void setCustomAnimations(PillagerRiflemanEntity animatable, long instanceId, AnimationState<PillagerRiflemanEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        boolean useDummy = animatable.isUsingDummyArm();

        // During bolt cycling and reload animations, the real arm cubes are hidden and the dummy right arm is visible.
        // During all other states, the real arm is visible and the dummy arm is hidden.
        getBone("pillager_parts_upper").ifPresent(b -> b.setHidden(useDummy));
        getBone("pillager_parts_lower").ifPresent(b -> b.setHidden(useDummy));
        getBone("dummy_right_arm").ifPresent(b -> b.setHidden(!useDummy));
    }
}
