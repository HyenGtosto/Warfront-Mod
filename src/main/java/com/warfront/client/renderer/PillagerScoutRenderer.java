package com.warfront.client.renderer;

import com.warfront.client.model.PillagerScoutModel;
import com.warfront.entity.PillagerScoutEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

public class PillagerScoutRenderer extends WarfrontGeoEntityRenderer<PillagerScoutEntity> {

    public PillagerScoutRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerScoutModel());
        this.shadowRadius = 0.5F;
    }
}
