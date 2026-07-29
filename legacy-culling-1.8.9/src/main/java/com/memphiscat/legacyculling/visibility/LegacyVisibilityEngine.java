package com.memphiscat.legacyculling.visibility;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.compat.OptiFineCompat;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.PistonExtensionBlock;
import net.minecraft.block.PistonHeadBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnchantingTableBlockEntity;
import net.minecraft.block.entity.EndPortalBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.PistonBlockEntity;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.AbstractArrowEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkProvider;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Conservative visibility tests for legacy Minecraft.
 *
 * A block may confirm occlusion only when it is loaded, opaque, static and its
 * real collision box covers the complete block cube. Plants, leaves, glass,
 * fluids, fences, slabs, stairs, doors, trapdoors, pistons and block entities
 * are therefore never treated as reliable occluders.
 */
public final class LegacyVisibilityEngine {
    private static final Map<Integer, VisibilityCache> ENTITY_CACHE = new HashMap<Integer, VisibilityCache>();
    private static final Map<Long, VisibilityCache> BLOCK_ENTITY_CACHE = new HashMap<Long, VisibilityCache>();
    private static final Map<Long, VisibilityCache> PARTICLE_CELL_CACHE = new HashMap<Long, VisibilityCache>();

    private static final double SAMPLE_INSET = 0.0125D;
    private static final double OCCLUDER_INSET = 0.002D;
    private static final double HIT_EPSILON = 0.02D;
    private static final double CAMERA_STABILITY_SQ = 0.1225D;
    private static final double TARGET_STABILITY_SQ = 0.0625D;
    private static final float ANGLE_STABILITY = 4.0F;

    private static Field arrowInGround;
    private static boolean arrowLookupDone;
    private static long lastPrune;

    private LegacyVisibilityEngine() {
    }

    public static synchronized void reset() {
        ENTITY_CACHE.clear();
        BLOCK_ENTITY_CACHE.clear();
        PARTICLE_CELL_CACHE.clear();
        lastPrune = 0L;
    }

    public static boolean shouldCullEntity(Entity entity, double cameraX, double cameraY, double cameraZ) {
        if (!LegacyCullingMod.CONFIG.enabled || entity == null) return false;

        MinecraftClient client = MinecraftClient.getInstance();
        Entity cameraEntity = client.getCameraEntity();
        if (entity == cameraEntity || entity == client.player || entity.rider == client.player
                || entity.vehicle == cameraEntity) {
            return false;
        }

        if (isExplicitlyDisabled(entity)) {
            CullingStats.disabledRenderer();
            return true;
        }
        if (isSafetyExempt(entity)) return false;

        if (LegacyCullingMod.CONFIG.customEntityRenderDistance) {
            double distance = configuredDistance(entity);
            if (entity.squaredDistanceTo(cameraX, cameraY, cameraZ) > distance * distance) {
                CullingStats.entity();
                return true;
            }
        }

        if (LegacyCullingMod.CONFIG.decorationBackfaceCulling
                && entity instanceof AbstractDecorationEntity
                && decorationFacesAway((AbstractDecorationEntity) entity, cameraX, cameraY, cameraZ)) {
            CullingStats.entity();
            return true;
        }

        if (!LegacyCullingMod.CONFIG.entityCulling) return false;
        if (LegacyCullingMod.CONFIG.smartEntityCulling && OptiFineCompat.shadersActive()) return false;

        Box box = entity.getBoundingBox().expand(0.18D, 0.18D, 0.18D);
        Vec3d camera = new Vec3d(cameraX, cameraY, cameraZ);
        if (distanceSquaredToBox(camera, box) < 16.0D) return false;
        if (LegacyFrameState.isBeyondFog(box, cameraX, cameraY, cameraZ)) {
            CullingStats.entity();
            return true;
        }

        float yaw = cameraEntity == null ? 0.0F : cameraEntity.yaw;
        float pitch = cameraEntity == null ? 0.0F : cameraEntity.pitch;
        boolean hidden = resolveOcclusion(ENTITY_CACHE, Integer.valueOf(entity.getEntityId()), entity.world,
                camera, yaw, pitch, box, LegacyCullingMod.CONFIG.entityCullingIntervalMs * 1000000L);
        if (hidden) CullingStats.entity();
        return hidden;
    }

