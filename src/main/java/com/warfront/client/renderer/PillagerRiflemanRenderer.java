package com.warfront.client.renderer;

import com.warfront.client.model.PillagerRiflemanModel;
import com.warfront.entity.PillagerRiflemanEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PillagerRiflemanRenderer extends GeoEntityRenderer<PillagerRiflemanEntity> {

    public PillagerRiflemanRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerRiflemanModel());
        this.shadowRadius = 0.5F;
    }

    @Override
    public float getMotionAnimThreshold(PillagerRiflemanEntity animatable) {
        return 0.0001f;
    }
}
