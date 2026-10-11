package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.block.entity.MortarBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;

public class MortarModel extends DefaultedBlockGeoModel<MortarBlockEntity> {

    public MortarModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_mortar"));
    }

    @Override
    public void setCustomAnimations(MortarBlockEntity animatable, long instanceId, AnimationState<MortarBlockEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        float yaw = animatable.getYaw(animationState.getPartialTick());
        float pitch = animatable.getPitch(animationState.getPartialTick());

        getBone("Head").ifPresent(head -> {
            head.setRotY((float) Math.toRadians(-yaw));
        });

        getBone("Cannon").ifPresent(cannon -> {
            cannon.setRotX((float) Math.toRadians(pitch));
            float recoil = animatable.getRecoilOffset(animationState.getPartialTick());
            // Recoil kick along Z (barrel extends to negative Z, so positive Z kicks back)
            cannon.setPosZ(recoil);
        });
    }
}
