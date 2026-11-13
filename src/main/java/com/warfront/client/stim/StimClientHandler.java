package com.warfront.client.stim;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.warfront.effect.WarfrontMobEffects;
import com.warfront.entity.AlliedFactionHelper;
import com.warfront.network.stim.TriggerStimMobilityPayload;
import com.warfront.stim.StimClientState;
import com.warfront.stim.StimConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/**
 * Client-side handler for Acrobatics Stim input (mid-air double jump & directional WASD dash)
 * and Combat Stim hostile entity marking.
 */
public final class StimClientHandler {

    private record MoveKeySpec(int forwardSign, int strafeSign) {
    }

    private static final MoveKeySpec[] MOVE_SPECS = {
            new MoveKeySpec(1, 0),   // W (forward)
            new MoveKeySpec(-1, 0),  // S (backward)
            new MoveKeySpec(0, 1),   // A (left)
            new MoveKeySpec(0, -1)   // D (right)
    };

    private static final boolean[] PREV_MOVE_DOWN = new boolean[4];
    private static final long[] LAST_MOVE_TAP_TICK = {-100L, -100L, -100L, -100L};

    private static long clientTickCounter = 0L;
    private static boolean wasOnGround = true;
    private static boolean wasJumpDown = false;
    private static long lastDoubleJumpSentTick = -100L;
    private static long lastDashSentTick = -100L;

    private StimClientHandler() {
    }

    public static void clear() {
        StimClientState.clear();
        for (int i = 0; i < 4; i++) {
            PREV_MOVE_DOWN[i] = false;
            LAST_MOVE_TAP_TICK[i] = -100L;
        }
        clientTickCounter = 0L;
        wasOnGround = true;
        wasJumpDown = false;
        lastDoubleJumpSentTick = -100L;
        lastDashSentTick = -100L;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.isPaused()) {
            return;
        }

        clientTickCounter++;
        StimClientState.tickClient();

        boolean canControl = mc.screen == null && player.isAlive() && !player.isSpectator();
        boolean hasAcrobatics = canControl && player.hasEffect(WarfrontMobEffects.ACROBATICS_STIM);

        handleDoubleJumpInput(mc, player, hasAcrobatics);
        handleDirectionalDashInput(mc, player, hasAcrobatics);

        wasOnGround = player.onGround();
    }

    private static void handleDoubleJumpInput(Minecraft mc, LocalPlayer player, boolean hasAcrobatics) {
        boolean jumpDown = mc.options.keyJump.isDown();
        boolean jumpPressed = jumpDown && !wasJumpDown;
        wasJumpDown = jumpDown;

        if (!hasAcrobatics || !jumpPressed) {
            return;
        }
        if (wasOnGround || player.onGround() || player.isInWater() || player.isInLava()
                || player.onClimbable() || player.isFallFlying() || player.isPassenger()
                || player.getAbilities().flying) {
            return;
        }
        if (player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return;
        }
        if (StimClientState.getDoubleJumpCooldownTicks() > 0 || (clientTickCounter - lastDoubleJumpSentTick) <= 5L) {
            return;
        }

        lastDoubleJumpSentTick = clientTickCounter;
        PacketDistributor.sendToServer(new TriggerStimMobilityPayload(false, 0.0F, 0.0F));
    }

    private static void handleDirectionalDashInput(Minecraft mc, LocalPlayer player, boolean hasAcrobatics) {
        KeyMapping[] moveKeys = {
                mc.options.keyUp,
                mc.options.keyDown,
                mc.options.keyLeft,
                mc.options.keyRight
        };

        for (int i = 0; i < moveKeys.length; i++) {
            boolean down = moveKeys[i].isDown();
            boolean pressed = down && !PREV_MOVE_DOWN[i];
            PREV_MOVE_DOWN[i] = down;

            if (!pressed || !hasAcrobatics || player.isPassenger()) {
                continue;
            }

            if ((clientTickCounter - LAST_MOVE_TAP_TICK[i]) <= StimConstants.DOUBLE_TAP_WINDOW_TICKS
                    && StimClientState.getDashCooldownTicks() <= 0
                    && (clientTickCounter - lastDashSentTick) > 5L) {
                LAST_MOVE_TAP_TICK[i] = -100L;
                lastDashSentTick = clientTickCounter;

                float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
                float sin = Mth.sin(yawRad);
                float cos = Mth.cos(yawRad);
                MoveKeySpec spec = MOVE_SPECS[i];
                float dirX = spec.strafeSign() * cos - spec.forwardSign() * sin;
                float dirZ = spec.forwardSign() * cos + spec.strafeSign() * sin;
                PacketDistributor.sendToServer(new TriggerStimMobilityPayload(true, dirX, dirZ));
            } else {
                LAST_MOVE_TAP_TICK[i] = clientTickCounter;
            }
        }
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !player.hasEffect(WarfrontMobEffects.COMBAT_STIM)) {
            return;
        }

        double range = StimConstants.HOSTILE_MARK_RANGE;
        double rangeSq = range * range;
        Vec3 camPos = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);

        AABB searchBox = player.getBoundingBox().inflate(range);
        java.util.List<Entity> candidates = mc.level.getEntities(player, searchBox, entity ->
                entity instanceof LivingEntity living
                        && living.isAlive()
                        && !living.isInvisible()
                        && living instanceof Enemy
                        && !AlliedFactionHelper.isAllied(player, living)
                        && living.distanceToSqr(player) <= rangeSq
        );
        if (candidates.isEmpty()) {
            return;
        }

        Matrix4f pose = new Matrix4f(event.getModelViewMatrix());
        pose.translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (Entity entity : candidates) {
            double ex = Mth.lerp(partialTick, entity.xo, entity.getX());
            double ey = Mth.lerp(partialTick, entity.yo, entity.getY());
            double ez = Mth.lerp(partialTick, entity.zo, entity.getZ());
            AABB box = entity.getBoundingBox().move(ex - entity.getX(), ey - entity.getY(), ez - entity.getZ()).inflate(0.05D);
            emitWireBox(buffer, pose,
                    (float) box.minX, (float) box.minY, (float) box.minZ,
                    (float) box.maxX, (float) box.maxY, (float) box.maxZ,
                    1.0F, 0.22F, 0.22F, 0.90F);
        }

        MeshData mesh = buffer.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void emitWireBox(BufferBuilder buf, Matrix4f pose,
                                    float x0, float y0, float z0,
                                    float x1, float y1, float z1,
                                    float r, float g, float b, float a) {
        // Bottom rectangle
        line(buf, pose, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(buf, pose, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(buf, pose, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(buf, pose, x0, y0, z1, x0, y0, z0, r, g, b, a);
        // Top rectangle
        line(buf, pose, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(buf, pose, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(buf, pose, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(buf, pose, x0, y1, z1, x0, y1, z0, r, g, b, a);
        // Vertical pillars
        line(buf, pose, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(buf, pose, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(buf, pose, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(buf, pose, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void line(BufferBuilder buf, Matrix4f pose,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float r, float g, float b, float a) {
        buf.addVertex(pose, x0, y0, z0).setColor(r, g, b, a);
        buf.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
    }
}
