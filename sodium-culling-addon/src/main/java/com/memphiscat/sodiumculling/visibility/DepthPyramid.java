package com.memphiscat.sodiumculling.visibility;

import com.memphiscat.sodiumculling.SodiumCullingClient;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL45C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Conservative previous-frame Hierarchical-Z buffer.
 * Uses two pixel-buffer objects and only maps a buffer after its GPU fence has completed.
 */
public final class DepthPyramid {
    private static final List<float[]> LEVELS = new ArrayList<>();
    private static final List<Integer> WIDTHS = new ArrayList<>();
    private static final List<Integer> HEIGHTS = new ArrayList<>();
    private static final ReadbackSlot[] SLOTS = {new ReadbackSlot(), new ReadbackSlot()};

    private static Matrix4f capturedView = new Matrix4f();
    private static Matrix4f capturedProjection = new Matrix4f();
    private static Vec3 capturedCamera = Vec3.ZERO;
    private static int sourceWidth;
    private static int sourceHeight;
    private static int baseScale = 1;
    private static boolean zeroToOne;
    private static long frameNumber;
    private static long capturedFrame = Long.MIN_VALUE;
    private static int allocatedWidth;
    private static int allocatedHeight;
    private static long allocatedBytes;
    private static boolean unavailable;

    private DepthPyramid() {
    }

