package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerScoutEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerScoutModel extends DefaultedEntityGeoModel<PillagerScoutEntity> {

    public PillagerScoutModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_scout"));
    }

    @Override
    public void setCustomAnimations(PillagerScoutEntity animatable, long instanceId, AnimationState<PillagerScoutEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        byte action = animatable.getActionState();
        if (action == PillagerScoutEntity.ACTION_UNSHEATHE || action == PillagerScoutEntity.ACTION_SHEATHE) {
            getBone("kniferightheld").ifPresent(b -> b.setHidden(false));
            getBone("knifeleftheld").ifPresent(b -> b.setHidden(false));
            getBone("kniferightsheathed").ifPresent(b -> b.setHidden(false));
            getBone("knifeleftsheathed").ifPresent(b -> b.setHidden(false));
        } else {
            boolean drawn = animatable.areDaggersDrawn();
            getBone("kniferightheld").ifPresent(b -> b.setHidden(!drawn));
            getBone("knifeleftheld").ifPresent(b -> b.setHidden(!drawn));
            getBone("kniferightsheathed").ifPresent(b -> b.setHidden(drawn));
            getBone("knifeleftsheathed").ifPresent(b -> b.setHidden(drawn));
        }
    }
}
