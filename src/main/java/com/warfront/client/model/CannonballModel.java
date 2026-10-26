package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.CannonballEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class CannonballModel extends DefaultedEntityGeoModel<CannonballEntity> {

    public CannonballModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "cannonball"));
    }
}
