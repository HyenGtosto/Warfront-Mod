package com.warfront.client.renderer;

import com.warfront.client.model.PillagerWarriorModel;
import com.warfront.entity.PillagerWarriorEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

public class PillagerWarriorRenderer extends WarfrontGeoEntityRenderer<PillagerWarriorEntity> {

    public PillagerWarriorRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerWarriorModel());
        this.shadowRadius = 0.5F;
    }
}
