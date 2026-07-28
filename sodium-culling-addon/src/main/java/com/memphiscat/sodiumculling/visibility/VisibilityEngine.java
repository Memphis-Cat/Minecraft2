package com.memphiscat.sodiumculling.visibility;

import com.memphiscat.sodiumculling.SodiumCullingClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Conservative CPU visibility checks for objects Sodium does not own.
 * A result is only "culled" when every enabled test is conclusive.
 */
public final class VisibilityEngine {
    private static final Map<Integer, CachedVisibility> ENTITY_CACHE = new HashMap<>();
    private static final Map<Long, CachedVisibility> BLOCK_ENTITY_CACHE = new HashMap<>();
    private static final Map<Long, CachedVisibility> PARTICLE_CELL_CACHE = new HashMap<>();

    private static Camera camera;
    private static Frustum frustum;
    private static long cacheTick = Long.MIN_VALUE;
    private static Boolean shaderPackActive;

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
    }

    public static Frustum frustum() {
        return frustum;
    }

    public static Camera camera() {
        return camera;
    }

    public static boolean shouldCullEntity(Entity entity) {
        if (!SodiumCullingClient.CONFIG.entityCulling || camera == null || frustum == null) {
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
            return true;
        }

        if (!frustum.isVisible(box)) {
            return true;
        }

        if (entity instanceof Painting painting && paintingFacesAway(painting, cameraPos)) {
            return true;
        }

        // Shader shadow passes can use a different camera. Distance/frustum remain safe,
        // but main-camera block-ray occlusion can incorrectly remove a visible shadow.
        if (isShaderPackActive()) {
            return false;
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
            return !cached.visible;
        }

        boolean visible = hasClearSample(level, cameraEntity, cameraPos, box);
        ENTITY_CACHE.put(entity.getId(), new CachedVisibility(tick, cameraBlock, objectBlock, visible));
        return !visible;
    }

    public static boolean shouldCullBlockEntity(BlockEntity blockEntity) {
        if (!SodiumCullingClient.CONFIG.blockEntityCulling || camera == null || frustum == null) {
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
            return true;
        }

        if (!frustum.isVisible(box)) {
            return true;
        }

        // A beacon beam can remain visible even when the beacon block itself is behind a wall.
        if (blockEntity instanceof BeaconBlockEntity || isShaderPackActive()) {
            return false;
        }

        if (cameraPos.distanceToSqr(box.getCenter()) < 9.0D) {
            return false;
        }

        long key = pos.asLong();
        long tick = level.getGameTime();
        BlockPos cameraBlock = BlockPos.containing(cameraPos);
        CachedVisibility cached = BLOCK_ENTITY_CACHE.get(key);
        if (cached != null && tick - cached.tick <= 4L && cached.cameraBlock.equals(cameraBlock)) {
            return !cached.visible;
        }

        boolean visible = hasClearSample(level, Minecraft.getInstance().getCameraEntity(), cameraPos, box);
        BLOCK_ENTITY_CACHE.put(key, new CachedVisibility(tick, cameraBlock, pos, visible));
        return !visible;
    }

    public static boolean shouldCullParticlePoint(double x, double y, double z) {
        if (!SodiumCullingClient.CONFIG.particleCulling || camera == null || frustum == null) {
            return false;
        }

        Vec3 cameraPos = camera.position();
        double maxDistance = effectiveDistance(SodiumCullingClient.CONFIG.particleMaxDistance);
        double dx = x - cameraPos.x;
        double dy = y - cameraPos.y;
        double dz = z - cameraPos.z;
        if (dx * dx + dy * dy + dz * dz > maxDistance * maxDistance) {
            return true;
        }

        if (!frustum.pointInFrustum(x, y, z)) {
            return true;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || isShaderPackActive() || dx * dx + dy * dy + dz * dz < 36.0D) {
            return false;
        }

        int cellX = floorDiv((int) Math.floor(x), 4);
        int cellY = floorDiv((int) Math.floor(y), 4);
        int cellZ = floorDiv((int) Math.floor(z), 4);
        long cellKey = packCell(cellX, cellY, cellZ);
        CachedVisibility cached = PARTICLE_CELL_CACHE.get(cellKey);
        if (cached != null) {
            return !cached.visible;
        }

        AABB cell = new AABB(cellX * 4.0D, cellY * 4.0D, cellZ * 4.0D,
                cellX * 4.0D + 4.0D, cellY * 4.0D + 4.0D, cellZ * 4.0D + 4.0D);
        boolean visible = hasClearSample(level, Minecraft.getInstance().getCameraEntity(), cameraPos, cell);
        PARTICLE_CELL_CACHE.put(cellKey, new CachedVisibility(level.getGameTime(), BlockPos.containing(cameraPos),
                BlockPos.containing(cell.getCenter()), visible));
        return !visible;
    }

    public static boolean isColumnVisible(AABB column) {
        return frustum == null || frustum.isVisible(column);
    }

    public static boolean isTextSideVisible(Vec3 normal, Vec3 signCenter, boolean front) {
        if (camera == null) {
            return true;
        }
        Vec3 toCamera = camera.position().subtract(signCenter);
        double length = toCamera.length();
        if (length < 0.001D) {
            return true;
        }
        double dot = normal.dot(toCamera.scale(1.0D / length));
        // Render both sides around edge-on angles to avoid popping.
        return front ? dot > -0.12D : dot < 0.12D;
    }

    private static boolean hasClearSample(ClientLevel level, Entity source, Vec3 cameraPos, AABB box) {
        Vec3 center = box.getCenter();
        if (hasClearRay(level, source, cameraPos, center)) {
            return true;
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
        HitResult result = level.clip(new ClipContext(start, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, source));
        if (result.getType() == HitResult.Type.MISS) {
            return true;
        }
        double hitDistance = start.distanceToSqr(result.getLocation());
        double targetDistance = start.distanceToSqr(target);
        return hitDistance + 0.09D >= targetDistance;
    }

    private static boolean paintingFacesAway(Painting painting, Vec3 cameraPos) {
        Vec3 center = painting.getBoundingBox().getCenter();
        Vec3 toCamera = cameraPos.subtract(center);
        double nx = painting.getDirection().getStepX();
        double nz = painting.getDirection().getStepZ();
        double dot = nx * toCamera.x + nz * toCamera.z;
        // Only reject a clearly rear-facing painting; edge-on paintings remain visible.
        return dot < -0.15D && toCamera.horizontalDistanceSqr() > 4.0D;
    }

    private static double effectiveDistance(int configured) {
        Minecraft minecraft = Minecraft.getInstance();
        int chunks = minecraft.options.getEffectiveRenderDistance();
        return Math.min(configured, Math.max(32.0D, chunks * 16.0D + 16.0D));
    }

    private static double distanceSquaredToBox(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0D), point.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isShaderPackActive() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            return false;
        }
        if (shaderPackActive != null) {
            return shaderPackActive;
        }
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method getInstance = apiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            Method inUse = apiClass.getMethod("isShaderPackInUse");
            shaderPackActive = (Boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException | LinkageError error) {
            SodiumCullingClient.LOGGER.debug("Iris API was not available; using safe no-occlusion fallback", error);
            shaderPackActive = true;
        }
        return shaderPackActive;
    }

    private static int floorDiv(int value, int divisor) {
        int result = value / divisor;
        if ((value ^ divisor) < 0 && result * divisor != value) {
            result--;
        }
        return result;
    }

    private static long packCell(int x, int y, int z) {
        return ((long) x & 0x1FFFFFL) << 42 | ((long) z & 0x1FFFFFL) << 21 | ((long) y & 0x1FFFFFL);
    }

    private record CachedVisibility(long tick, BlockPos cameraBlock, BlockPos objectBlock, boolean visible) {
    }
}
