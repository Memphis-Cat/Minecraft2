package com.memphiscat.sodiumculling.visibility;

import com.memphiscat.sodiumculling.SodiumCullingClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Conservative visibility checks for render categories Sodium does not own. */
public final class VisibilityEngine {
    private static final Map<Integer, CachedVisibility> ENTITY_CACHE = new HashMap<>();
    private static final Map<Long, CachedVisibility> BLOCK_ENTITY_CACHE = new HashMap<>();
    private static final Map<Long, CachedVisibility> PARTICLE_CELL_CACHE = new HashMap<>();

    private static Camera camera;
    private static Frustum frustum;
    private static long cacheTick = Long.MIN_VALUE;
    private static long shaderQueryTick = Long.MIN_VALUE;
    private static boolean cachedShaderPackActive;
    private static float fogEnd = Float.POSITIVE_INFINITY;
    private static boolean irisShadowLookupDone;
    private static Field irisShadowActiveField;

    private VisibilityEngine() {
    }

    public static void beginFrame(Camera nextCamera, Frustum nextFrustum) {
        camera = nextCamera;
        frustum = nextFrustum;
        ClientLevel level = Minecraft.getInstance().level;
        long tick = level == null ? 0L : level.getGameTime();
        if (tick != cacheTick) {
            cacheTick = tick;
            PARTICLE_CELL_CACHE.clear();
            if ((tick & 31L) == 0L) {
                ENTITY_CACHE.entrySet().removeIf(entry -> tick - entry.getValue().tick > 40L);
                BLOCK_ENTITY_CACHE.entrySet().removeIf(entry -> tick - entry.getValue().tick > 40L);
            }
        }
        updateFogDistance();
    }

    public static Frustum frustum() {
        return frustum;
    }

    public static Camera camera() {
        return camera;
    }

    public static boolean shouldCullEntity(Entity entity) {
        if (!SodiumCullingClient.CONFIG.enabled || !SodiumCullingClient.CONFIG.entityCulling
                || camera == null || frustum == null) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Entity cameraEntity = minecraft.getCameraEntity();
        if (entity == cameraEntity || entity == minecraft.player
                || (cameraEntity != null && entity.isPassengerOfSameVehicle(cameraEntity))) {
            return false;
        }

        AABB box = entity.getBoundingBox().inflate(0.15D);
        Vec3 cameraPos = camera.position();
        double maxDistance = effectiveDistance(SodiumCullingClient.CONFIG.entityMaxDistance);
        if (distanceSquaredToBox(cameraPos, box) > maxDistance * maxDistance) {
            return entityCulled();
        }
        if (!frustum.isVisible(box)) {
            return entityCulled();
        }
        if (entity instanceof HangingEntity hanging && hangingFacesAway(hanging, cameraPos)) {
            return entityCulled();
        }

        ClientLevel level = minecraft.level;
        if (level == null || cameraPos.distanceToSqr(box.getCenter()) < 16.0D) {
            return false;
        }

        CachedVisibility cached = ENTITY_CACHE.get(entity.getId());
        BlockPos cameraBlock = BlockPos.containing(cameraPos);
        BlockPos objectBlock = BlockPos.containing(box.getCenter());
        long tick = level.getGameTime();
        if (cached != null && tick - cached.tick <= 3L && cached.cameraBlock.equals(cameraBlock)
                && cached.objectBlock.equals(objectBlock)) {
            return cached.visible ? false : entityCulled();
        }

        boolean visible = hasClearSample(level, cameraEntity, cameraPos, box);
        ENTITY_CACHE.put(entity.getId(), new CachedVisibility(tick, cameraBlock, objectBlock, visible));
        return visible ? false : entityCulled();
    }

    public static boolean shouldCullBlockEntity(BlockEntity blockEntity) {
        if (!SodiumCullingClient.CONFIG.enabled || !SodiumCullingClient.CONFIG.blockEntityCulling
                || camera == null || frustum == null || isIrisShadowPass()) {
            return false;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || blockEntity.getLevel() != level) {
            return false;
        }

        BlockPos pos = blockEntity.getBlockPos();
        AABB box;
        if (blockEntity instanceof BeaconBlockEntity) {
            box = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0D,
                    level.getMaxY() + 1.0D, pos.getZ() + 1.0D);
        } else {
            box = new AABB(pos).inflate(0.1D);
        }

        Vec3 cameraPos = camera.position();
        double maxDistance = effectiveDistance(SodiumCullingClient.CONFIG.blockEntityMaxDistance);
        if (distanceSquaredToBox(cameraPos, box) > maxDistance * maxDistance) {
            return blockEntityCulled();
        }
        if (!frustum.isVisible(box)) {
            return blockEntityCulled();
        }

