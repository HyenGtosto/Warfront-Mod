package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.block.entity.PillagerBallistaBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;

public class PillagerBallistaModel extends DefaultedBlockGeoModel<PillagerBallistaBlockEntity> {

    public PillagerBallistaModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_ballista"));
    }

    @Override
    public void setCustomAnimations(PillagerBallistaBlockEntity animatable, long instanceId, AnimationState<PillagerBallistaBlockEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        float yaw = animatable.getYaw(animationState.getPartialTick());
        float pitch = animatable.getPitch(animationState.getPartialTick());

        // Horizontal traverse uses "Body" bone around Y axis
        getBone("Body").ifPresent(body -> {
            body.setRotY((float) Math.toRadians(-yaw));
        });

        // Vertical pitch elevation uses "Ballista" bone around its local Z trunnion axis
        // (since the root "Base" bone is rotated 90 degrees around Y to face North -Z)
        getBone("Ballista").ifPresent(ballista -> {
            ballista.setRotZ((float) Math.toRadians(-pitch));
        });
    }
}
