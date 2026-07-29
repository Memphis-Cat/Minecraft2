package com.memphiscat.legacyculling.renderer;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.compat.OptiFineCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/** Shared stage-one coordinator for compatibility and native renderer paths. */
public final class NativeRendererCoordinator {
    public static final int BACKEND_COMPATIBILITY = 0;
    public static final int BACKEND_NATIVE_EXPERIMENTAL = 1;

    private static final SectionVisibilityHierarchy HIERARCHY = new SectionVisibilityHierarchy();
    private static World world;
    private static boolean failed;
    private static String failureReason = "";

    private NativeRendererCoordinator() {
    }

    public static void beginFrame() {
        if (!nativeEnabled()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        Entity camera = client.getCameraEntity();
        if (client.world == null || camera == null) return;
        try {
            world = client.world;
            HIERARCHY.beginFrame(client.world, camera.x, camera.y, camera.z,
                    Math.max(2, client.options.viewDistance),
                    LegacyCullingMod.CONFIG.sectionGraphBuildsPerFrame);
        } catch (RuntimeException exception) {
            fail("section hierarchy", exception);
        }
    }

    public static SectionVisibility visibility(Box box) {
        if (!nativeEnabled() || box == null) return SectionVisibility.UNKNOWN;
        try {
            return HIERARCHY.visibility(box);
        } catch (RuntimeException exception) {
            fail("visibility query", exception);
            return SectionVisibility.UNKNOWN;
        }
    }

    public static boolean definitelyReachable(Box box) {
        return visibility(box) == SectionVisibility.VISIBLE;
    }

    public static boolean definitelyBlocked(Box box) {
        return visibility(box) == SectionVisibility.BLOCKED;
    }

    public static void onChunkState(int chunkX, int chunkZ, boolean loaded) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        try {
            HIERARCHY.onChunkState(client.world, chunkX, chunkZ, loaded);
        } catch (RuntimeException exception) {
            fail("chunk invalidation", exception);
        }
    }

    public static void invalidateRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        try {
            HIERARCHY.invalidateRegion(client.world, minX, minY, minZ, maxX, maxY, maxZ);
        } catch (RuntimeException exception) {
            fail("region invalidation", exception);
        }
    }

    public static void reset() {
        world = null;
        failed = false;
        failureReason = "";
        HIERARCHY.reset();
    }

    public static boolean nativeEnabled() {
        if (!LegacyCullingMod.CONFIG.enabled || failed
                || LegacyCullingMod.CONFIG.rendererBackend != BACKEND_NATIVE_EXPERIMENTAL) return false;
        if (LegacyCullingMod.CONFIG.smartEntityCulling && OptiFineCompat.shadersActive()) return false;
        return true;
    }

    public static boolean failed() {
        return failed;
    }

    public static String failureReason() {
        return failureReason;
    }

    private static void fail(String stage, RuntimeException exception) {
        failed = true;
        failureReason = stage + ": " + exception.getClass().getSimpleName();
        LegacyCullingMod.LOGGER.error("Native renderer stage one failed during {}; falling back to compatibility", stage, exception);
        HIERARCHY.reset();
    }
}
