package com.warfront.client.renderer;

import com.warfront.client.model.SupplyWagonExtensionModel;
import com.warfront.entity.SupplyWagonExtensionEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class SupplyWagonExtensionRenderer extends GeoEntityRenderer<SupplyWagonExtensionEntity> {

    public SupplyWagonExtensionRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new SupplyWagonExtensionModel());
        this.shadowRadius = 1.2F;
    }

    @Override
    public float getMotionAnimThreshold(SupplyWagonExtensionEntity animatable) {
        return 0.0001f;
    }
}
