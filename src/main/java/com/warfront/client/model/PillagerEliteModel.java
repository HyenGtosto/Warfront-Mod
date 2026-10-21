package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerEliteEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerEliteModel extends DefaultedEntityGeoModel<PillagerEliteEntity> {

    public PillagerEliteModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_elite"));
    }

    @Override
    public void setCustomAnimations(PillagerEliteEntity animatable, long instanceId, AnimationState<PillagerEliteEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        boolean swordDrawn = animatable.isSwordDrawn();
        boolean xbowDrawn = animatable.isCrossbowDrawn();

        // Weapon visibility handling:
        // sword_sheathed: visible only when sword is not drawn
        // sword_held: visible only when sword is drawn (held in hand across sword and crossbow stances)
        // xbow: visible only when crossbow is active
        getBone("sword_sheathed").ifPresent(b -> b.setHidden(swordDrawn));
        getBone("sword_held").ifPresent(b -> b.setHidden(!swordDrawn));
        getBone("xbow").ifPresent(b -> b.setHidden(!xbowDrawn));
    }
}
