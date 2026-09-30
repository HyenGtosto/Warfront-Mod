package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerArmoredEliteEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerArmoredEliteModel extends DefaultedEntityGeoModel<PillagerArmoredEliteEntity> {

    public PillagerArmoredEliteModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_armored_elite"));
    }
}
