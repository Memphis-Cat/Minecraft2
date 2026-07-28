package com.memphiscat.legacyculling.visibility;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.compat.OptiFineCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GLSync;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Conservative previous-frame HZB for the legacy OpenGL renderer. */
public final class LegacyDepthPyramid {
    private static final Slot[] SLOTS = {new Slot(), new Slot()};
    private static final List<float[]> LEVELS = new ArrayList<float[]>();
    private static final List<Integer> WIDTHS = new ArrayList<Integer>();
    private static final List<Integer> HEIGHTS = new ArrayList<Integer>();
    private static final FloatBuffer MATRIX_BUFFER = BufferUtils.createFloatBuffer(16);

    private static int allocatedWidth;
    private static int allocatedHeight;
    private static long allocatedBytes;
    private static int sourceWidth;
    private static int sourceHeight;
    private static int baseScale = 1;
    private static float[] capturedMvp = identity();
    private static double capturedCameraX;
    private static double capturedCameraY;
    private static double capturedCameraZ;
    private static float capturedYaw;
    private static float capturedPitch;
    private static long frame;
    private static long capturedFrame = Long.MIN_VALUE;
    private static boolean unavailable;

    private LegacyDepthPyramid() {
    }

    public static void capture() {
        frame++;
        if (unavailable || !LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.entityHierarchicalZ) return;
        if (LegacyCullingMod.CONFIG.shaderShadowSafety && OptiFineCompat.shadersActive()) return;
        if (!GLContext.getCapabilities().OpenGL15 || !GLContext.getCapabilities().OpenGL32) {
            unavailable = true;
            LegacyCullingMod.LOGGER.warn("Legacy HZB requires OpenGL 3.2 sync support");
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer framebuffer = client.getFramebuffer();
        Entity camera = client.getCameraEntity();
        if (framebuffer == null || camera == null || !framebuffer.useDepthAttachment) return;
        int width = framebuffer.viewportWidth;
        int height = framebuffer.viewportHeight;
        if (width <= 0 || height <= 0) return;

        try {
            ensureBuffers(width, height);
            consumeCompleted();
            int interval = Math.max(2, LegacyCullingMod.CONFIG.hierarchicalZCaptureInterval);
            if (frame % interval != 0L) return;

            Slot slot = freeSlot();
            if (slot == null) return;
            slot.snapshot = new Snapshot(captureMvp(), camera.x, camera.y, camera.z,
                    camera.yaw, camera.pitch, frame, width, height);

            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 0L);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        } catch (RuntimeException exception) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            cleanup();
            unavailable = true;
            LegacyCullingMod.LOGGER.warn("Legacy HZB capture failed and was disabled", exception);
        }
    }

    public static boolean isOccluded(Box box) {
        if (LEVELS.isEmpty() || capturedFrame == Long.MIN_VALUE) return false;
        int interval = Math.max(2, LegacyCullingMod.CONFIG.hierarchicalZCaptureInterval);
        if (frame - capturedFrame > interval * 3L + 4L) return false;

        Entity camera = MinecraftClient.getInstance().getCameraEntity();
        if (camera == null) return false;
        double movementSq = square(camera.x - capturedCameraX) + square(camera.y - capturedCameraY)
                + square(camera.z - capturedCameraZ);
        if (movementSq > 0.0625D || angleDifference(camera.yaw, capturedYaw) > 2.0F
                || Math.abs(camera.pitch - capturedPitch) > 2.0F) return false;

        float minScreenX = Float.POSITIVE_INFINITY;
        float minScreenY = Float.POSITIVE_INFINITY;
        float maxScreenX = Float.NEGATIVE_INFINITY;
        float maxScreenY = Float.NEGATIVE_INFINITY;
        float nearestDepth = 1.0F;

        for (int mask = 0; mask < 8; mask++) {
            float worldX = (float) ((mask & 1) == 0 ? box.minX : box.maxX);
            float worldY = (float) ((mask & 2) == 0 ? box.minY : box.maxY);
            float worldZ = (float) ((mask & 4) == 0 ? box.minZ : box.maxZ);
            float[] clip = transform(capturedMvp, worldX, worldY, worldZ, 1.0F);
            if (!Float.isFinite(clip[3]) || clip[3] <= 0.02F) return false;
            float ndcX = clip[0] / clip[3];
            float ndcY = clip[1] / clip[3];
            float depth = clip[2] / clip[3] * 0.5F + 0.5F;
            if (!Float.isFinite(depth) || depth < 0.0F || depth > 1.0F) return false;
            nearestDepth = Math.min(nearestDepth, depth);
            float screenX = (ndcX * 0.5F + 0.5F) * sourceWidth;
            float screenY = (ndcY * 0.5F + 0.5F) * sourceHeight;
            minScreenX = Math.min(minScreenX, screenX);
            minScreenY = Math.min(minScreenY, screenY);
            maxScreenX = Math.max(maxScreenX, screenX);
            maxScreenY = Math.max(maxScreenY, screenY);
        }

        if (maxScreenX < 0.0F || maxScreenY < 0.0F
                || minScreenX >= sourceWidth || minScreenY >= sourceHeight) return false;
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
        int width = WIDTHS.get(level);
        int height = HEIGHTS.get(level);
        int x0 = clamp((int) Math.floor(baseMinX / divisor), 0, width - 1);
        int y0 = clamp((int) Math.floor(baseMinY / divisor), 0, height - 1);
        int x1 = clamp((int) Math.floor(baseMaxX / divisor), 0, width - 1);
        int y1 = clamp((int) Math.floor(baseMaxY / divisor), 0, height - 1);
        float[] data = LEVELS.get(level);
        float epsilon = 0.0035F;
        for (int y = y0; y <= y1; y++) {
            int row = y * width;
            for (int x = x0; x <= x1; x++) {
                if (data[row + x] >= nearestDepth - epsilon) return false;
            }
        }
        CullingStats.hzbHit();
        return true;
    }

    public static boolean isAvailable() {
        return !unavailable && !LEVELS.isEmpty();
    }

    private static void ensureBuffers(int width, int height) {
        if (width == allocatedWidth && height == allocatedHeight && SLOTS[0].pbo != 0) return;
        cleanup();
        allocatedWidth = width;
        allocatedHeight = height;
        allocatedBytes = (long) width * height * Float.BYTES;
        for (Slot slot : SLOTS) {
            slot.pbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, allocatedBytes, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    private static void consumeCompleted() {
        for (Slot slot : SLOTS) {
            if (slot.fence == null || slot.snapshot == null) continue;
            int status = GL32.glClientWaitSync(slot.fence, 0, 0L);
            if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) continue;

            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
            ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY,
                    allocatedBytes, null);
            if (mapped != null) {
                build(mapped.order(ByteOrder.nativeOrder()).asFloatBuffer(), slot.snapshot);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL32.glDeleteSync(slot.fence);
            slot.fence = null;
            slot.snapshot = null;
        }
    }

    private static void build(FloatBuffer depth, Snapshot snapshot) {
        LEVELS.clear();
        WIDTHS.clear();
        HEIGHTS.clear();
        sourceWidth = snapshot.width;
        sourceHeight = snapshot.height;
        int maxWidth = Math.max(128, LegacyCullingMod.CONFIG.hierarchicalZMaxWidth);
        baseScale = Math.max(1, (sourceWidth + maxWidth - 1) / maxWidth);
        int width = (sourceWidth + baseScale - 1) / baseScale;
        int height = (sourceHeight + baseScale - 1) / baseScale;
        float[] base = new float[width * height];

        for (int y = 0; y < sourceHeight; y++) {
            int sourceRow = y * sourceWidth;
            int targetRow = (y / baseScale) * width;
            for (int x = 0; x < sourceWidth; x++) {
                float value = depth.get(sourceRow + x);
                int target = targetRow + x / baseScale;
                if (Float.isFinite(value) && value >= 0.0F && value <= 1.0F && value > base[target]) {
                    base[target] = value;
                }
            }
        }
        LEVELS.add(base);
        WIDTHS.add(width);
        HEIGHTS.add(height);

        float[] previous = base;
        int previousWidth = width;
        int previousHeight = height;
        while (previousWidth > 1 || previousHeight > 1) {
            int nextWidth = Math.max(1, (previousWidth + 1) / 2);
            int nextHeight = Math.max(1, (previousHeight + 1) / 2);
            float[] next = new float[nextWidth * nextHeight];
            for (int y = 0; y < nextHeight; y++) {
                for (int x = 0; x < nextWidth; x++) {
                    float maximum = 0.0F;
                    for (int oy = 0; oy < 2; oy++) {
                        int py = y * 2 + oy;
                        if (py >= previousHeight) continue;
                        for (int ox = 0; ox < 2; ox++) {
                            int px = x * 2 + ox;
                            if (px < previousWidth) maximum = Math.max(maximum, previous[py * previousWidth + px]);
                        }
                    }
                    next[y * nextWidth + x] = maximum;
                }
            }
            LEVELS.add(next);
            WIDTHS.add(nextWidth);
            HEIGHTS.add(nextHeight);
            previous = next;
            previousWidth = nextWidth;
            previousHeight = nextHeight;
        }

        capturedMvp = snapshot.mvp;
        capturedCameraX = snapshot.cameraX;
        capturedCameraY = snapshot.cameraY;
        capturedCameraZ = snapshot.cameraZ;
        capturedYaw = snapshot.yaw;
        capturedPitch = snapshot.pitch;
        capturedFrame = snapshot.frame;
        CullingStats.hzbCapture();
    }

    private static Slot freeSlot() {
        for (Slot slot : SLOTS) if (slot.fence == null) return slot;
        return null;
    }

    private static float[] captureMvp() {
        float[] model = new float[16];
        float[] projection = new float[16];
        MATRIX_BUFFER.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX_BUFFER);
        MATRIX_BUFFER.rewind();
        MATRIX_BUFFER.get(model);
        MATRIX_BUFFER.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, MATRIX_BUFFER);
        MATRIX_BUFFER.rewind();
        MATRIX_BUFFER.get(projection);
        return multiply(projection, model);
    }

    private static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0F;
                for (int k = 0; k < 4; k++) sum += left[k * 4 + row] * right[column * 4 + k];
                result[column * 4 + row] = sum;
            }
        }
        return result;
    }

    private static float[] transform(float[] matrix, float x, float y, float z, float w) {
        return new float[] {
                matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12] * w,
                matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13] * w,
                matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14] * w,
                matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15] * w
        };
    }

    private static void cleanup() {
        for (Slot slot : SLOTS) {
            if (slot.fence != null) GL32.glDeleteSync(slot.fence);
            if (slot.pbo != 0) GL15.glDeleteBuffers(slot.pbo);
            slot.fence = null;
            slot.pbo = 0;
            slot.snapshot = null;
        }
        allocatedWidth = 0;
        allocatedHeight = 0;
        allocatedBytes = 0L;
        LEVELS.clear();
        WIDTHS.clear();
        HEIGHTS.clear();
    }

    private static float[] identity() {
        float[] matrix = new float[16];
        Arrays.fill(matrix, 0.0F);
        matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1.0F;
        return matrix;
    }

    private static float angleDifference(float a, float b) {
        float difference = Math.abs(a - b) % 360.0F;
        return difference > 180.0F ? 360.0F - difference : difference;
    }

    private static double square(double value) {
        return value * value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Slot {
        int pbo;
        GLSync fence;
        Snapshot snapshot;
    }

    private static final class Snapshot {
        final float[] mvp;
        final double cameraX;
        final double cameraY;
        final double cameraZ;
        final float yaw;
        final float pitch;
        final long frame;
        final int width;
        final int height;

        Snapshot(float[] mvp, double cameraX, double cameraY, double cameraZ,
                 float yaw, float pitch, long frame, int width, int height) {
            this.mvp = mvp;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
            this.yaw = yaw;
            this.pitch = pitch;
            this.frame = frame;
            this.width = width;
            this.height = height;
        }
    }
}
