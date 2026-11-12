package com.warfront.client.renderer;

import com.warfront.client.model.PillagerEliteModel;
import com.warfront.entity.PillagerEliteEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

public class PillagerEliteRenderer extends WarfrontGeoEntityRenderer<PillagerEliteEntity> {

    public PillagerEliteRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerEliteModel());
        this.shadowRadius = 0.6F;
    }
}
