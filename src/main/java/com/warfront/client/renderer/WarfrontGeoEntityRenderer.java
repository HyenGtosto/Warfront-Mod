package com.warfront.client.renderer;

import com.warfront.block.WarfrontBlocks;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationProcessor;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.constant.dataticket.DataTicket;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.GeoRenderer;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Shared client-side GeckoLib renderer providing centralized visibility culling,
 * 96-block distance limits, per-entity shadow cutoff, motion threshold normalization,
 * and safe distance-tiered animation throttling via per-entity bone pose buffers.
 */
public abstract class WarfrontGeoEntityRenderer<T extends Entity & GeoAnimatable> extends GeoEntityRenderer<T> {

    private static final double MAX_RENDER_DISTANCE = 96.0D;
    private static final double MAX_RENDER_DISTANCE_SQ = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;
    private static final double PROXIMITY_BYPASS_DISTANCE = 6.0D;
    private static final double PROXIMITY_BYPASS_DISTANCE_SQ = PROXIMITY_BYPASS_DISTANCE * PROXIMITY_BYPASS_DISTANCE;
    private static final double SHADOW_DISTANCE = 24.0D;
    private static final double SHADOW_DISTANCE_SQ = SHADOW_DISTANCE * SHADOW_DISTANCE;

    // Animation LOD distance tiers (Option A: full rate when scoping or using Optical Rangefinder Lens)
    private static final double ANIM_LOD_NEAR_SQ = 14.0D * 14.0D; // 0-14m: full rate
    private static final double ANIM_LOD_MID_SQ = 30.0D * 30.0D;  // 14-30m: ~35 FPS (30-45 FPS band)
    private static final double ANIM_LOD_FAR_SQ = 50.0D * 50.0D;  // 30-50m: ~18 FPS (15-20 FPS band); >50m: ~9 FPS
    private static final long ANIM_INTERVAL_MID_NS = 28_000_000L;
    private static final long ANIM_INTERVAL_FAR_NS = 55_000_000L;
    private static final long ANIM_INTERVAL_DISTANT_NS = 110_000_000L;

    // Staggered occlusion timing & asymmetric hysteresis (1.0s hide grace, 0-tick reveal)
    private static final long EVAL_INTERVAL_TICKS = 5L;
    private static final long OCCLUSION_GRACE_TICKS = 20L;
    private static final long PRUNE_INTERVAL_TICKS = 40L;
    private static final long STALE_EVICT_TICKS = 60L;
    private static final double TELEPORT_CAMERA_JUMP_SQ = 16.0D;       // > 4.0m in a single tick clears cache
    private static final double OCCLUDED_CAMERA_VERTICAL_DRIFT = 0.45D; // > 0.45m vertical step/jump reveals immediately
    private static final double OCCLUDED_CAMERA_HORIZ_DRIFT_SQ = 1.0D; // > 1.0m horizontal move reveals immediately
    private static final double OCCLUDED_ENTITY_DRIFT_SQ = 0.75D * 0.75D;

    private static int globalClearGeneration = 0;

    protected WarfrontGeoEntityRenderer(EntityRendererProvider.Context renderManager, GeoModel<T> model) {
        super(renderManager, new ThrottledGeoModel<>(model));
    }

    /**
     * Restore GeckoLib 4.7.4's default 0.015f motion threshold so sub-pixel floating-point
     * drift does not continuously trigger walk animations on stationary entities.
     */
    @Override
    public float getMotionAnimThreshold(T animatable) {
        return 0.015F;
    }

    @Override
    public boolean shouldRender(T entity, Frustum frustum, double camX, double camY, double camZ) {
        if (!super.shouldRender(entity, frustum, camX, camY, camZ)) {
            return false;
        }
        if (entity.noCulling) {
            return true;
        }

        double distSq = closestEntityDistanceSq(entity, camX, camY, camZ);
        if (distSq > MAX_RENDER_DISTANCE_SQ && !isPlayerScopingOrUsingRangefinder(Minecraft.getInstance())) {
            return false;
        }

        if (distSq <= PROXIMITY_BYPASS_DISTANCE_SQ) {
            return true;
        }

        return !OcclusionCache.isEntityOccluded(entity, camX, camY, camZ);
    }