    public static void capture(CameraRenderState cameraState) {
        frameNumber++;
        if (unavailable || !SodiumCullingClient.CONFIG.enabled
                || !SodiumCullingClient.CONFIG.hierarchicalZCulling
                || Minecraft.getInstance().level == null || !RenderSystem.isOnRenderThread()) {
            return;
        }

        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTexture depthTexture = target.getDepthTexture();
        if (!(depthTexture instanceof GlTexture glTexture)) {
            unavailable = true;
            SodiumCullingClient.LOGGER.warn("Hierarchical-Z requires the OpenGL backend");
            return;
        }

        int width = target.width;
        int height = target.height;
        if (width <= 0 || height <= 0) {
            return;
        }

        try {
            ensureBuffers(width, height);
            consumeCompletedReadbacks();

            int interval = SodiumCullingClient.CONFIG.hierarchicalZCaptureInterval;
            if (frameNumber % interval != 0L) {
                return;
            }

            ReadbackSlot slot = findFreeSlot();
            if (slot == null) {
                return;
            }

            slot.snapshot = new CameraSnapshot(
                    new Matrix4f(cameraState.viewRotationMatrix),
                    new Matrix4f(cameraState.projectionMatrix),
                    cameraState.pos,
                    RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(),
                    frameNumber,
                    width,
                    height
            );

            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, slot.pbo);
            GL45C.glGetTextureImage(glTexture.glId(), 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT,
                    Math.toIntExact(allocatedBytes), 0L);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            slot.fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        } catch (RuntimeException | LinkageError error) {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            cleanup();
            unavailable = true;
            SodiumCullingClient.LOGGER.warn("Hierarchical-Z asynchronous readback failed", error);
        }
    }

    private static void ensureBuffers(int width, int height) {
        if (width == allocatedWidth && height == allocatedHeight && SLOTS[0].pbo != 0) {
            return;
        }
        cleanup();
        allocatedWidth = width;
        allocatedHeight = height;
        allocatedBytes = (long) width * height * Float.BYTES;
        for (ReadbackSlot slot : SLOTS) {
            slot.pbo = GL45C.glCreateBuffers();
            GL45C.glNamedBufferData(slot.pbo, allocatedBytes, GL15C.GL_STREAM_READ);
        }
    }

    private static void consumeCompletedReadbacks() {
        for (ReadbackSlot slot : SLOTS) {
            if (slot.fence == 0L || slot.snapshot == null) {
                continue;
            }
            int status = GL32C.glClientWaitSync(slot.fence, 0, 0L);
            if (status != GL32C.GL_ALREADY_SIGNALED && status != GL32C.GL_CONDITION_SATISFIED) {
                continue;
            }

            ByteBuffer mapped = GL45C.glMapNamedBufferRange(slot.pbo, 0L, allocatedBytes,
                    GL30C.GL_MAP_READ_BIT);
            if (mapped != null) {
                FloatBuffer depth = mapped.order(ByteOrder.nativeOrder()).asFloatBuffer();
                build(depth, slot.snapshot);
                GL45C.glUnmapNamedBuffer(slot.pbo);
            }
            GL32C.glDeleteSync(slot.fence);
            slot.fence = 0L;
            slot.snapshot = null;
        }
    }

    private static ReadbackSlot findFreeSlot() {
        for (ReadbackSlot slot : SLOTS) {
            if (slot.fence == 0L) {
                return slot;
            }
        }
        return null;
    }

    private static void build(FloatBuffer depth, CameraSnapshot snapshot) {
        LEVELS.clear();
        WIDTHS.clear();
        HEIGHTS.clear();

        int width = snapshot.width;
        int height = snapshot.height;
        sourceWidth = width;
        sourceHeight = height;
        int maxWidth = SodiumCullingClient.CONFIG.hierarchicalZMaxWidth;
        baseScale = Math.max(1, (width + maxWidth - 1) / maxWidth);
        int baseWidth = (width + baseScale - 1) / baseScale;
        int baseHeight = (height + baseScale - 1) / baseScale;
        float[] base = new float[baseWidth * baseHeight];
        Arrays.fill(base, 1.0F);

        for (int y = 0; y < height; y++) {
            int by = y / baseScale;
            int row = y * width;
            int baseRow = by * baseWidth;
            for (int x = 0; x < width; x++) {
                int index = baseRow + x / baseScale;
                float value = depth.get(row + x);
                if (Float.isFinite(value) && value < base[index]) {
                    base[index] = value;
                }
            }
        }

        LEVELS.add(base);
        WIDTHS.add(baseWidth);
        HEIGHTS.add(baseHeight);

        float[] previous = base;
        int previousWidth = baseWidth;
        int previousHeight = baseHeight;
        while (previousWidth > 1 || previousHeight > 1) {
            int nextWidth = Math.max(1, (previousWidth + 1) / 2);
            int nextHeight = Math.max(1, (previousHeight + 1) / 2);
            float[] next = new float[nextWidth * nextHeight];
            Arrays.fill(next, 1.0F);
            for (int y = 0; y < nextHeight; y++) {
                for (int x = 0; x < nextWidth; x++) {
                    float minimum = 1.0F;
                    for (int oy = 0; oy < 2; oy++) {
                        int py = y * 2 + oy;
                        if (py >= previousHeight) continue;
                        for (int ox = 0; ox < 2; ox++) {
                            int px = x * 2 + ox;
                            if (px < previousWidth) {
                                minimum = Math.min(minimum, previous[py * previousWidth + px]);
                            }
                        }
                    }
                    next[y * nextWidth + x] = minimum;
                }
            }
            LEVELS.add(next);
            WIDTHS.add(nextWidth);
            HEIGHTS.add(nextHeight);
            previous = next;
            previousWidth = nextWidth;
            previousHeight = nextHeight;
        }

        capturedView = snapshot.view;
        capturedProjection = snapshot.projection;
        capturedCamera = snapshot.camera;
        zeroToOne = snapshot.zeroToOne;
        capturedFrame = snapshot.frame;
        CullingStats.hzbCapture();
    }

    public static boolean isOccluded(AABB box) {
        if (LEVELS.isEmpty() || capturedFrame == Long.MIN_VALUE
                || frameNumber - capturedFrame > SodiumCullingClient.CONFIG.hierarchicalZCaptureInterval * 3L + 4L) {
            return false;
        }

        float minScreenX = Float.POSITIVE_INFINITY;
        float minScreenY = Float.POSITIVE_INFINITY;
        float maxScreenX = Float.NEGATIVE_INFINITY;
        float maxScreenY = Float.NEGATIVE_INFINITY;
        float nearestDepth = 0.0F;

        for (int mask = 0; mask < 8; mask++) {
            double worldX = (mask & 1) == 0 ? box.minX : box.maxX;
            double worldY = (mask & 2) == 0 ? box.minY : box.maxY;
            double worldZ = (mask & 4) == 0 ? box.minZ : box.maxZ;
            Vector4f clip = new Vector4f(
                    (float) (worldX - capturedCamera.x),
                    (float) (worldY - capturedCamera.y),
                    (float) (worldZ - capturedCamera.z),
                    1.0F
            );
            capturedView.transform(clip);
            capturedProjection.transform(clip);
            if (!Float.isFinite(clip.w) || clip.w <= 0.02F) {
                return false;
            }
            float ndcX = clip.x / clip.w;
            float ndcY = clip.y / clip.w;
            float ndcZ = clip.z / clip.w;
            float depth = zeroToOne ? ndcZ : ndcZ * 0.5F + 0.5F;
            if (!Float.isFinite(depth) || depth < 0.0F || depth > 1.0F) {
                return false;
            }
            nearestDepth = Math.max(nearestDepth, depth);
            float screenX = (ndcX * 0.5F + 0.5F) * sourceWidth;
            float screenY = (ndcY * 0.5F + 0.5F) * sourceHeight;
            minScreenX = Math.min(minScreenX, screenX);
            minScreenY = Math.min(minScreenY, screenY);
            maxScreenX = Math.max(maxScreenX, screenX);
            maxScreenY = Math.max(maxScreenY, screenY);
        }

        if (maxScreenX < 0.0F || maxScreenY < 0.0F || minScreenX >= sourceWidth || minScreenY >= sourceHeight) {
            return false;
        }
        minScreenX = Math.max(0.0F, minScreenX);
        minScreenY = Math.max(0.0F, minScreenY);
        maxScreenX = Math.min(sourceWidth - 1.0F, maxScreenX);
        maxScreenY = Math.min(sourceHeight - 1.0F, maxScreenY);

        float baseMinX = minScreenX / baseScale;
        float baseMinY = minScreenY / baseScale;
        float baseMaxX = maxScreenX / baseScale;
        float baseMaxY = maxScreenY / baseScale;
        float span = Math.max(baseMaxX - baseMinX, baseMaxY - baseMinY);
        int level = 0;
        while (level + 1 < LEVELS.size() && span > 4.0F) {
            span *= 0.5F;
            level++;
        }

        int divisor = 1 << level;
        int levelWidth = WIDTHS.get(level);
        int levelHeight = HEIGHTS.get(level);
        int minX = clamp((int) Math.floor(baseMinX / divisor), 0, levelWidth - 1);
        int minY = clamp((int) Math.floor(baseMinY / divisor), 0, levelHeight - 1);
        int maxX = clamp((int) Math.floor(baseMaxX / divisor), 0, levelWidth - 1);
        int maxY = clamp((int) Math.floor(baseMaxY / divisor), 0, levelHeight - 1);

        float regionMinimum = 1.0F;
        float[] data = LEVELS.get(level);
        for (int y = minY; y <= maxY; y++) {
            int row = y * levelWidth;
            for (int x = minX; x <= maxX; x++) {
                regionMinimum = Math.min(regionMinimum, data[row + x]);
                if (regionMinimum <= nearestDepth + 0.0035F) {
                    return false;
                }
            }
        }
        return regionMinimum > nearestDepth + 0.0035F;
    }

    public static boolean isAvailable() {
        return !unavailable && !LEVELS.isEmpty();
    }

    private static void cleanup() {
        for (ReadbackSlot slot : SLOTS) {
            if (slot.fence != 0L) {
                GL32C.glDeleteSync(slot.fence);
                slot.fence = 0L;
            }
            if (slot.pbo != 0) {
                GL45C.glDeleteBuffers(slot.pbo);
                slot.pbo = 0;
            }
            slot.snapshot = null;
        }
        allocatedWidth = 0;
        allocatedHeight = 0;
        allocatedBytes = 0L;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ReadbackSlot {
        private int pbo;
        private long fence;
        private CameraSnapshot snapshot;
    }

    private record CameraSnapshot(Matrix4f view, Matrix4f projection, Vec3 camera, boolean zeroToOne,
                                  long frame, int width, int height) {
    }
}
