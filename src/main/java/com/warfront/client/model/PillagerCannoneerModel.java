package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerCannoneerEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerCannoneerModel extends DefaultedEntityGeoModel<PillagerCannoneerEntity> {

    public PillagerCannoneerModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_cannoneer"));
    }
}
