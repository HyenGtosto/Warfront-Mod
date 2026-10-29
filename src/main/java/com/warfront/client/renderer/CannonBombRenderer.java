package com.warfront.client.renderer;

import com.warfront.client.model.CannonBombModel;
import com.warfront.entity.CannonBombEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class CannonBombRenderer extends GeoEntityRenderer<CannonBombEntity> {

    public CannonBombRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new CannonBombModel());
        this.shadowRadius = 0.25F;
    }
}
