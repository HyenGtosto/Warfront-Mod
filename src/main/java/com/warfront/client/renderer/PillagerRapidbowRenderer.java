package com.warfront.client.renderer;

import com.warfront.client.model.PillagerRapidbowModel;
import com.warfront.entity.PillagerRapidbowEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

public class PillagerRapidbowRenderer extends WarfrontGeoEntityRenderer<PillagerRapidbowEntity> {

    public PillagerRapidbowRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerRapidbowModel());
        this.shadowRadius = 0.65F;
    }
}
