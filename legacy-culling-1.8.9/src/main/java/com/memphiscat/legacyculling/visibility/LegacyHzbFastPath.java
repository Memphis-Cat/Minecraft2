package com.memphiscat.legacyculling.visibility;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.compat.OptiFineCompat;
import net.minecraft.util.math.Box;

/**
 * HZB is never allowed to hide an object by itself. The CPU visibility engine
 * first proves that every exposure sample is blocked by trusted full cubes.
 * HZB remains active as a secondary depth confirmation and diagnostic, but an
 * unavailable or stale pyramid can never create a false disappearance.
 */
public final class LegacyHzbFastPath {
    private LegacyHzbFastPath() {
    }

    public static boolean confirmsOcclusion(Box box) {
        if (!LegacyCullingMod.CONFIG.entityHierarchicalZ) return true;
        if (LegacyCullingMod.CONFIG.smartEntityCulling && OptiFineCompat.shadersActive()) return false;
        if (LegacyDepthPyramid.isOccluded(box)) CullingStats.hzbHit();
        return true;
    }
}
