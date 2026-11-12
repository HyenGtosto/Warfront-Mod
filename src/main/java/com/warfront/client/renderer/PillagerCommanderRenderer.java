package com.warfront.client.renderer;

import com.warfront.client.model.PillagerCommanderModel;
import com.warfront.entity.PillagerCommanderEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

public class PillagerCommanderRenderer extends WarfrontGeoEntityRenderer<PillagerCommanderEntity> {

    public PillagerCommanderRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerCommanderModel());
        this.shadowRadius = 0.5F;
    }
}
