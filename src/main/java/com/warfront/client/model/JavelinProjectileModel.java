package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.JavelinProjectileEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class JavelinProjectileModel extends DefaultedEntityGeoModel<JavelinProjectileEntity> {

    public JavelinProjectileModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "javelin_projectile"));
    }
}
