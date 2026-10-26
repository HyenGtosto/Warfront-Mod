package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerIroncladEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerIroncladModel extends DefaultedEntityGeoModel<PillagerIroncladEntity> {

    public PillagerIroncladModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_ironclad"));
    }
}
