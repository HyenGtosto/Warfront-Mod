package com.warfront.client.renderer;

import com.warfront.client.model.CannonballModel;
import com.warfront.entity.CannonballEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class CannonballRenderer extends GeoEntityRenderer<CannonballEntity> {

    public CannonballRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new CannonballModel());
        this.shadowRadius = 0.25F;
    }
}
