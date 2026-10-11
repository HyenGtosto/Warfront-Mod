package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.ArtilleryShellEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class ArtilleryShellModel extends DefaultedEntityGeoModel<ArtilleryShellEntity> {

    public ArtilleryShellModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "artillery_shell"));
    }

    @Override
    public void setCustomAnimations(ArtilleryShellEntity animatable, long instanceId, AnimationState<ArtilleryShellEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        Vec3 movement = animatable.getDeltaMovement();
        double vx = movement.x;
        double vy = movement.y;
        double vz = movement.z;
        double horizSpeed = Math.sqrt(vx * vx + vz * vz);

        if (horizSpeed > 1.0E-4 || Math.abs(vy) > 1.0E-4) {
            // Yaw azimuth: shell nose points North (-Z) at yaw 0
            float yaw = (float) Math.atan2(vx, -vz);
            // Elevation pitch: +X rot tilts up when rising (vy > 0), 0 at apex, -X rot tilts down when plunging (vy < 0)
            float pitch = (float) Math.atan2(vy, Math.max(1.0E-4, horizSpeed));

            getBone("body").ifPresent(body -> {
                body.setRotY(-yaw);
                body.setRotX(pitch);
                body.setRotZ(0.0F);
            });
        }
    }
}
