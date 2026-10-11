package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.block.entity.AmmoRackBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;

public class AmmoRackModel extends DefaultedBlockGeoModel<AmmoRackBlockEntity> {

    public AmmoRackModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "ammo_rack"));
    }
}
