package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.EntityTerrainVariant;
import com.warfront.entity.PillagerWarriorEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerWarriorModel extends DefaultedEntityGeoModel<PillagerWarriorEntity> {

    private static final ResourceLocation DEFAULT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "textures/entity/pillager_warrior.png");
    private static final ResourceLocation DESERT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "textures/entity/pillager_warrior_desert.png");

    public PillagerWarriorModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_warrior"));
    }

    @Override
    public ResourceLocation getTextureResource(PillagerWarriorEntity animatable) {
        if (animatable != null && animatable.getTerrainVariant() == EntityTerrainVariant.DESERT) {
            return DESERT_TEXTURE;
        }
        return DEFAULT_TEXTURE;
    }
}
