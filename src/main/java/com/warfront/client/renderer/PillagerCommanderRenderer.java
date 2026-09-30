package com.warfront.client.renderer;

import com.warfront.client.model.PillagerCommanderModel;
import com.warfront.entity.PillagerCommanderEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PillagerCommanderRenderer extends GeoEntityRenderer<PillagerCommanderEntity> {

    public PillagerCommanderRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerCommanderModel());
        this.shadowRadius = 0.5F;
    }
}
