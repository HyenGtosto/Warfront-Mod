package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.block.entity.LoadedShellType;
import com.warfront.entity.FriendlyArtilleryShellEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class FriendlyArtilleryShellModel extends DefaultedEntityGeoModel<FriendlyArtilleryShellEntity> {

    private static final ResourceLocation HE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "textures/entity/high_explosive_shell.png");
    private static final ResourceLocation INCENDIARY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "textures/entity/incendiary_shell.png");
    private static final ResourceLocation AIRBURST_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "textures/entity/airburst_shell.png");
    private static final ResourceLocation SMOKE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "textures/entity/smoke_shell.png");

    public FriendlyArtilleryShellModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "high_explosive_shell"));
    }

    @Override
    public ResourceLocation getTextureResource(FriendlyArtilleryShellEntity animatable) {
        return switch (animatable.getShellType()) {
            case HE -> HE_TEXTURE;
            case INCENDIARY -> INCENDIARY_TEXTURE;
            case AIRBURST -> AIRBURST_TEXTURE;
            case SMOKE -> SMOKE_TEXTURE;
            case NONE -> HE_TEXTURE;
        };
    }

    @Override
    public void setCustomAnimations(FriendlyArtilleryShellEntity animatable, long instanceId, AnimationState<FriendlyArtilleryShellEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        Vec3 movement = animatable.getDeltaMovement();
        double vx = movement.x;
        double vy = movement.y;
        double vz = movement.z;
        double horizSpeed = Math.sqrt(vx * vx + vz * vz);

        if (horizSpeed > 1.0E-4 || Math.abs(vy) > 1.0E-4) {
            float yaw = (float) Math.atan2(vx, -vz);
            float pitch = (float) Math.atan2(vy, Math.max(1.0E-4, horizSpeed));

            getBone("body").ifPresent(body -> {
                body.setRotY(-yaw);
                body.setRotX(pitch);
                body.setRotZ(0.0F);
            });
        }
    }
}