    public static boolean shouldCullBlockEntity(BlockEntity blockEntity) {
        if (!LegacyCullingMod.CONFIG.enabled || blockEntity == null || !blockEntity.hasWorld()) return false;

        if (blockEntity instanceof EnchantingTableBlockEntity && LegacyCullingMod.CONFIG.disableEnchantmentBooks) {
            CullingStats.disabledRenderer();
            return true;
        }
        if (blockEntity instanceof EndPortalBlockEntity && LegacyCullingMod.CONFIG.disableEndPortals) {
            CullingStats.disabledRenderer();
            return true;
        }
        if (blockEntity instanceof SkullBlockEntity && LegacyCullingMod.CONFIG.disableSkulls) {
            CullingStats.disabledRenderer();
            return true;
        }

        double cameraX = BlockEntityRenderDispatcher.CAMERA_X;
        double cameraY = BlockEntityRenderDispatcher.CAMERA_Y;
        double cameraZ = BlockEntityRenderDispatcher.CAMERA_Z;
        if (LegacyCullingMod.CONFIG.customEntityRenderDistance) {
            double distance = LegacyCullingMod.CONFIG.tileEntityRenderDistance;
            if (blockEntity.getSquaredDistance(cameraX, cameraY, cameraZ) > distance * distance) {
                CullingStats.blockEntity();
                return true;
            }
        }

        if (!LegacyCullingMod.CONFIG.blockEntityCulling || blockEntity instanceof BeaconBlockEntity
                || blockEntity instanceof PistonBlockEntity) {
            return false;
        }
        BlockEntityRenderer renderer = BlockEntityRenderDispatcher.INSTANCE.getRenderer(blockEntity);
        if (renderer == null || renderer.rendersOutsideBoundingBox()) return false;

        BlockPos pos = blockEntity.getPos();
        Box box = blockEntityBox(blockEntity, pos);
        Vec3d camera = new Vec3d(cameraX, cameraY, cameraZ);
        if (distanceSquaredToBox(camera, box) < 9.0D) return false;
        if (LegacyFrameState.isBeyondFog(box, cameraX, cameraY, cameraZ)) {
            CullingStats.blockEntity();
            return true;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        Entity cameraEntity = client.getCameraEntity();
        float yaw = cameraEntity == null ? 0.0F : cameraEntity.yaw;
        float pitch = cameraEntity == null ? 0.0F : cameraEntity.pitch;
        boolean hidden = resolveOcclusion(BLOCK_ENTITY_CACHE, Long.valueOf(pos.asLong()),
                blockEntity.getEntityWorld(), camera, yaw, pitch, box,
                LegacyCullingMod.CONFIG.entityCullingIntervalMs * 1000000L);
        if (hidden) CullingStats.blockEntity();
        return hidden;
    }

    public static boolean shouldCullParticle(double x, double y, double z) {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.particleCulling) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        Entity cameraEntity = client.getCameraEntity();
        World world = client.world;
        if (cameraEntity == null || world == null) return false;

        Vec3d camera = cameraEntity.getCameraPosVec(1.0F);
        double dx = x - camera.x;
        double dy = y - camera.y;
        double dz = z - camera.z;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        double maxDistance = LegacyCullingMod.CONFIG.particleMaxDistance;
        if (distanceSq > maxDistance * maxDistance) {
            CullingStats.particle();
            return true;
        }
        if (distanceSq < 36.0D) return false;

        int cellX = floor(x) >> 2;
        int cellY = floor(y) >> 2;
        int cellZ = floor(z) >> 2;
        long key = packCell(cellX, cellY, cellZ);
        Box cell = new Box(cellX * 4.0D, cellY * 4.0D, cellZ * 4.0D,
                cellX * 4.0D + 4.0D, cellY * 4.0D + 4.0D, cellZ * 4.0D + 4.0D);
        if (LegacyFrameState.isBeyondFog(cell, camera.x, camera.y, camera.z)) {
            CullingStats.particle();
            return true;
        }

        long interval = Math.max(1000000L, LegacyCullingMod.CONFIG.entityCullingIntervalMs * 1000000L);
        boolean hidden = resolveOcclusion(PARTICLE_CELL_CACHE, Long.valueOf(key), world, camera,
                cameraEntity.yaw, cameraEntity.pitch, cell, interval);
        if (hidden) CullingStats.particle();
        return hidden;
    }

