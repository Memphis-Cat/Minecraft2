package com.memphiscat.legacyculling.visibility;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.compat.OptiFineCompat;
import net.minecraft.util.math.Box;
import org.lwjgl.opengl.GL11;

public final class LegacyFrameState {
    private static float fogEnd = Float.POSITIVE_INFINITY;

    private LegacyFrameState() {
    }

    public static void updateFogDistance() {
        fogEnd = Float.POSITIVE_INFINITY;
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.fogCulling) return;
        if (LegacyCullingMod.CONFIG.smartEntityCulling && OptiFineCompat.shadersActive()) return;
        float value = GL11.glGetFloat(GL11.GL_FOG_END);
        if (Float.isFinite(value) && value > 1.0F) fogEnd = value;
    }

    public static boolean isBeyondFog(Box box, double cameraX, double cameraY, double cameraZ) {
        if (!Float.isFinite(fogEnd)) return false;
        double dx = Math.max(Math.max(box.minX - cameraX, 0.0D), cameraX - box.maxX);
        double dy = Math.max(Math.max(box.minY - cameraY, 0.0D), cameraY - box.maxY);
        double dz = Math.max(Math.max(box.minZ - cameraZ, 0.0D), cameraZ - box.maxZ);
        double limit = fogEnd + 2.0D;
        return dx * dx + dy * dy + dz * dz > limit * limit;
    }

    public static int weatherRadius(int vanillaRadius) {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.weatherCulling
                || !LegacyCullingMod.CONFIG.fogCulling || !Float.isFinite(fogEnd)) {
            return vanillaRadius;
        }
        return Math.max(2, Math.min(vanillaRadius, (int) Math.ceil(fogEnd)));
    }

    public static float fogEnd() {
        return fogEnd;
    }
}
