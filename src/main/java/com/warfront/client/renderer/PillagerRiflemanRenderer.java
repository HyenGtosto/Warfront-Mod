package com.warfront.client.renderer;

import com.warfront.client.model.PillagerRiflemanModel;
import com.warfront.entity.PillagerRiflemanEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

public class PillagerRiflemanRenderer extends WarfrontGeoEntityRenderer<PillagerRiflemanEntity> {

    public PillagerRiflemanRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerRiflemanModel());
        this.shadowRadius = 0.5F;
    }
}
