package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.block.entity.FriendlyMortarBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;

public class FriendlyMortarModel extends DefaultedBlockGeoModel<FriendlyMortarBlockEntity> {

    public FriendlyMortarModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "friendly_mortar"));
    }

    @Override
    public void setCustomAnimations(FriendlyMortarBlockEntity animatable, long instanceId, AnimationState<FriendlyMortarBlockEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        float yaw = animatable.getYaw(animationState.getPartialTick());
        float pitch = animatable.getPitch(animationState.getPartialTick());

        getBone("Head").ifPresent(head -> {
            head.setRotY((float) Math.toRadians(-yaw));
        });

        getBone("Cannon").ifPresent(cannon -> {
            cannon.setRotX((float) Math.toRadians(pitch));
        });
    }
}
