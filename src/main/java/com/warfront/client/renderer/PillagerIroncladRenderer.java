package com.warfront.client.renderer;

import com.warfront.client.model.PillagerIroncladModel;
import com.warfront.entity.PillagerIroncladEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PillagerIroncladRenderer extends GeoEntityRenderer<PillagerIroncladEntity> {

    public PillagerIroncladRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerIroncladModel());
        this.shadowRadius = 0.9F;
    }

    @Override
    public float getMotionAnimThreshold(PillagerIroncladEntity animatable) {
        return 0.0001f;
    }
}
