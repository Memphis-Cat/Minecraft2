package com.memphiscat.legacyculling.performance;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.minecraft.client.MinecraftClient;

/**
 * Smooths chunk rebuild submissions instead of using a one-second hard gate.
 * A small token bucket permits short loading bursts while current FPS controls
 * how quickly the bucket refills.
 */
public final class AdaptiveChunkScheduler {
    private static long lastNanos;
    private static double tokens;

    private AdaptiveChunkScheduler() {
    }

    public static synchronized boolean tryAcquire() {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.limitChunkUpdates) {
            return true;
        }

        long now = System.nanoTime();
        if (lastNanos == 0L) {
            lastNanos = now;
            tokens = LegacyCullingMod.CONFIG.chunkBurstLimit;
        }

        double elapsed = Math.min(0.25D, Math.max(0.0D, (now - lastNanos) / 1_000_000_000.0D));
        lastNanos = now;

        int fps = MinecraftClient.getCurrentFps();
        int target = LegacyCullingMod.CONFIG.chunkTargetFps;
        double multiplier = 1.0D;
        if (LegacyCullingMod.CONFIG.adaptiveChunkLoading) {
            if (fps <= 0) {
                multiplier = 2.0D;
            } else if (fps >= target + 30) {
                multiplier = 2.5D;
            } else if (fps >= target + 10) {
                multiplier = 1.8D;
            } else if (fps >= target) {
                multiplier = 1.25D;
            } else if (fps >= Math.max(20, target * 85 / 100)) {
                multiplier = 0.70D;
            } else {
                multiplier = 0.30D;
            }
        }

        double refillRate = Math.max(1.0D, LegacyCullingMod.CONFIG.chunkUpdateLimit * multiplier);
        tokens = Math.min(LegacyCullingMod.CONFIG.chunkBurstLimit, tokens + elapsed * refillRate);
        if (tokens < 1.0D) return false;
        tokens -= 1.0D;
        return true;
    }

    public static synchronized void reset() {
        lastNanos = 0L;
        tokens = 0.0D;
    }
}
