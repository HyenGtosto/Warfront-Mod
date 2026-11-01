package com.warfront.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.warfront.client.model.FriendlyArtilleryShellModel;
import com.warfront.entity.FriendlyArtilleryShellEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class FriendlyArtilleryShellRenderer extends GeoEntityRenderer<FriendlyArtilleryShellEntity> {

    public FriendlyArtilleryShellRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new FriendlyArtilleryShellModel());
    }

    @Override
    protected void applyRotations(FriendlyArtilleryShellEntity animatable, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
        // Suppress default GeoEntityRenderer 180-degree South-facing lock.
        // Full 3D trajectory yaw and elevation pitch are applied directly to the "body" bone in FriendlyArtilleryShellModel.
    }
}
