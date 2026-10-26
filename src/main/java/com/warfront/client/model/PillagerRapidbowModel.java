package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerRapidbowEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerRapidbowModel extends DefaultedEntityGeoModel<PillagerRapidbowEntity> {

    public PillagerRapidbowModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_rapidbow"));
    }
}
