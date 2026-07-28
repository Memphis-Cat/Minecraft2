package com.memphiscat.legacyculling.visibility;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.compat.OptiFineCompat;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** HZB is a fast confirmation only. Uncertain objects fall through to full CPU sampling. */
public final class LegacyHzbFastPath {
    private LegacyHzbFastPath() {
    }

    public static boolean shouldCullEntity(Entity entity, double cameraX, double cameraY, double cameraZ) {
        if (!baseEnabled() || entity == null || safetyExempt(entity)) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        Entity cameraEntity = client.getCameraEntity();
        if (entity == cameraEntity || entity == client.player) return false;

        Box box = entity.getBoundingBox().expand(0.12D, 0.12D, 0.12D);
        if (LegacyFrameState.isBeyondFog(box, cameraX, cameraY, cameraZ)) {
            CullingStats.entity();
            return true;
        }
        if (!LegacyCullingMod.CONFIG.entityHierarchicalZ) return false;

        Vec3d camera = new Vec3d(cameraX, cameraY, cameraZ);
        if (distanceSquared(camera, box) < 16.0D || !LegacyDepthPyramid.isOccluded(box)) return false;
        if (centerRayClear(entity.world, camera, center(box))) return false;
        CullingStats.entity();
        return true;
    }

    public static boolean shouldCullBlockEntity(BlockEntity blockEntity) {
        if (!baseEnabled() || blockEntity == null || !blockEntity.hasWorld() || blockEntity instanceof BeaconBlockEntity) {
            return false;
        }
        BlockEntityRenderer renderer = BlockEntityRenderDispatcher.INSTANCE.getRenderer(blockEntity);
        if (renderer == null || renderer.rendersOutsideBoundingBox()) return false;

        double cameraX = BlockEntityRenderDispatcher.CAMERA_X;
        double cameraY = BlockEntityRenderDispatcher.CAMERA_Y;
        double cameraZ = BlockEntityRenderDispatcher.CAMERA_Z;
        BlockPos pos = blockEntity.getPos();
        Box box = new Box(pos, pos.add(1, 1, 1)).expand(0.05D, 0.05D, 0.05D);
        if (LegacyFrameState.isBeyondFog(box, cameraX, cameraY, cameraZ)) {
            CullingStats.blockEntity();
            return true;
        }
        if (!LegacyCullingMod.CONFIG.entityHierarchicalZ) return false;

        Vec3d camera = new Vec3d(cameraX, cameraY, cameraZ);
        if (distanceSquared(camera, box) < 9.0D || !LegacyDepthPyramid.isOccluded(box)) return false;
        if (centerRayClear(blockEntity.getEntityWorld(), camera, center(box))) return false;
        CullingStats.blockEntity();
        return true;
    }

    public static boolean shouldCullParticle(double x, double y, double z) {
        if (!baseEnabled()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        Entity cameraEntity = client.getCameraEntity();
        if (cameraEntity == null || client.world == null) return false;
        Vec3d camera = cameraEntity.getCameraPosVec(1.0F);
        Box box = new Box(x - 0.15D, y - 0.15D, z - 0.15D, x + 0.15D, y + 0.15D, z + 0.15D);
        if (LegacyFrameState.isBeyondFog(box, camera.x, camera.y, camera.z)) {
            CullingStats.particle();
            return true;
        }
        if (!LegacyCullingMod.CONFIG.entityHierarchicalZ) return false;
        if (!LegacyDepthPyramid.isOccluded(box) || centerRayClear(client.world, camera, new Vec3d(x, y, z))) {
            return false;
        }
        CullingStats.particle();
        return true;
    }

    private static boolean baseEnabled() {
        return LegacyCullingMod.CONFIG.enabled
                && !(LegacyCullingMod.CONFIG.smartEntityCulling && OptiFineCompat.shadersActive());
    }

    private static boolean safetyExempt(Entity entity) {
        if (entity instanceof EnderDragonEntity && LegacyCullingMod.CONFIG.dontCullEnderDragons) return true;
        if (entity instanceof WitherEntity && LegacyCullingMod.CONFIG.dontCullWithers) return true;
        if (entity instanceof PlayerEntity && LegacyCullingMod.CONFIG.dontCullPlayerNametags) return true;
        if (entity instanceof ArmorStandEntity) {
            ArmorStandEntity stand = (ArmorStandEntity) entity;
            if (LegacyCullingMod.CONFIG.dontCullArmorstandNametags
                    && (stand.hasCustomName() || stand.isCustomNameVisible() || stand.shouldShowName())) return true;
            if (LegacyCullingMod.CONFIG.checkArmorstandRules
                    && (stand.isInvisible() || stand.hasCustomName() || stand.shouldShowName())) return true;
        }
        return LegacyCullingMod.CONFIG.dontCullEntityNametags
                && (entity.hasCustomName() || entity.isCustomNameVisible() || entity.shouldRenderName());
    }

    private static boolean centerRayClear(World world, Vec3d camera, Vec3d target) {
        CullingStats.rayTest();
        BlockHitResult hit = world.rayTrace(camera, target, false, true, false);
        if (hit == null || hit.type == BlockHitResult.Type.MISS) return true;
        if (hit.pos == null) return false;
        return camera.distanceTo(hit.pos) + 0.03D >= camera.distanceTo(target);
    }

    private static Vec3d center(Box box) {
        return new Vec3d((box.minX + box.maxX) * 0.5D,
                (box.minY + box.maxY) * 0.5D,
                (box.minZ + box.maxZ) * 0.5D);
    }

    private static double distanceSquared(Vec3d point, Box box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0D), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0D), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0D), point.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }
}
