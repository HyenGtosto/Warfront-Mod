package com.warfront.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.warfront.client.model.PillagerJavelinItemModel;
import com.warfront.item.PillagerJavelinItem;
import net.minecraft.client.renderer.MultiBufferSource;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * Renders {@link PillagerJavelinItem} using {@code javelin_projectile.geo.json},
 * rotating its horizontal -Z shaft into vertical +Y alignment centered on the item pivot
 * to match vanilla Trident hand and GUI display conventions.
 */
public class PillagerJavelinItemRenderer extends GeoItemRenderer<PillagerJavelinItem> {

    public PillagerJavelinItemRenderer() {
        super(new PillagerJavelinItemModel());
        this.useAlternateGuiLighting();
    }

    @Override
    public void preRender(
            PoseStack poseStack,
            PillagerJavelinItem animatable,
            BakedGeoModel model,
            MultiBufferSource bufferSource,
            VertexConsumer buffer,
            boolean isReRender,
            float partialTick,
            int packedLight,
            int packedOverlay,
            int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
        if (!isReRender) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            poseStack.translate(0.0F, -13.0F / 16.0F, 0.0F);
        }
    }
}
