package com.warfront.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.warfront.client.model.ArtilleryShellModel;
import com.warfront.entity.ArtilleryShellEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class ArtilleryShellRenderer extends GeoEntityRenderer<ArtilleryShellEntity> {

    public ArtilleryShellRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new ArtilleryShellModel());
    }

    @Override
    protected void applyRotations(ArtilleryShellEntity animatable, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
        // Suppress default GeoEntityRenderer 180-degree South-facing lock.
        // Full 3D trajectory yaw and elevation pitch are applied to the "body" bone in ArtilleryShellModel.
    }
}