    /**
     * Suppress entity ground shadows beyond 24 blocks per-entity without mutating
     * the shared {@code shadowRadius} field on the singleton renderer instance.
     */
    @Override
    protected float getShadowRadius(T entity) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera != null && camera.isInitialized()) {
            Vec3 camPos = camera.getPosition();
            if (closestEntityDistanceSq(entity, camPos.x, camPos.y, camPos.z) > SHADOW_DISTANCE_SQ) {
                return 0.0F;
            }
        }
        return super.getShadowRadius(entity);
    }

    public static void clearRenderCaches() {
        OcclusionCache.clear();
        globalClearGeneration++;
    }

    private static boolean isPlayerScopingOrUsingRangefinder(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return false;
        }
        if (player.isScoping()) {
            return true;
        }
        Item rangefinder = WarfrontBlocks.OPTICAL_RANGEFINDER_LENS.get();
        return player.getMainHandItem().is(rangefinder) || player.getOffhandItem().is(rangefinder);
    }

    private static double closestEntityDistanceSq(Entity entity, double camX, double camY, double camZ) {
        double feetDistSq = entity.distanceToSqr(camX, camY, camZ);
        double eyeDistSq = squareDistance(camX, camY, camZ, entity.getX(), entity.getEyeY(), entity.getZ());
        return Math.min(feetDistSq, eyeDistSq);
    }

    private static double squareDistance(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double squareDistance2D(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    private static boolean isOpaqueOccluder(BlockState state, BlockGetter level, BlockPos pos) {
        if (state.isAir() || !state.canOcclude() || state.is(BlockTags.LEAVES)) {
            return false;
        }
        return state.isSolidRender(level, pos);
    }

    /**
     * Raycast context that treats transparent and non-full-cube blocks (glass, leaves,
     * fences, bars, water, tall grass) as empty so occlusion rays continue cleanly to solid
     * geometry behind them.
     */
    private static final class OpaqueClipContext extends ClipContext {
        OpaqueClipContext(Vec3 from, Vec3 to) {
            super(from, to, Block.VISUAL, Fluid.NONE, CollisionContext.empty());
        }

        @Override
        public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
            if (!isOpaqueOccluder(state, level, pos)) {
                return Shapes.empty();
            }
            return state.getOcclusionShape(level, pos);
        }
    }

    private static final class OcclusionRecord {
        UUID uuid;
        long lastEvaluatedTick;
        long occludedSinceTick;
        boolean rawOccluded;
        double evalCamX;
        double evalCamY;
        double evalCamZ;
        double evalEntityX;
        double evalEntityY;
        double evalEntityZ;

        OcclusionRecord(UUID uuid, long tick, long occludedSinceTick, boolean rawOccluded,
                        double camX, double camY, double camZ,
                        double entityX, double entityY, double entityZ) {
            update(uuid, tick, occludedSinceTick, rawOccluded, camX, camY, camZ, entityX, entityY, entityZ);
        }

        void update(UUID uuid, long tick, long occludedSinceTick, boolean rawOccluded,
                    double camX, double camY, double camZ,
                    double entityX, double entityY, double entityZ) {
            this.uuid = uuid;
            this.lastEvaluatedTick = tick;
            this.occludedSinceTick = occludedSinceTick;
            this.rawOccluded = rawOccluded;
            this.evalCamX = camX;
            this.evalCamY = camY;
            this.evalCamZ = camZ;
            this.evalEntityX = entityX;
            this.evalEntityY = entityY;
            this.evalEntityZ = entityZ;
        }

        boolean isGraceExpired() {
            return rawOccluded && occludedSinceTick >= 0L && (lastEvaluatedTick - occludedSinceTick) >= OCCLUSION_GRACE_TICKS;
        }
    }

    private static final class OcclusionCache {
        private static final Int2ObjectOpenHashMap<OcclusionRecord> CACHE = new Int2ObjectOpenHashMap<>();
        private static WeakReference<ClientLevel> lastLevelRef = new WeakReference<>(null);
        private static long lastTickTime = Long.MIN_VALUE;
        private static long lastPruneTime = Long.MIN_VALUE;
        private static double lastTickCamX;
        private static double lastTickCamY;
        private static double lastTickCamZ;
        private static boolean hasLastCamPos;

        static void clear() {
            CACHE.clear();
            lastLevelRef.clear();
            lastTickTime = Long.MIN_VALUE;
            lastPruneTime = Long.MIN_VALUE;
            hasLastCamPos = false;
        }

        static boolean isEntityOccluded(Entity entity, double camX, double camY, double camZ) {
            if (!(entity.level() instanceof ClientLevel level)) {
                return false;
            }

            if (lastLevelRef.get() != level) {
                CACHE.clear();
                lastLevelRef = new WeakReference<>(level);
                lastTickTime = Long.MIN_VALUE;
                lastPruneTime = Long.MIN_VALUE;
                hasLastCamPos = false;
            }

            long gameTime = level.getGameTime();
            updateTickState(gameTime, camX, camY, camZ);

            int entityId = entity.getId();
            UUID entityUuid = entity.getUUID();
            OcclusionRecord record = CACHE.get(entityId);
            boolean validRecord = record != null && record.uuid.equals(entityUuid);

            // Reuse current tick evaluation across multiple render frames / shader passes
            if (validRecord && record.lastEvaluatedTick == gameTime) {
                return isHiddenConservative(record, entity, camX, camY, camZ);
            }

            if (Math.floorMod((long) entityId + gameTime, EVAL_INTERVAL_TICKS) == 0L) {
                boolean rawOccluded = computeEntityOccluded(level, entity, camX, camY, camZ);
                long occludedSince = -1L;
                if (rawOccluded) {
                    boolean continuous = validRecord
                            && record.occludedSinceTick >= 0L
                            && gameTime >= record.occludedSinceTick
                            && (gameTime - record.lastEvaluatedTick) <= (EVAL_INTERVAL_TICKS * 2L);
                    occludedSince = continuous ? record.occludedSinceTick : gameTime;
                }

                if (record == null) {
                    record = new OcclusionRecord(
                            entityUuid, gameTime, occludedSince, rawOccluded,
                            camX, camY, camZ,
                            entity.getX(), entity.getY(), entity.getZ()
                    );
                    CACHE.put(entityId, record);
                } else {
                    record.update(
                            entityUuid, gameTime, occludedSince, rawOccluded,
                            camX, camY, camZ,
                            entity.getX(), entity.getY(), entity.getZ()
                    );
                }
                return record.isGraceExpired();
            }

            // Cache miss or stale entry: remain visible until a check establishes sustained occlusion
            if (!validRecord || gameTime < record.lastEvaluatedTick || (gameTime - record.lastEvaluatedTick) > EVAL_INTERVAL_TICKS) {
                return false;
            }
            return isHiddenConservative(record, entity, camX, camY, camZ);
        }

        private static void updateTickState(long gameTime, double camX, double camY, double camZ) {
            if (gameTime == lastTickTime) {
                return;
            }
            if (gameTime < lastTickTime) {
                CACHE.clear();
            } else if (hasLastCamPos && squareDistance(camX, camY, camZ, lastTickCamX, lastTickCamY, lastTickCamZ) > TELEPORT_CAMERA_JUMP_SQ) {
                CACHE.clear();
            }
            lastTickCamX = camX;
            lastTickCamY = camY;
            lastTickCamZ = camZ;
            hasLastCamPos = true;
            lastTickTime = gameTime;

            if (lastPruneTime == Long.MIN_VALUE || gameTime - lastPruneTime >= PRUNE_INTERVAL_TICKS) {
                pruneStaleEntries(gameTime);
                lastPruneTime = gameTime;
            }
        }

        private static void pruneStaleEntries(long gameTime) {
            var it = CACHE.int2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                OcclusionRecord record = it.next().getValue();
                if (gameTime < record.lastEvaluatedTick || (gameTime - record.lastEvaluatedTick) > STALE_EVICT_TICKS) {
                    it.remove();
                }
            }
        }

        private static boolean isHiddenConservative(
                OcclusionRecord record, Entity entity, double camX, double camY, double camZ) {
            if (!record.isGraceExpired()) {
                return false;
            }
            // Immediate vertical/corner reveal: climbing a step/slab or peeking around walls avoids pop-in delay
            if (Math.abs(camY - record.evalCamY) > OCCLUDED_CAMERA_VERTICAL_DRIFT) {
                return false;
            }
            if (squareDistance2D(camX, camZ, record.evalCamX, record.evalCamZ) > OCCLUDED_CAMERA_HORIZ_DRIFT_SQ) {
                return false;
            }
            return squareDistance(
                    entity.getX(), entity.getY(), entity.getZ(),
                    record.evalEntityX, record.evalEntityY, record.evalEntityZ) <= OCCLUDED_ENTITY_DRIFT_SQ;
        }

        private static boolean computeEntityOccluded(
                ClientLevel level, Entity entity, double camX, double camY, double camZ) {
            Vec3 camPos = new Vec3(camX, camY, camZ);
            BlockPos camBlockPos = BlockPos.containing(camX, camY, camZ);
            if (isOpaqueOccluder(level.getBlockState(camBlockPos), level, camBlockPos)) {
                return false;
            }

            double ex = entity.getX();
            double ey = entity.getY();
            double ez = entity.getZ();
            double eyeY = entity.getEyeY();
            double height = Math.max(0.5D, entity.getBbHeight());
            AABB box = entity.getBoundingBoxForCulling();
            double topCrownY = Math.max(eyeY + 0.15D, box.maxY + 0.15D);
            double outerHalfWidth = Math.max(0.35D, entity.getBbWidth() * 0.5D + 0.25D);

            // 1. Eye center (short-circuits on the 1st ray for open-view entities)
            if (hasLineOfSight(level, camPos, new Vec3(ex, eyeY, ez))) {
                return false;
            }

            // 2. Top silhouette crown (catches helmets/hats peeking over ridges and stairways)
            if (hasLineOfSight(level, camPos, new Vec3(ex, topCrownY, ez))) {
                return false;
            }

            // 3. Mid center
            double midY = ey + height * 0.55D;
            if (hasLineOfSight(level, camPos, new Vec3(ex, midY, ez))) {
                return false;
            }

            // 4. Expanded horizontal outer silhouette flanks perpendicular to camera sightline
            double dx = ex - camX;
            double dz = ez - camZ;
            double horizLenSq = dx * dx + dz * dz;
            if (horizLenSq > 1.0E-4D) {
                double invLen = 1.0D / Math.sqrt(horizLenSq);
                double rightX = -dz * invLen * outerHalfWidth;
                double rightZ = dx * invLen * outerHalfWidth;
                if (hasLineOfSight(level, camPos, new Vec3(ex - rightX, midY, ez - rightZ))) {
                    return false;
                }
                if (hasLineOfSight(level, camPos, new Vec3(ex + rightX, midY, ez + rightZ))) {
                    return false;
                }
            }

            // 5. Lower torso / legs
            double lowerY = ey + Math.min(0.35D, height * 0.20D);
            return !hasLineOfSight(level, camPos, new Vec3(ex, lowerY, ez));
        }

        private static boolean hasLineOfSight(ClientLevel level, Vec3 from, Vec3 to) {
            BlockHitResult hit = level.clip(new OpaqueClipContext(from, to));
            return hit.getType() == HitResult.Type.MISS;
        }
    }

    /**
     * Per-entity pose snapshot buffer storing all GeoBone transforms and visibility flags.
     * Eliminates shared BakedGeoModel bone pose bleeding when distant animation evaluation is throttled.
     */
    private static final class PoseRecord {
        UUID uuid;
        long nextEvalNs;
        long lastSeenGameTime;
        int boneCount;
        float[] transforms; // 9 floats per bone: rotX/Y/Z, posX/Y/Z, scaleX/Y/Z
        boolean[] hiddenFlags; // 2 booleans per bone: hidden, childrenHidden

        PoseRecord(UUID uuid, int boneCount, long nextEvalNs, long gameTime) {
            this.uuid = uuid;
            this.nextEvalNs = nextEvalNs;
            this.lastSeenGameTime = gameTime;
            ensureCapacity(boneCount);
        }

        void ensureCapacity(int count) {
            if (this.transforms == null || this.boneCount != count) {
                this.boneCount = count;
                this.transforms = new float[count * 9];
                this.hiddenFlags = new boolean[count * 2];
            }
        }

        void captureFromBones(GeoBone[] bones) {
            ensureCapacity(bones.length);
            for (int i = 0; i < bones.length; i++) {
                GeoBone bone = bones[i];
                int tBase = i * 9;
                transforms[tBase] = bone.getRotX();
                transforms[tBase + 1] = bone.getRotY();
                transforms[tBase + 2] = bone.getRotZ();
                transforms[tBase + 3] = bone.getPosX();
                transforms[tBase + 4] = bone.getPosY();
                transforms[tBase + 5] = bone.getPosZ();
                transforms[tBase + 6] = bone.getScaleX();
                transforms[tBase + 7] = bone.getScaleY();
                transforms[tBase + 8] = bone.getScaleZ();

                int hBase = i * 2;
                hiddenFlags[hBase] = bone.isHidden();
                hiddenFlags[hBase + 1] = bone.isHidingChildren();
            }
        }

        void applyToBones(GeoBone[] bones) {
            for (int i = 0; i < bones.length; i++) {
                GeoBone bone = bones[i];
                int tBase = i * 9;
                bone.setRotX(transforms[tBase]);
                bone.setRotY(transforms[tBase + 1]);
                bone.setRotZ(transforms[tBase + 2]);
                bone.setPosX(transforms[tBase + 3]);
                bone.setPosY(transforms[tBase + 4]);
                bone.setPosZ(transforms[tBase + 5]);
                bone.setScaleX(transforms[tBase + 6]);
                bone.setScaleY(transforms[tBase + 7]);
                bone.setScaleZ(transforms[tBase + 8]);

                int hBase = i * 2;
                bone.setHidden(hiddenFlags[hBase]);
                bone.setChildrenHidden(hiddenFlags[hBase + 1]);
                bone.resetStateChanges();
            }
        }
    }

    /**
     * Delegating GeoModel wrapper that enforces distance-tiered animation LOD using
     * per-entity PoseRecords while delegating all resources, bone lookups, and custom
     * animation callbacks to the underlying model.
     */
    private static final class ThrottledGeoModel<T extends Entity & GeoAnimatable> extends GeoModel<T> {
        private final GeoModel<T> delegate;
        private final Int2ObjectOpenHashMap<PoseRecord> poseCache = new Int2ObjectOpenHashMap<>();
        private WeakReference<Level> lastLevelRef = new WeakReference<>(null);
        private WeakReference<BakedGeoModel> lastBakedModelRef = new WeakReference<>(null);
        private GeoBone[] boneArray;
        private int clearGeneration = globalClearGeneration;
        private long lastSharedModelOwner = Long.MIN_VALUE;
        private long lastDelegateEvaluatedInstance = Long.MIN_VALUE;
        private long lastPruneGameTime = Long.MIN_VALUE;

        ThrottledGeoModel(GeoModel<T> delegate) {
            this.delegate = delegate;
        }

        private void resetPoseCache() {
            poseCache.clear();
            boneArray = null;
            lastSharedModelOwner = Long.MIN_VALUE;
            lastDelegateEvaluatedInstance = Long.MIN_VALUE;
            lastPruneGameTime = Long.MIN_VALUE;
        }

        private GeoBone[] getOrBuildBoneArray() {
            Collection<GeoBone> registered = delegate.getAnimationProcessor().getRegisteredBones();
            if (boneArray == null || boneArray.length != registered.size()) {
                boneArray = registered.toArray(new GeoBone[0]);
            }
            return boneArray;
        }

        @Override
        public ResourceLocation getModelResource(T animatable, GeoRenderer<T> renderer) {
            return delegate.getModelResource(animatable, renderer);
        }

        @Override
        @SuppressWarnings("removal")
        public ResourceLocation getModelResource(T animatable) {
            return delegate.getModelResource(animatable);
        }

        @Override
        public ResourceLocation getTextureResource(T animatable, GeoRenderer<T> renderer) {
            return delegate.getTextureResource(animatable, renderer);
        }

        @Override
        @SuppressWarnings("removal")
        public ResourceLocation getTextureResource(T animatable) {
            return delegate.getTextureResource(animatable);
        }

        @Override
        public ResourceLocation getAnimationResource(T animatable) {
            return delegate.getAnimationResource(animatable);
        }

        @Override
        public ResourceLocation[] getAnimationResourceFallbacks(T animatable) {
            return delegate.getAnimationResourceFallbacks(animatable);
        }

        @Override
        public boolean crashIfBoneMissing() {
            return delegate.crashIfBoneMissing();
        }

        @Override
        public RenderType getRenderType(T animatable, ResourceLocation texture) {
            return delegate.getRenderType(animatable, texture);
        }

        @Override
        public BakedGeoModel getBakedModel(ResourceLocation location) {
            BakedGeoModel baked = delegate.getBakedModel(location);
            if (lastBakedModelRef.get() != baked) {
                lastBakedModelRef = new WeakReference<>(baked);
                resetPoseCache();
            }
            return baked;
        }

        @Override
        public Optional<GeoBone> getBone(String name) {
            return delegate.getBone(name);
        }

        @Override
        public Animation getAnimation(T animatable, String name) {
            return delegate.getAnimation(animatable, name);
        }

        @Override
        public AnimationProcessor<T> getAnimationProcessor() {
            return delegate.getAnimationProcessor();
        }

        @Override
        public void addAdditionalStateData(T animatable, long instanceId, BiConsumer<DataTicket<T>, T> dataConsumer) {
            delegate.addAdditionalStateData(animatable, instanceId, dataConsumer);
        }

        @Override
        public void setCustomAnimations(T animatable, long instanceId, AnimationState<T> animationState) {
            delegate.setCustomAnimations(animatable, instanceId, animationState);
        }

        @Override
        public void applyMolangQueries(AnimationState<T> animationState, double animTime) {
            delegate.applyMolangQueries(animationState, animTime);
        }

        @Override
        public void handleAnimations(T animatable, long instanceId, AnimationState<T> animationState, float partialTick) {
            if (clearGeneration != globalClearGeneration) {
                resetPoseCache();
                lastLevelRef.clear();
                clearGeneration = globalClearGeneration;
            }

            Level level = animatable.level();
            if (lastLevelRef.get() != level) {
                resetPoseCache();
                lastLevelRef = new WeakReference<>(level);
            }

            GeoBone[] bones = getOrBuildBoneArray();
            if (bones.length == 0) {
                delegate.handleAnimations(animatable, instanceId, animationState, partialTick);
                lastSharedModelOwner = instanceId;
                lastDelegateEvaluatedInstance = instanceId;
                return;
            }

            long gameTime = level != null ? level.getGameTime() : 0L;
            if (lastPruneGameTime == Long.MIN_VALUE || gameTime < lastPruneGameTime || (gameTime - lastPruneGameTime) >= PRUNE_INTERVAL_TICKS) {
                prunePoseCache(gameTime);
                lastPruneGameTime = gameTime;
            }

            int entityId = animatable.getId();
            UUID entityUuid = animatable.getUUID();
            PoseRecord record = poseCache.get(entityId);
            boolean validRecord = record != null && record.uuid.equals(entityUuid) && record.boneCount == bones.length;

            long intervalNs = computeAnimIntervalNs(animatable);
            long nowNs = Util.getNanos();

            if (validRecord && intervalNs > 0L && nowNs < record.nextEvalNs) {
                record.lastSeenGameTime = gameTime;
                if (lastSharedModelOwner != instanceId) {
                    record.applyToBones(bones);
                    lastSharedModelOwner = instanceId;
                }
                return;
            }

            // Guard against GeoModel's early-return when multi-pass rendering (e.g. shadows) interleaves
            // a throttled entity between passes for this same entity on the identical render tick.
            AnimatableManager<T> manager = animatable.getAnimatableInstanceCache().getManagerForId(instanceId);
            Double dataTick = animationState.getData(DataTickets.TICK);
            double currentTick = (dataTick != null ? dataTick : (double) animatable.tickCount) + partialTick;
            boolean delegateWouldEarlyReturn = !manager.isFirstTick()
                    && currentTick == manager.getLastUpdateTime()
                    && instanceId == lastDelegateEvaluatedInstance;

            if (delegateWouldEarlyReturn) {
                if (validRecord && lastSharedModelOwner != instanceId) {
                    record.lastSeenGameTime = gameTime;
                    record.applyToBones(bones);
                    lastSharedModelOwner = instanceId;
                }
                return;
            }

            delegate.handleAnimations(animatable, instanceId, animationState, partialTick);
            lastSharedModelOwner = instanceId;
            lastDelegateEvaluatedInstance = instanceId;

            long nextEvalNs;
            if (intervalNs <= 0L) {
                nextEvalNs = nowNs;
            } else if (!validRecord) {
                long staggerOffsetNs = Math.floorMod(entityId, 4) * (intervalNs >>> 2);
                nextEvalNs = nowNs + Math.max(0L, intervalNs - staggerOffsetNs);
            } else {
                nextEvalNs = nowNs + intervalNs;
            }

            if (record == null) {
                record = new PoseRecord(entityUuid, bones.length, nextEvalNs, gameTime);
                poseCache.put(entityId, record);
            } else {
                record.uuid = entityUuid;
                record.nextEvalNs = nextEvalNs;
                record.lastSeenGameTime = gameTime;
            }
            record.captureFromBones(bones);
        }

        private void prunePoseCache(long gameTime) {
            var it = poseCache.int2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                PoseRecord record = it.next().getValue();
                if (gameTime < record.lastSeenGameTime || (gameTime - record.lastSeenGameTime) > STALE_EVICT_TICKS) {
                    it.remove();
                }
            }
        }

        private static long computeAnimIntervalNs(Entity entity) {
            Minecraft mc = Minecraft.getInstance();
            if (isPlayerScopingOrUsingRangefinder(mc)) {
                return 0L;
            }
            if (entity instanceof LivingEntity living && (living.isDeadOrDying()
                    || (living.hurtTime > 0 && living.hurtTime >= living.hurtDuration - 1))) {
                return 0L;
            }
            Camera camera = mc.gameRenderer.getMainCamera();
            if (camera == null || !camera.isInitialized()) {
                return 0L;
            }
            Vec3 camPos = camera.getPosition();
            double distSq = closestEntityDistanceSq(entity, camPos.x, camPos.y, camPos.z);
            if (distSq <= ANIM_LOD_NEAR_SQ) {
                return 0L;
            }
            if (distSq <= ANIM_LOD_MID_SQ) {
                return ANIM_INTERVAL_MID_NS;
            }
            if (distSq <= ANIM_LOD_FAR_SQ) {
                return ANIM_INTERVAL_FAR_NS;
            }
            return ANIM_INTERVAL_DISTANT_NS;
        }
    }
}