        if (blockEntity instanceof BeaconBlockEntity || cameraPos.distanceToSqr(box.getCenter()) < 9.0D) {
            return false;
        }

        long key = pos.asLong();
        long tick = level.getGameTime();
        BlockPos cameraBlock = BlockPos.containing(cameraPos);
        CachedVisibility cached = BLOCK_ENTITY_CACHE.get(key);
        if (cached != null && tick - cached.tick <= 4L && cached.cameraBlock.equals(cameraBlock)) {
            return cached.visible ? false : blockEntityCulled();
        }

        boolean visible = hasClearSample(level, Minecraft.getInstance().getCameraEntity(), cameraPos, box);
        BLOCK_ENTITY_CACHE.put(key, new CachedVisibility(tick, cameraBlock, pos, visible));
        return visible ? false : blockEntityCulled();
    }

    public static boolean shouldCullParticlePoint(double x, double y, double z) {
        if (!SodiumCullingClient.CONFIG.enabled || !SodiumCullingClient.CONFIG.particleCulling
                || camera == null || frustum == null) {
            return false;
        }

        Vec3 cameraPos = camera.position();
        double maxDistance = effectiveDistance(SodiumCullingClient.CONFIG.particleMaxDistance);
        double dx = x - cameraPos.x;
        double dy = y - cameraPos.y;
        double dz = z - cameraPos.z;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared > maxDistance * maxDistance || !frustum.pointInFrustum(x, y, z)) {
            CullingStats.particle();
            return true;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || distanceSquared < 36.0D) {
            return false;
        }

        int cellX = Math.floorDiv((int) Math.floor(x), 4);
        int cellY = Math.floorDiv((int) Math.floor(y), 4);
        int cellZ = Math.floorDiv((int) Math.floor(z), 4);
        long cellKey = packCell(cellX, cellY, cellZ);
        CachedVisibility cached = PARTICLE_CELL_CACHE.get(cellKey);
        if (cached != null) {
            if (!cached.visible) CullingStats.particle();
            return !cached.visible;
        }

        AABB cell = new AABB(cellX * 4.0D, cellY * 4.0D, cellZ * 4.0D,
                cellX * 4.0D + 4.0D, cellY * 4.0D + 4.0D, cellZ * 4.0D + 4.0D);
        boolean visible = hasClearSample(level, Minecraft.getInstance().getCameraEntity(), cameraPos, cell);
        PARTICLE_CELL_CACHE.put(cellKey, new CachedVisibility(level.getGameTime(), BlockPos.containing(cameraPos),
                BlockPos.containing(cell.getCenter()), visible));
        if (!visible) CullingStats.particle();
        return !visible;
    }

    public static boolean isColumnVisible(AABB column) {
        if (!SodiumCullingClient.CONFIG.enabled) {
            return true;
        }
        if (frustum != null && !frustum.isVisible(column)) {
            return false;
        }
        if (!SodiumCullingClient.CONFIG.fogCulling || !Float.isFinite(fogEnd) || camera == null) {
            return true;
        }
        return distanceSquaredToBox(camera.position(), column) <= (fogEnd + 2.0F) * (fogEnd + 2.0F);
    }

    public static boolean isTextSideVisible(Vec3 normal, Vec3 signCenter, boolean front) {
        if (!SodiumCullingClient.CONFIG.enabled || camera == null) {
            return true;
        }
        Vec3 toCamera = camera.position().subtract(signCenter);
        double length = toCamera.length();
        if (length < 0.001D) {
            return true;
        }
        double dot = normal.dot(toCamera.scale(1.0D / length));
        boolean visible = front ? dot > -0.12D : dot < 0.12D;
        if (!visible) CullingStats.signSide();
        return visible;
    }

    private static boolean hasClearSample(ClientLevel level, Entity source, Vec3 cameraPos, AABB box) {
        Vec3 center = box.getCenter();
        if (hasClearRay(level, source, cameraPos, center)) {
            return true;
        }

        if (SodiumCullingClient.CONFIG.hierarchicalZCulling && DepthPyramid.isOccluded(box)) {
            CullingStats.hzbHit();
            return false;
        }

        double insetX = Math.min(0.05D, box.getXsize() * 0.1D);
        double insetY = Math.min(0.05D, box.getYsize() * 0.1D);
        double insetZ = Math.min(0.05D, box.getZsize() * 0.1D);
        double minX = box.minX + insetX;
        double minY = box.minY + insetY;
        double minZ = box.minZ + insetZ;
        double maxX = box.maxX - insetX;
        double maxY = box.maxY - insetY;
        double maxZ = box.maxZ - insetZ;

        for (int mask = 0; mask < 8; mask++) {
            Vec3 target = new Vec3((mask & 1) == 0 ? minX : maxX,
                    (mask & 2) == 0 ? minY : maxY,
                    (mask & 4) == 0 ? minZ : maxZ);
            if (hasClearRay(level, source, cameraPos, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasClearRay(ClientLevel level, Entity source, Vec3 start, Vec3 target) {
        CullingStats.rayTest();
        HitResult result = level.clip(new ClipContext(start, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, source));
        if (result.getType() == HitResult.Type.MISS) {
            return true;
        }
        double hitDistance = start.distanceToSqr(result.getLocation());
        double targetDistance = start.distanceToSqr(target);
        return hitDistance + 0.09D >= targetDistance;
    }

    private static boolean hangingFacesAway(HangingEntity hanging, Vec3 cameraPos) {
        Vec3 center = hanging.getBoundingBox().getCenter();
        Vec3 toCamera = cameraPos.subtract(center);
        double nx = hanging.getDirection().getStepX();
        double nz = hanging.getDirection().getStepZ();
        double dot = nx * toCamera.x + nz * toCamera.z;
        return dot < -0.15D && toCamera.horizontalDistanceSqr() > 4.0D;
    }

    private static double effectiveDistance(int configured) {
        Minecraft minecraft = Minecraft.getInstance();
        int chunks = minecraft.options.getEffectiveRenderDistance();
        double result = Math.min(configured, Math.max(32.0D, chunks * 16.0D + 16.0D));
        if (SodiumCullingClient.CONFIG.fogCulling && Float.isFinite(fogEnd)) {
            result = Math.min(result, Math.max(8.0D, fogEnd + 2.0D));
        }
        return result;
    }

    private static void updateFogDistance() {
        fogEnd = Float.POSITIVE_INFINITY;
        if (!SodiumCullingClient.CONFIG.enabled || !SodiumCullingClient.CONFIG.fogCulling
                || Minecraft.getInstance().level == null) {
            return;
        }
        CameraRenderState state = Minecraft.getInstance().gameRenderer.gameRenderState()
                .levelRenderState.cameraRenderState;
        float renderEnd = state.fogData.renderDistanceEnd;
        float environmentalEnd = state.fogData.environmentalEnd;
        if (Float.isFinite(renderEnd) && renderEnd > 1.0F) {
            fogEnd = renderEnd;
        }
        if (Float.isFinite(environmentalEnd) && environmentalEnd > 1.0F) {
            fogEnd = Math.min(fogEnd, environmentalEnd);
        }
    }

    private static double distanceSquaredToBox(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0D), point.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    public static boolean shaderPackActive() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            return false;
        }
        ClientLevel level = Minecraft.getInstance().level;
        long tick = level == null ? 0L : level.getGameTime();
        if (shaderQueryTick == tick) {
            return cachedShaderPackActive;
        }
        shaderQueryTick = tick;
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Method inUse = apiClass.getMethod("isShaderPackInUse");
            cachedShaderPackActive = (Boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException | LinkageError error) {
            SodiumCullingClient.LOGGER.debug("Iris API unavailable", error);
            cachedShaderPackActive = false;
        }
        return cachedShaderPackActive;
    }

    public static boolean isIrisShadowPass() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            return false;
        }
        if (!irisShadowLookupDone) {
            irisShadowLookupDone = true;
            try {
                Class<?> shadowRenderer = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
                irisShadowActiveField = shadowRenderer.getField("ACTIVE");
            } catch (ReflectiveOperationException | LinkageError error) {
                SodiumCullingClient.LOGGER.debug("Could not access Iris shadow-pass state", error);
            }
        }
        if (irisShadowActiveField == null) {
            return false;
        }
        try {
            return irisShadowActiveField.getBoolean(null);
        } catch (IllegalAccessException error) {
            return false;
        }
    }

    private static boolean entityCulled() {
        CullingStats.entity();
        return true;
    }

    private static boolean blockEntityCulled() {
        CullingStats.blockEntity();
        return true;
    }

    private static long packCell(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) << 42 | ((long) z & 0x1FFFFFL) << 21 | ((long) y & 0x1FFFFFL);
    }

    private record CachedVisibility(long tick, BlockPos cameraBlock, BlockPos objectBlock, boolean visible) {
    }
}
