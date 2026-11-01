package com.warfront.client.renderer;

import com.warfront.entity.SmokeScreenEntity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class SmokeScreenRenderer extends EntityRenderer<SmokeScreenEntity> {

    public SmokeScreenRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(SmokeScreenEntity entity) {
        return null;
    }
}
