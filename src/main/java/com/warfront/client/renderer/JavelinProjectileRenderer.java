package com.warfront.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.warfront.client.model.JavelinProjectileModel;
import com.warfront.entity.JavelinProjectileEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class JavelinProjectileRenderer extends GeoEntityRenderer<JavelinProjectileEntity> {

    public JavelinProjectileRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new JavelinProjectileModel());
    }

    @Override
    protected void applyRotations(JavelinProjectileEntity animatable, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
        float yawDeg = animatable.getRenderYaw();
        float pitchDeg = animatable.getRenderPitch();

        if (!animatable.isInGround()) {
            Vec3 movement = animatable.getDeltaMovement();
            double horiz = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
            if (horiz > 1.0E-4D || Math.abs(movement.y) > 1.0E-4D) {
                yawDeg = (float) Math.toDegrees(Math.atan2(movement.x, -movement.z));
                pitchDeg = (float) Math.toDegrees(Math.atan2(movement.y, Math.max(1.0E-4D, horiz)));
            }
        }

        // In javelin_projectile.geo.json, the shaft points North (-Z) along y = 13 model pixels (13 / 16 blocks).
        poseStack.mulPose(Axis.YP.rotationDegrees(-yawDeg));
        poseStack.mulPose(Axis.XP.rotationDegrees(pitchDeg));
        poseStack.translate(0.0F, -13.0F / 16.0F, 0.0F);
    }
}
