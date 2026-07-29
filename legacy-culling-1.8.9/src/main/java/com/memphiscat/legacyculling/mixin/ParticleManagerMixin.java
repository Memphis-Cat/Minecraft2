package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.renderer.NativeRendererCoordinator;
import com.memphiscat.legacyculling.visibility.CullingStats;
import com.memphiscat.legacyculling.visibility.LegacyVisibilityEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
    @Shadow
    private List<Particle>[][] particles;

    @Unique
    private final Map<Long, Integer> legacyculling$spawnCells = new HashMap<Long, Integer>();

    @Inject(method = "tick", at = @At("HEAD"))
    private void legacyculling$resetParticleAdmission(CallbackInfo ci) {
        legacyculling$spawnCells.clear();
    }

    @Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void legacyculling$limitParticleSpawn(Particle particle, CallbackInfo ci) {
        if (!LegacyCullingMod.CONFIG.enabled) return;

        if (LegacyCullingMod.CONFIG.maxParticleLimit && legacyculling$totalParticles() >= LegacyCullingMod.CONFIG.maxParticles) {
            CullingStats.particleLimit();
            ci.cancel();
            return;
        }

        Entity cameraEntity = MinecraftClient.getInstance().getCameraEntity();
        if (LegacyCullingMod.CONFIG.particleCulling && cameraEntity != null) {
            Vec3d camera = cameraEntity.getCameraPosVec(1.0F);
            double dx = particle.x - camera.x;
            double dy = particle.y - camera.y;
            double dz = particle.z - camera.z;
            double distance = LegacyCullingMod.CONFIG.particleMaxDistance;
            if (dx * dx + dy * dy + dz * dz > distance * distance) {
                CullingStats.particle();
                ci.cancel();
                return;
            }
        }

        if (LegacyCullingMod.CONFIG.particleDensity) {
            long cell = legacyculling$cellKey(particle.x, particle.y, particle.z);
            Integer count = legacyculling$spawnCells.get(cell);
            int next = count == null ? 1 : count + 1;
            if (next > LegacyCullingMod.CONFIG.particleCellLimit) {
                CullingStats.particleLimit();
                ci.cancel();
                return;
            }
            legacyculling$spawnCells.put(cell, next);
        }
    }

    @Redirect(method = "renderParticles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;draw(Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V"))
    private void legacyculling$cullParticleDraw(Particle particle, BufferBuilder buffer, Entity camera,
                                                 float tickDelta, float rotationX, float rotationXZ,
                                                 float rotationZ, float rotationYZ, float rotationXY) {
        if (legacyculling$shouldRenderParticle(particle)) {
            particle.draw(buffer, camera, tickDelta, rotationX, rotationXZ, rotationZ, rotationYZ, rotationXY);
        }
    }

    @Redirect(method = "method_1299", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;draw(Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V"))
    private void legacyculling$cullLitParticleDraw(Particle particle, BufferBuilder buffer, Entity camera,
                                                    float tickDelta, float rotationX, float rotationXZ,
                                                    float rotationZ, float rotationYZ, float rotationXY) {
        if (legacyculling$shouldRenderParticle(particle)) {
            particle.draw(buffer, camera, tickDelta, rotationX, rotationXZ, rotationZ, rotationYZ, rotationXY);
        }
    }

    @Unique
    private static boolean legacyculling$shouldRenderParticle(Particle particle) {
        Box box = new Box(particle.x - 0.2D, particle.y - 0.2D, particle.z - 0.2D,
                particle.x + 0.2D, particle.y + 0.2D, particle.z + 0.2D);
        return NativeRendererCoordinator.definitelyReachable(box)
                || !LegacyVisibilityEngine.shouldCullParticle(particle.x, particle.y, particle.z);
    }

    @Unique
    private int legacyculling$totalParticles() {
        int total = 0;
        for (List<Particle>[] layer : particles) {
            for (List<Particle> list : layer) total += list.size();
        }
        return total;
    }

    @Unique
    private static long legacyculling$cellKey(double x, double y, double z) {
        int cx = legacyculling$floor(x) >> 2;
        int cy = legacyculling$floor(y) >> 2;
        int cz = legacyculling$floor(z) >> 2;
        return ((long) (cx & 0x3FFFFFF) << 38) | ((long) (cz & 0x3FFFFFF) << 12) | (cy & 0xFFFL);
    }

    @Unique
    private static int legacyculling$floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }
}
