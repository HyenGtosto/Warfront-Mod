package com.warfront.client.renderer;

import com.warfront.client.model.SupplyWagonCartModel;
import com.warfront.entity.SupplyWagonCartEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class SupplyWagonCartRenderer extends GeoEntityRenderer<SupplyWagonCartEntity> {

    public SupplyWagonCartRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new SupplyWagonCartModel());
        this.shadowRadius = 1.2F;
    }

    @Override
    public float getMotionAnimThreshold(SupplyWagonCartEntity animatable) {
        return 0.0001f;
    }
}
