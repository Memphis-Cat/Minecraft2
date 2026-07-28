package com.memphiscat.sodiumculling.visibility;

import com.memphiscat.sodiumculling.SodiumCullingClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Limits particle creation bursts by spatial cell without touching existing particles. */
public final class ParticleAdmission {
    private static final Map<Long, Integer> CELL_COUNTS = new HashMap<>();
    private static long lastTick = Long.MIN_VALUE;

    private ParticleAdmission() {
    }

    public static boolean reject(Particle particle) {
        if (!SodiumCullingClient.CONFIG.enabled || !SodiumCullingClient.CONFIG.particleCulling) {
            return false;
        }

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }

        long tick = level.getGameTime();
        if (tick != lastTick) {
            lastTick = tick;
            CELL_COUNTS.clear();
        }

        Vec3 center = particle.getBoundingBox().getCenter();
        if (VisibilityEngine.camera() != null) {
            double maxDistance = SodiumCullingClient.CONFIG.particleMaxDistance;
            if (VisibilityEngine.camera().position().distanceToSqr(center) > maxDistance * maxDistance) {
                CullingStats.particleAdmission();
                return true;
            }
        }

        int x = ((int) Math.floor(center.x)) >> 2;
        int y = ((int) Math.floor(center.y)) >> 2;
        int z = ((int) Math.floor(center.z)) >> 2;
        long key = ((long) x & 0x1FFFFFL) << 42 | ((long) z & 0x1FFFFFL) << 21 | ((long) y & 0x1FFFFFL);
        int count = CELL_COUNTS.merge(key, 1, Integer::sum);
        boolean rejected = count > SodiumCullingClient.CONFIG.particleCellLimit;
        if (rejected) CullingStats.particleAdmission();
        return rejected;
    }
}
