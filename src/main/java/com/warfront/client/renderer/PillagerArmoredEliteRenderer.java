package com.warfront.client.renderer;

import com.warfront.client.model.PillagerArmoredEliteModel;
import com.warfront.entity.PillagerArmoredEliteEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PillagerArmoredEliteRenderer extends GeoEntityRenderer<PillagerArmoredEliteEntity> {

    public PillagerArmoredEliteRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerArmoredEliteModel());
        this.shadowRadius = 0.5F;
    }
}
