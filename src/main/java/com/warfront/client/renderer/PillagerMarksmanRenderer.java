package com.warfront.client.renderer;

import com.warfront.client.model.PillagerMarksmanModel;
import com.warfront.entity.PillagerMarksmanEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PillagerMarksmanRenderer extends GeoEntityRenderer<PillagerMarksmanEntity> {

    public PillagerMarksmanRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerMarksmanModel());
        this.shadowRadius = 0.5F;
    }
}
