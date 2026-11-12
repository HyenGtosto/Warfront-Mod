package com.warfront.client.renderer;

import com.warfront.client.model.PillagerCannoneerModel;
import com.warfront.entity.PillagerCannoneerEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

public class PillagerCannoneerRenderer extends WarfrontGeoEntityRenderer<PillagerCannoneerEntity> {

    public PillagerCannoneerRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerCannoneerModel());
        this.shadowRadius = 0.55F;
    }
}