    public static boolean isSignTextVisible(BlockEntity blockEntity) {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.signTextCulling || blockEntity == null) {
            return true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        Entity cameraEntity = client.getCameraEntity();
        if (cameraEntity == null) return true;
        Vec3d camera = cameraEntity.getCameraPosVec(1.0F);
        BlockPos pos = blockEntity.getPos();
        double dx = camera.x - (pos.getX() + 0.5D);
        double dz = camera.z - (pos.getZ() + 0.5D);
        int data = blockEntity.getDataValue();
        double nx;
        double nz;
        if (blockEntity.getBlock() == Blocks.STANDING_SIGN) {
            double angle = Math.toRadians(-(data * 360.0D / 16.0D));
            nx = Math.sin(angle);
            nz = Math.cos(angle);
        } else {
            Direction direction = Direction.getById(data);
            if (direction == null) return true;
            nx = direction.getOffsetX();
            nz = direction.getOffsetZ();
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001D) return true;
        boolean visible = (nx * dx + nz * dz) / length > -0.22D;
        if (!visible) CullingStats.signText();
        return visible;
    }

    private static <K> boolean resolveOcclusion(Map<K, VisibilityCache> cache, K key, World world,
                                                 Vec3d camera, float yaw, float pitch, Box box,
                                                 long visibleCacheInterval) {
        long now = System.nanoTime();
        long frame = LegacyFrameState.frameIndex();
        Vec3d target = boxCenter(box);
        VisibilityCache previous = cache.get(key);

        if (previous != null && previous.frame == frame) return previous.hidden;
        if (previous != null && previous.clear && now - previous.timeNanos < visibleCacheInterval
                && stable(previous, camera, yaw, pitch, target)) {
            return false;
        }

        boolean clear = hasClearSample(world, camera, box);
        if (clear) {
            cache.put(key, VisibilityCache.clear(now, frame, camera, yaw, pitch, target));
            pruneCaches(now);
            return false;
        }

        if (!LegacyHzbFastPath.confirmsOcclusion(box)) {
            cache.put(key, VisibilityCache.clear(now, frame, camera, yaw, pitch, target));
            pruneCaches(now);
            return false;
        }

        int occludedFrames = 1;
        if (previous != null && !previous.clear && stable(previous, camera, yaw, pitch, target)
                && frame >= previous.frame && frame - previous.frame <= 2L) {
            occludedFrames = previous.occludedFrames + 1;
        }
        boolean hidden = occludedFrames >= LegacyCullingMod.CONFIG.occlusionHideFrames;
        cache.put(key, VisibilityCache.occluded(now, frame, camera, yaw, pitch, target,
                occludedFrames, hidden));
        pruneCaches(now);
        return hidden;
    }

    private static boolean stable(VisibilityCache cache, Vec3d camera, float yaw, float pitch, Vec3d target) {
        return square(cache.cameraX - camera.x) + square(cache.cameraY - camera.y)
                + square(cache.cameraZ - camera.z) <= CAMERA_STABILITY_SQ
                && square(cache.targetX - target.x) + square(cache.targetY - target.y)
                + square(cache.targetZ - target.z) <= TARGET_STABILITY_SQ
                && angleDifference(cache.yaw, yaw) <= ANGLE_STABILITY
                && Math.abs(cache.pitch - pitch) <= ANGLE_STABILITY;
    }

