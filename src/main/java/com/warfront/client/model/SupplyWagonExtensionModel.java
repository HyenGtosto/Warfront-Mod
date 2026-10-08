package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.SupplyWagonExtensionEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class SupplyWagonExtensionModel extends DefaultedEntityGeoModel<SupplyWagonExtensionEntity> {

    public SupplyWagonExtensionModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "supply_wagon_extension"));
    }
}
