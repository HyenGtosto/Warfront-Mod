package com.warfront.client.renderer;

import com.warfront.client.model.PillagerScoutModel;
import com.warfront.entity.PillagerScoutEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PillagerScoutRenderer extends GeoEntityRenderer<PillagerScoutEntity> {

    public PillagerScoutRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerScoutModel());
        this.shadowRadius = 0.5F;
    }

    @Override
    public float getMotionAnimThreshold(PillagerScoutEntity animatable) {
        return 0.0001f;
    }
}
