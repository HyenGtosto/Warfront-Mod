package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.BallistaBoltEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class BallistaBoltModel extends DefaultedEntityGeoModel<BallistaBoltEntity> {

    public BallistaBoltModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "ballista_bolt"));
    }
}