    private static Box blockEntityBox(BlockEntity blockEntity, BlockPos pos) {
        if (blockEntity instanceof ChestBlockEntity || blockEntity instanceof EnderChestBlockEntity) {
            return new Box(pos.getX() - 0.15D, pos.getY() - 0.05D, pos.getZ() - 0.15D,
                    pos.getX() + 1.15D, pos.getY() + 1.65D, pos.getZ() + 1.15D);
        }
        if (blockEntity instanceof EnchantingTableBlockEntity) {
            return new Box(pos.getX() - 0.20D, pos.getY() - 0.05D, pos.getZ() - 0.20D,
                    pos.getX() + 1.20D, pos.getY() + 1.60D, pos.getZ() + 1.20D);
        }
        return new Box(pos, pos.add(1, 1, 1)).expand(0.08D, 0.08D, 0.08D);
    }

    private static boolean isSafetyExempt(Entity entity) {
        if (entity instanceof EnderDragonEntity && LegacyCullingMod.CONFIG.dontCullEnderDragons) return true;
        if (entity instanceof WitherEntity && LegacyCullingMod.CONFIG.dontCullWithers) return true;
        if (entity instanceof PlayerEntity && LegacyCullingMod.CONFIG.dontCullPlayerNametags) return true;
        if (entity instanceof ArmorStandEntity) {
            ArmorStandEntity armorStand = (ArmorStandEntity) entity;
            if (LegacyCullingMod.CONFIG.dontCullArmorstandNametags
                    && (armorStand.hasCustomName() || armorStand.isCustomNameVisible() || armorStand.shouldShowName())) {
                return true;
            }
            if (LegacyCullingMod.CONFIG.checkArmorstandRules
                    && (armorStand.isInvisible() || armorStand.hasCustomName() || armorStand.shouldShowName())) {
                return true;
            }
        }
        return LegacyCullingMod.CONFIG.dontCullEntityNametags
                && !(entity instanceof PlayerEntity)
                && (entity.hasCustomName() || entity.isCustomNameVisible() || entity.shouldRenderName());
    }

    private static boolean isExplicitlyDisabled(Entity entity) {
        if (entity instanceof ArmorStandEntity && LegacyCullingMod.CONFIG.disableArmorstands) return true;
        if (entity instanceof FallingBlockEntity && LegacyCullingMod.CONFIG.disableFallingBlocks) return true;
        if (entity instanceof PlayerEntity && LegacyCullingMod.CONFIG.disableSemitransparentPlayers
                && entity.isInvisible() && !entity.isInvisibleTo(MinecraftClient.getInstance().player)) return true;
        if (entity instanceof ItemFrameEntity) {
            if (LegacyCullingMod.CONFIG.disableItemFrames) return true;
            if (LegacyCullingMod.CONFIG.disableMappedItemFrames) {
                ItemStack stack = ((ItemFrameEntity) entity).getHeldItemStack();
                if (stack != null && stack.getItem() instanceof FilledMapItem) return true;
            }
        }
        return entity instanceof AbstractArrowEntity
                && LegacyCullingMod.CONFIG.disableGroundedArrows
                && isArrowInGround((AbstractArrowEntity) entity);
    }

    private static double configuredDistance(Entity entity) {
        if (entity instanceof PlayerEntity) return LegacyCullingMod.CONFIG.playerEntityRenderDistance;
        if (entity instanceof HostileEntity) return LegacyCullingMod.CONFIG.hostileEntityRenderDistance;
        if (entity instanceof PassiveEntity) return LegacyCullingMod.CONFIG.passiveEntityRenderDistance;
        return LegacyCullingMod.CONFIG.globalEntityRenderDistance;
    }

    private static boolean decorationFacesAway(AbstractDecorationEntity entity,
                                                double cameraX, double cameraY, double cameraZ) {
        Direction direction = entity.direction;
        if (direction == null) return false;
        Box box = entity.getBoundingBox();
        double dx = cameraX - (box.minX + box.maxX) * 0.5D;
        double dz = cameraZ - (box.minZ + box.maxZ) * 0.5D;
        double dot = direction.getOffsetX() * dx + direction.getOffsetZ() * dz;
        return dot < -0.38D && dx * dx + dz * dz > 9.0D;
    }

