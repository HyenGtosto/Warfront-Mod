package com.warfront.client.renderer;

import com.warfront.client.model.FriendlyClusterBombletModel;
import com.warfront.entity.FriendlyClusterBombletEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class FriendlyClusterBombletRenderer extends GeoEntityRenderer<FriendlyClusterBombletEntity> {

    public FriendlyClusterBombletRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new FriendlyClusterBombletModel());
        this.shadowRadius = 0.20F;
    }
}
