package com.warfront.client.renderer;

import com.warfront.block.entity.MortarBlockEntity;
import com.warfront.client.model.MortarModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class MortarBlockRenderer extends GeoBlockRenderer<MortarBlockEntity> {

    public MortarBlockRenderer() {
        super(new MortarModel());
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public boolean shouldRenderOffScreen(MortarBlockEntity blockEntity) {
        return true;
    }
}
