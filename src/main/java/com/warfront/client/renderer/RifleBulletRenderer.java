package com.warfront.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.warfront.entity.RifleBulletEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

public class RifleBulletRenderer extends EntityRenderer<RifleBulletEntity> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("warfront", "textures/entity/rifle_bullet.png");

    public RifleBulletRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(RifleBulletEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(RifleBulletEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.pushPose();
        float yRot = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
        float xRot = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
        poseStack.mulPose(Axis.YP.rotationDegrees(yRot - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(xRot));

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutout(TEXTURE));
        Matrix4f matrix = poseStack.last().pose();

        float length = 0.30F;
        float width = 0.035F;

        // Quad 1 (horizontal plane)
        vertex(consumer, matrix, -length, 0, -width, 0.0F, 0.0F, packedLight);
        vertex(consumer, matrix, length, 0, -width, 1.0F, 0.0F, packedLight);
        vertex(consumer, matrix, length, 0, width, 1.0F, 1.0F, packedLight);
        vertex(consumer, matrix, -length, 0, width, 0.0F, 1.0F, packedLight);

        // Quad 2 (vertical plane)
        vertex(consumer, matrix, -length, -width, 0, 0.0F, 0.0F, packedLight);
        vertex(consumer, matrix, length, -width, 0, 1.0F, 0.0F, packedLight);
        vertex(consumer, matrix, length, width, 0, 1.0F, 1.0F, packedLight);
        vertex(consumer, matrix, -length, width, 0, 0.0F, 1.0F, packedLight);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    private void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, float u, float v, int packedLight) {
        consumer.addVertex(matrix, x, y, z)
                .setColor(255, 230, 160, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(packedLight)
                .setNormal(0.0F, 1.0F, 0.0F);
    }
}
