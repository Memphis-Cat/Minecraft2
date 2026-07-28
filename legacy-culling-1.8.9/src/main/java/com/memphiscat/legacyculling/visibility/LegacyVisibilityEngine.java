package com.memphiscat.legacyculling.visibility;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.compat.OptiFineCompat;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.EnchantingTableBlockEntity;
import net.minecraft.block.entity.EndPortalBlockEntity;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
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

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class LegacyVisibilityEngine {
    private static final Map<Integer, VisibilityCache> ENTITY_CACHE = new HashMap<Integer, VisibilityCache>();
    private static final Map<Long, VisibilityCache> BLOCK_ENTITY_CACHE = new HashMap<Long, VisibilityCache>();
    private static final Map<Long, VisibilityCache> PARTICLE_CELL_CACHE = new HashMap<Long, VisibilityCache>();
    private static Field arrowInGround;
    private static boolean arrowLookupDone;
    private static long lastPrune;

    private LegacyVisibilityEngine() {
    }

    public static boolean shouldCullEntity(Entity entity, double cameraX, double cameraY, double cameraZ) {
        if (!LegacyCullingMod.CONFIG.enabled || entity == null) return false;

        MinecraftClient client = MinecraftClient.getInstance();
        Entity cameraEntity = client.getCameraEntity();
        if (entity == cameraEntity || entity == client.player || entity.rider == client.player || entity.vehicle == cameraEntity) {
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

        Box box = entity.getBoundingBox().expand(0.12D, 0.12D, 0.12D);
        Vec3d camera = new Vec3d(cameraX, cameraY, cameraZ);
        if (distanceSquaredToBox(camera, box) < 16.0D) return false;

        long now = System.nanoTime();
        long interval = LegacyCullingMod.CONFIG.entityCullingIntervalMs * 1000000L;
        BlockPos cameraBlock = new BlockPos(camera);
        BlockPos targetBlock = new BlockPos(boxCenter(box));
        VisibilityCache cached = ENTITY_CACHE.get(entity.getEntityId());
        if (cached != null && now - cached.timeNanos < interval
                && cached.cameraBlock.equals(cameraBlock) && cached.targetBlock.equals(targetBlock)) {
            if (!cached.visible) CullingStats.entity();
            return !cached.visible;
        }

        boolean visible = hasClearSample(entity.world, camera, box);
        ENTITY_CACHE.put(entity.getEntityId(), new VisibilityCache(now, cameraBlock, targetBlock, visible));
        pruneCaches(now);
        if (!visible) CullingStats.entity();
        return !visible;
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

        if (!LegacyCullingMod.CONFIG.blockEntityCulling || blockEntity instanceof BeaconBlockEntity) return false;
        BlockEntityRenderer renderer = BlockEntityRenderDispatcher.INSTANCE.getRenderer(blockEntity);
        if (renderer == null || renderer.rendersOutsideBoundingBox()) return false;

        BlockPos pos = blockEntity.getPos();
        Box box = new Box(pos, pos.add(1, 1, 1)).expand(0.05D, 0.05D, 0.05D);
        Vec3d camera = new Vec3d(cameraX, cameraY, cameraZ);
        if (distanceSquaredToBox(camera, box) < 9.0D) return false;

        long now = System.nanoTime();
        long interval = LegacyCullingMod.CONFIG.entityCullingIntervalMs * 1000000L;
        BlockPos cameraBlock = new BlockPos(camera);
        long key = pos.asLong();
        VisibilityCache cached = BLOCK_ENTITY_CACHE.get(key);
        if (cached != null && now - cached.timeNanos < interval && cached.cameraBlock.equals(cameraBlock)) {
            if (!cached.visible) CullingStats.blockEntity();
            return !cached.visible;
        }

        boolean visible = hasClearSample(blockEntity.getEntityWorld(), camera, box);
        BLOCK_ENTITY_CACHE.put(key, new VisibilityCache(now, cameraBlock, pos, visible));
        pruneCaches(now);
        if (!visible) CullingStats.blockEntity();
        return !visible;
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
        long now = System.nanoTime();
        VisibilityCache cached = PARTICLE_CELL_CACHE.get(key);
        long interval = Math.max(1000000L, LegacyCullingMod.CONFIG.entityCullingIntervalMs * 1000000L);
        if (cached != null && now - cached.timeNanos < interval) {
            if (!cached.visible) CullingStats.particle();
            return !cached.visible;
        }

        Box cell = new Box(cellX * 4.0D, cellY * 4.0D, cellZ * 4.0D,
                cellX * 4.0D + 4.0D, cellY * 4.0D + 4.0D, cellZ * 4.0D + 4.0D);
        boolean visible = hasClearSample(world, camera, cell);
        PARTICLE_CELL_CACHE.put(key, new VisibilityCache(now, new BlockPos(camera), new BlockPos(boxCenter(cell)), visible));
        if (!visible) CullingStats.particle();
        return !visible;
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
        if (blockEntity.getBlock() == net.minecraft.block.Blocks.STANDING_SIGN) {
            double angle = Math.toRadians(-(data * 360.0D / 16.0D));
            nx = Math.sin(angle);
            nz = Math.cos(angle);
        } else {
            Direction direction = Direction.getById(data);
            nx = direction.getOffsetX();
            nz = direction.getOffsetZ();
        }
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001D) return true;
        boolean visible = (nx * dx + nz * dz) / length > -0.10D;
        if (!visible) CullingStats.signText();
        return visible;
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

    private static boolean decorationFacesAway(AbstractDecorationEntity entity, double cameraX, double cameraY, double cameraZ) {
        Direction direction = entity.direction;
        if (direction == null) return false;
        Box box = entity.getBoundingBox();
        double dx = cameraX - (box.minX + box.maxX) * 0.5D;
        double dz = cameraZ - (box.minZ + box.maxZ) * 0.5D;
        double dot = direction.getOffsetX() * dx + direction.getOffsetZ() * dz;
        return dot < -0.15D && dx * dx + dz * dz > 4.0D;
    }

    private static boolean hasClearSample(World world, Vec3d camera, Box box) {
        Vec3d center = boxCenter(box);
        if (hasClearRay(world, camera, center)) return true;

        double insetX = Math.min(0.05D, (box.maxX - box.minX) * 0.1D);
        double insetY = Math.min(0.05D, (box.maxY - box.minY) * 0.1D);
        double insetZ = Math.min(0.05D, (box.maxZ - box.minZ) * 0.1D);
        for (int mask = 0; mask < 8; mask++) {
            Vec3d target = new Vec3d(
                    (mask & 1) == 0 ? box.minX + insetX : box.maxX - insetX,
                    (mask & 2) == 0 ? box.minY + insetY : box.maxY - insetY,
                    (mask & 4) == 0 ? box.minZ + insetZ : box.maxZ - insetZ);
            if (hasClearRay(world, camera, target)) return true;
        }
        return false;
    }

    private static boolean hasClearRay(World world, Vec3d start, Vec3d target) {
        CullingStats.rayTest();
        BlockHitResult hit = world.rayTrace(start, target, false, true, false);
        if (hit == null || hit.type == BlockHitResult.Type.MISS) return true;
        double hitDistance = hit.pos == null ? 0.0D : start.distanceTo(hit.pos);
        double targetDistance = start.distanceTo(target);
        return hitDistance + 0.03D >= targetDistance;
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

    private static long packCell(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static final class VisibilityCache {
        final long timeNanos;
        final BlockPos cameraBlock;
        final BlockPos targetBlock;
        final boolean visible;

        VisibilityCache(long timeNanos, BlockPos cameraBlock, BlockPos targetBlock, boolean visible) {
            this.timeNanos = timeNanos;
            this.cameraBlock = cameraBlock;
            this.targetBlock = targetBlock;
            this.visible = visible;
        }
    }
}
