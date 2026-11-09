package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.EntityTerrainVariant;
import com.warfront.entity.PillagerMarksmanEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerMarksmanModel extends DefaultedEntityGeoModel<PillagerMarksmanEntity> {

    private static final ResourceLocation DEFAULT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "textures/entity/pillager_marksman.png");
    private static final ResourceLocation ARCTIC_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "textures/entity/pillager_marksman_arctic.png");

    public PillagerMarksmanModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_marksman"));
    }

    @Override
    public ResourceLocation getTextureResource(PillagerMarksmanEntity animatable) {
        if (animatable != null && animatable.getTerrainVariant() == EntityTerrainVariant.ARCTIC) {
            return ARCTIC_TEXTURE;
        }
        return DEFAULT_TEXTURE;
    }
}
