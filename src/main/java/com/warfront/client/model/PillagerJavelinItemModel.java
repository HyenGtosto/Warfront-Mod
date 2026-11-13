package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.item.PillagerJavelinItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/**
 * Reuses the exact {@code javelin_projectile.geo.json} model and {@code javelin_projectile.png}
 * texture for the held/inventory {@link PillagerJavelinItem}.
 */
public class PillagerJavelinItemModel extends DefaultedEntityGeoModel<PillagerJavelinItem> {

    public PillagerJavelinItemModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "javelin_projectile"));
    }
}
