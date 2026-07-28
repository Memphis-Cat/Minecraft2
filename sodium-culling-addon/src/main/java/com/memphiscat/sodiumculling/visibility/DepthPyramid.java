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
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Conservative previous-frame Hierarchical-Z buffer.
 *
 * <p>26.2 uses reversed depth: clear depth is 0 and larger values are nearer. Each pyramid level
 * stores the minimum depth in its region. A box is considered covered only when every pixel in its
 * projected rectangle contains depth nearer than the box's nearest projected point. The result is
 * never used alone: VisibilityEngine also confirms occlusion with world collision rays.</p>
 */
public final class DepthPyramid {
    private static final List<float[]> LEVELS = new ArrayList<>();
    private static final List<Integer> WIDTHS = new ArrayList<>();
    private static final List<Integer> HEIGHTS = new ArrayList<>();

    private static Matrix4f capturedView = new Matrix4f();
    private static Matrix4f capturedProjection = new Matrix4f();
    private static Vec3 capturedCamera = Vec3.ZERO;
    private static int sourceWidth;
    private static int sourceHeight;
    private static int baseScale = 1;
    private static boolean zeroToOne;
    private static long frameNumber;
    private static long capturedFrame = Long.MIN_VALUE;
    private static boolean permanentlyUnavailable;

    private DepthPyramid() {
    }

    public static void capture(CameraRenderState cameraState) {
        frameNumber++;
        if (permanentlyUnavailable || !SodiumCullingClient.CONFIG.hierarchicalZCulling
                || Minecraft.getInstance().level == null || VisibilityEngine.shaderPackActive()) {
            return;
        }
        int interval = SodiumCullingClient.CONFIG.hierarchicalZCaptureInterval;
        if (frameNumber % interval != 0L) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }

        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTexture depthTexture = target.getDepthTexture();
        if (!(depthTexture instanceof GlTexture glTexture)) {
            permanentlyUnavailable = true;
            SodiumCullingClient.LOGGER.info("Hierarchical-Z disabled: active renderer is not the OpenGL backend");
            return;
        }

        int width = target.width;
        int height = target.height;
        if (width <= 0 || height <= 0) {
            return;
        }

        FloatBuffer depth = MemoryUtil.memAllocFloat(width * height);
        try {
            GL45C.glGetTextureImage(glTexture.glId(), 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, depth);
            depth.rewind();
            build(depth, width, height, cameraState);
        } catch (RuntimeException | LinkageError error) {
            permanentlyUnavailable = true;
            LEVELS.clear();
            WIDTHS.clear();
            HEIGHTS.clear();
            SodiumCullingClient.LOGGER.warn("Hierarchical-Z depth capture failed; disabling it safely", error);
        } finally {
            MemoryUtil.memFree(depth);
        }
    }

    private static void build(FloatBuffer depth, int width, int height, CameraRenderState cameraState) {
        LEVELS.clear();
        WIDTHS.clear();
        HEIGHTS.clear();

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

        capturedView = new Matrix4f(cameraState.viewRotationMatrix);
        capturedProjection = new Matrix4f(cameraState.projectionMatrix);
        capturedCamera = cameraState.pos;
        zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        capturedFrame = frameNumber;
    }

    /** Returns true only for a high-confidence full-rectangle depth occlusion. */
    public static boolean isOccluded(AABB box) {
        if (LEVELS.isEmpty() || capturedFrame == Long.MIN_VALUE
                || frameNumber - capturedFrame > SodiumCullingClient.CONFIG.hierarchicalZCaptureInterval + 2L) {
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
