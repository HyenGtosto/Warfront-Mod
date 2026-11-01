package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.block.entity.FriendlyBallistaBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;

public class FriendlyBallistaModel extends DefaultedBlockGeoModel<FriendlyBallistaBlockEntity> {

    public FriendlyBallistaModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "friendly_ballista"));
    }

    @Override
    public void setCustomAnimations(FriendlyBallistaBlockEntity animatable, long instanceId, AnimationState<FriendlyBallistaBlockEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        float yaw = animatable.getYaw(animationState.getPartialTick());
        float pitch = animatable.getPitch(animationState.getPartialTick());

        getBone("Body").ifPresent(body -> {
            body.setRotY((float) Math.toRadians(-yaw));
        });

        getBone("Ballista").ifPresent(ballista -> {
            ballista.setRotZ((float) Math.toRadians(-pitch));
        });
    }
}
