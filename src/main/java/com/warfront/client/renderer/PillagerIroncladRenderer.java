package com.warfront.client.renderer;

import com.warfront.client.model.PillagerIroncladModel;
import com.warfront.entity.PillagerIroncladEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

public class PillagerIroncladRenderer extends WarfrontGeoEntityRenderer<PillagerIroncladEntity> {

    public PillagerIroncladRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerIroncladModel());
        this.shadowRadius = 0.9F;
    }
}
