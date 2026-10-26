package com.warfront.client.renderer;

import com.warfront.client.model.PillagerRapidbowModel;
import com.warfront.entity.PillagerRapidbowEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PillagerRapidbowRenderer extends GeoEntityRenderer<PillagerRapidbowEntity> {

    public PillagerRapidbowRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerRapidbowModel());
        this.shadowRadius = 0.65F;
    }

    @Override
    public float getMotionAnimThreshold(PillagerRapidbowEntity animatable) {
        return 0.0001f;
    }
}