    private static boolean hasClearSample(World world, Vec3d camera, Box box) {
        Vec3d center = boxCenter(box);
        if (hasClearRay(world, camera, center)) return true;

        double insetX = Math.min(SAMPLE_INSET, Math.max(0.001D, (box.maxX - box.minX) * 0.04D));
        double insetY = Math.min(SAMPLE_INSET, Math.max(0.001D, (box.maxY - box.minY) * 0.04D));
        double insetZ = Math.min(SAMPLE_INSET, Math.max(0.001D, (box.maxZ - box.minZ) * 0.04D));
        double[] xs = {box.minX + insetX, (box.minX + box.maxX) * 0.5D, box.maxX - insetX};
        double[] ys = {box.minY + insetY, (box.minY + box.maxY) * 0.5D, box.maxY - insetY};
        double[] zs = {box.minZ + insetZ, (box.minZ + box.maxZ) * 0.5D, box.maxZ - insetZ};

        for (int yi = 0; yi < 3; yi++) {
            for (int zi = 0; zi < 3; zi++) {
                for (int xi = 0; xi < 3; xi++) {
                    if (xi == 1 && yi == 1 && zi == 1) continue;
                    if (hasClearRay(world, camera, new Vec3d(xs[xi], ys[yi], zs[zi]))) return true;
                }
            }
        }
        return false;
    }

