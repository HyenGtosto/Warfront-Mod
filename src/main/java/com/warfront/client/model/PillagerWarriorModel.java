package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerWarriorEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerWarriorModel extends DefaultedEntityGeoModel<PillagerWarriorEntity> {

    public PillagerWarriorModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_warrior"));
    }
}
