package com.warfront.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.warfront.client.model.BallistaBoltModel;
import com.warfront.entity.BallistaBoltEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class BallistaBoltRenderer extends GeoEntityRenderer<BallistaBoltEntity> {

    public BallistaBoltRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new BallistaBoltModel());
    }

    @Override
    protected void applyRotations(BallistaBoltEntity animatable, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick, float nativeScale) {
        // In ballista_bolt.geo.json, the root "Bolt" bone has rotation [0, 90, 0] pointing North (-Z) at rest,
        // and its shaft centerline is at y = 25 model units (25 / 16 blocks).
        Vec3 movement = animatable.getDeltaMovement();
        double vx = movement.x;
        double vy = movement.y;
        double vz = movement.z;
        double horizSpeed = Math.sqrt(vx * vx + vz * vz);

        if (horizSpeed > 1.0E-4D || Math.abs(vy) > 1.0E-4D) {
            float yawDeg = (float) Math.toDegrees(Math.atan2(vx, -vz));
            float pitchDeg = (float) Math.toDegrees(Math.atan2(vy, Math.max(1.0E-4D, horizSpeed)));

            poseStack.mulPose(Axis.YP.rotationDegrees(-yawDeg));
            poseStack.mulPose(Axis.XP.rotationDegrees(pitchDeg));
        }

        // Shift the bolt down by 25 model pixels so the shaft centerline aligns with the entity's position
        poseStack.translate(0.0F, -25.0F / 16.0F, 0.0F);
    }
}