    /**
     * Grid traversal that never asks an unloaded chunk for block state. Only
     * trusted full-cube collision boxes can stop the ray.
     */
    private static boolean hasClearRay(World world, Vec3d start, Vec3d target) {
        CullingStats.rayTest();
        double dx = target.x - start.x;
        double dy = target.y - start.y;
        double dz = target.z - start.z;
        double targetDistance = start.distanceTo(target);
        if (targetDistance < 0.0001D) return true;

        int x = floor(start.x);
        int y = floor(start.y);
        int z = floor(start.z);
        int endX = floor(target.x);
        int endY = floor(target.y);
        int endZ = floor(target.z);
        int stepX = sign(dx);
        int stepY = sign(dy);
        int stepZ = sign(dz);
        double tMaxX = firstBoundary(start.x, dx, stepX);
        double tMaxY = firstBoundary(start.y, dy, stepY);
        double tMaxZ = firstBoundary(start.z, dz, stepZ);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dz);
        int maximumSteps = Math.min(2048, Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z) + 6);

        for (int step = 0; step < maximumSteps; step++) {
            double t;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }

            if (t > 1.0D + 0.00001D) return true;
            if (x == endX && y == endY && z == endZ) return true;
            if (y < 0 || y >= world.getMaxBuildHeight()) continue;
            if (!isChunkLoaded(world, x, z)) return true;

            BlockPos pos = new BlockPos(x, y, z);
            Box occluder = trustedOccluderBox(world, pos);
            if (occluder == null) continue;
            Box inset = new Box(occluder.minX + OCCLUDER_INSET, occluder.minY + OCCLUDER_INSET,
                    occluder.minZ + OCCLUDER_INSET, occluder.maxX - OCCLUDER_INSET,
                    occluder.maxY - OCCLUDER_INSET, occluder.maxZ - OCCLUDER_INSET);
            BlockHitResult hit = inset.method_585(start, target);
            if (hit != null && hit.pos != null
                    && start.distanceTo(hit.pos) + HIT_EPSILON < targetDistance) {
                return false;
            }
        }
        return true;
    }

    private static Box trustedOccluderBox(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (block == null || block == Blocks.AIR || block == Blocks.BARRIER) return null;
        if (block instanceof DoorBlock || block instanceof TrapdoorBlock || block instanceof FenceGateBlock
                || block instanceof PistonBlock || block instanceof PistonHeadBlock
                || block instanceof PistonExtensionBlock) {
            return null;
        }
        Material material = block.getMaterial();
        if (material == null || material.isFluid() || material.isTranslucent() || !material.isSolid()
                || !material.blocksMovement() || !material.isOpaque()) {
            return null;
        }
        if (block.isLeafBlock() || block.hasTransparency() || block.isTranslucent()
                || !block.isFullBlock() || !block.isFullCube() || !block.isNormalBlock()
                || !block.renderAsNormalBlock() || block.getOpacity() < 255 || block.hasBlockEntity()) {
            return null;
        }

        Box collision = block.getCollisionBox(world, pos, state);
        if (collision == null) return null;
        double minX = pos.getX();
        double minY = pos.getY();
        double minZ = pos.getZ();
        if (collision.minX > minX + OCCLUDER_INSET || collision.minY > minY + OCCLUDER_INSET
                || collision.minZ > minZ + OCCLUDER_INSET
                || collision.maxX < minX + 1.0D - OCCLUDER_INSET
                || collision.maxY < minY + 1.0D - OCCLUDER_INSET
                || collision.maxZ < minZ + 1.0D - OCCLUDER_INSET) {
            return null;
        }
        return collision;
    }

    private static boolean isChunkLoaded(World world, int blockX, int blockZ) {
        ChunkProvider provider = world.getChunkProvider();
        return provider != null && provider.chunkExists(blockX >> 4, blockZ >> 4);
    }

    private static boolean isArrowInGround(AbstractArrowEntity arrow) {
        if (!arrowLookupDone) {
            arrowLookupDone = true;
            try {
                arrowInGround = AbstractArrowEntity.class.getDeclaredField("inGround");
                arrowInGround.setAccessible(true);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        if (arrowInGround == null) return false;
        try {
            return arrowInGround.getBoolean(arrow);
        } catch (IllegalAccessException ignored) {
            return false;
        }
    }

    private static void pruneCaches(long now) {
        if (now - lastPrune < 2000000000L) return;
        lastPrune = now;
        prune(ENTITY_CACHE, now);
        prune(BLOCK_ENTITY_CACHE, now);
        prune(PARTICLE_CELL_CACHE, now);
    }

    private static <K> void prune(Map<K, VisibilityCache> cache, long now) {
        Iterator<Map.Entry<K, VisibilityCache>> iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().timeNanos > 5000000000L) iterator.remove();
        }
    }

    private static Vec3d boxCenter(Box box) {
        return new Vec3d((box.minX + box.maxX) * 0.5D,
                (box.minY + box.maxY) * 0.5D,
                (box.minZ + box.maxZ) * 0.5D);
    }

    private static double distanceSquaredToBox(Vec3d point, Box box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0D), point.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static int sign(double value) {
        return value > 0.0D ? 1 : value < 0.0D ? -1 : 0;
    }

    private static double firstBoundary(double coordinate, double delta, int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double boundary = step > 0 ? Math.floor(coordinate) + 1.0D : Math.floor(coordinate);
        return (boundary - coordinate) / delta;
    }

    private static long packCell(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static float angleDifference(float first, float second) {
        float difference = Math.abs(first - second) % 360.0F;
        return difference > 180.0F ? 360.0F - difference : difference;
    }

    private static double square(double value) {
        return value * value;
    }

    private static final class VisibilityCache {
        final long timeNanos;
        final long frame;
        final double cameraX;
        final double cameraY;
        final double cameraZ;
        final float yaw;
        final float pitch;
        final double targetX;
        final double targetY;
        final double targetZ;
        final boolean clear;
        final int occludedFrames;
        final boolean hidden;

        private VisibilityCache(long timeNanos, long frame, Vec3d camera, float yaw, float pitch,
                                Vec3d target, boolean clear, int occludedFrames, boolean hidden) {
            this.timeNanos = timeNanos;
            this.frame = frame;
            this.cameraX = camera.x;
            this.cameraY = camera.y;
            this.cameraZ = camera.z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.targetX = target.x;
            this.targetY = target.y;
            this.targetZ = target.z;
            this.clear = clear;
            this.occludedFrames = occludedFrames;
            this.hidden = hidden;
        }

        static VisibilityCache clear(long timeNanos, long frame, Vec3d camera, float yaw, float pitch,
                                     Vec3d target) {
            return new VisibilityCache(timeNanos, frame, camera, yaw, pitch, target, true, 0, false);
        }

        static VisibilityCache occluded(long timeNanos, long frame, Vec3d camera, float yaw, float pitch,
                                        Vec3d target, int occludedFrames, boolean hidden) {
            return new VisibilityCache(timeNanos, frame, camera, yaw, pitch, target,
                    false, occludedFrames, hidden);
        }
    }
}
