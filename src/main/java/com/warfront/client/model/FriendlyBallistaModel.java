package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.block.entity.FriendlyBallistaBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;

public class FriendlyBallistaModel extends DefaultedBlockGeoModel<FriendlyBallistaBlockEntity> {

    public FriendlyBallistaModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "ballista_turret"));
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

        getBone("Bolt").ifPresent(bolt -> {
            if (animatable.getFireAnimTicks() <= 0 && !animatable.isBoltChambered()) {
                bolt.setHidden(true);
            } else {
                bolt.setHidden(false);
            }
        });
    }
}
