package com.warfront.client.renderer;

import com.warfront.client.model.PillagerWarriorModel;
import com.warfront.entity.PillagerWarriorEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PillagerWarriorRenderer extends GeoEntityRenderer<PillagerWarriorEntity> {

    public PillagerWarriorRenderer(EntityRendererProvider.Context renderManager) {
        super(renderManager, new PillagerWarriorModel());
        this.shadowRadius = 0.5F;
    }

    @Override
    public float getMotionAnimThreshold(PillagerWarriorEntity animatable) {
        return 0.0001f;
    }
}
