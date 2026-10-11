package com.warfront.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.warfront.block.entity.AmmoRackBlockEntity;
import com.warfront.client.model.AmmoRackModel;
import net.minecraft.core.Direction;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class AmmoRackBlockRenderer extends GeoBlockRenderer<AmmoRackBlockEntity> {

    public AmmoRackBlockRenderer() {
        super(new AmmoRackModel());
    }

    @Override
    protected void rotateBlock(Direction facing, PoseStack poseStack) {
        switch (facing) {
            case SOUTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            case EAST  -> poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            case WEST  -> poseStack.mulPose(Axis.YP.rotationDegrees(270.0F));
            case NORTH -> poseStack.mulPose(Axis.YP.rotationDegrees(0.0F));
            default    -> super.rotateBlock(facing, poseStack);
        }
    }
}
