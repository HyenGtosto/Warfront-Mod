package com.warfront.client.model;

import com.warfront.Warfront;
import com.warfront.entity.PillagerCommanderEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

public class PillagerCommanderModel extends DefaultedEntityGeoModel<PillagerCommanderEntity> {

    public PillagerCommanderModel() {
        super(ResourceLocation.fromNamespaceAndPath(Warfront.MOD_ID, "pillager_commander"));
    }

    @Override
    public void setCustomAnimations(PillagerCommanderEntity animatable, long instanceId, AnimationState<PillagerCommanderEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        byte action = animatable.getActionState();
        if (action == PillagerCommanderEntity.ACTION_UNSHEATHE || action == PillagerCommanderEntity.ACTION_SHEATHE) {
            getBone("sword_held").ifPresent(b -> b.setHidden(false));
            getBone("sword_sheathed").ifPresent(b -> b.setHidden(false));
        } else {
            boolean drawn = animatable.isSwordDrawn();
            getBone("sword_held").ifPresent(b -> b.setHidden(!drawn));
            getBone("sword_sheathed").ifPresent(b -> b.setHidden(drawn));
        }
    }
}
