package com.warfront.client.renderer;

import com.warfront.client.model.PillagerLegionnaireModel;
import com.warfront.entity.PillagerLegionnaireEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

public class PillagerLegionnaireRenderer extends WarfrontGeoEntityRenderer<PillagerLegionnaireEntity> {

    public PillagerLegionnaireRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerLegionnaireModel());
        this.shadowRadius = 0.55F;
    }
}
