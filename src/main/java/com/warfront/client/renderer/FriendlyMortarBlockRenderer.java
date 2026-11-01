package com.warfront.client.renderer;

import com.warfront.block.entity.FriendlyMortarBlockEntity;
import com.warfront.client.model.FriendlyMortarModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class FriendlyMortarBlockRenderer extends GeoBlockRenderer<FriendlyMortarBlockEntity> {

    public FriendlyMortarBlockRenderer() {
        super(new FriendlyMortarModel());
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public boolean shouldRenderOffScreen(FriendlyMortarBlockEntity blockEntity) {
        return true;
    }
}
