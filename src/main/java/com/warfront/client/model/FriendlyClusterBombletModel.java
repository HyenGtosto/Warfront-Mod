package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.FriendlyClusterBombletEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class FriendlyClusterBombletModel extends DefaultedEntityGeoModel<FriendlyClusterBombletEntity> {

    public FriendlyClusterBombletModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "cannon_bomb"));
    }
}
