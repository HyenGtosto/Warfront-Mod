package com.warfront.client.renderer;

import com.warfront.block.entity.FriendlyBallistaBlockEntity;
import com.warfront.client.model.FriendlyBallistaModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class FriendlyBallistaBlockRenderer extends GeoBlockRenderer<FriendlyBallistaBlockEntity> {

    public FriendlyBallistaBlockRenderer() {
        super(new FriendlyBallistaModel());
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public boolean shouldRenderOffScreen(FriendlyBallistaBlockEntity blockEntity) {
        return true;
    }
}
