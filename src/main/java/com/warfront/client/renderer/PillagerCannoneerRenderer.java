package com.warfront.client.renderer;

import com.warfront.client.model.PillagerCannoneerModel;
import com.warfront.entity.PillagerCannoneerEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PillagerCannoneerRenderer extends GeoEntityRenderer<PillagerCannoneerEntity> {

    public PillagerCannoneerRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerCannoneerModel());
        this.shadowRadius = 0.55F;
    }

    @Override
    public float getMotionAnimThreshold(PillagerCannoneerEntity animatable) {
        return 0.0001f;
    }
}
