package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.SupplyWagonCartEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class SupplyWagonCartModel extends DefaultedEntityGeoModel<SupplyWagonCartEntity> {

    public SupplyWagonCartModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "supply_wagon_cart"));
    }
}
