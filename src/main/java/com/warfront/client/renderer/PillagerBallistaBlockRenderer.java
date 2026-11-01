package com.warfront.client.renderer;

import com.warfront.block.entity.PillagerBallistaBlockEntity;
import com.warfront.client.model.PillagerBallistaModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class PillagerBallistaBlockRenderer extends GeoBlockRenderer<PillagerBallistaBlockEntity> {

    public PillagerBallistaBlockRenderer() {
        super(new PillagerBallistaModel());
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public boolean shouldRenderOffScreen(PillagerBallistaBlockEntity blockEntity) {
        return true;
    }
}
