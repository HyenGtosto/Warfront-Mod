package com.warfront.client.renderer;

import com.warfront.client.model.PillagerEliteModel;
import com.warfront.entity.PillagerEliteEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PillagerEliteRenderer extends GeoEntityRenderer<PillagerEliteEntity> {

    public PillagerEliteRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerEliteModel());
        this.shadowRadius = 0.6F;
    }

    @Override
    public float getMotionAnimThreshold(PillagerEliteEntity animatable) {
        return 0.0001f;
    }
}
