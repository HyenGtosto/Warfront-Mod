package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.CannonBombEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class CannonBombModel extends DefaultedEntityGeoModel<CannonBombEntity> {

    public CannonBombModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "cannon_bomb"));
    }
}
