package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerMarksmanEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerMarksmanModel extends DefaultedEntityGeoModel<PillagerMarksmanEntity> {

    public PillagerMarksmanModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_marksman"));
    }
}
