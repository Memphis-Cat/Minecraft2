package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.minecraft.client.particle.Particle;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityParticleLightMixin {
    @Unique
    private boolean legacyculling$hasParticleLight;
    @Unique
    private int legacyculling$particleLight;

    @Inject(method = "getLightmapCoordinates", at = @At("HEAD"), cancellable = true)
    private void legacyculling$reuseParticleLight(float tickDelta, CallbackInfoReturnable<Integer> cir) {
        if (LegacyCullingMod.CONFIG.enabled && LegacyCullingMod.CONFIG.staticParticleColor
                && (Object) this instanceof Particle && legacyculling$hasParticleLight) {
            cir.setReturnValue(legacyculling$particleLight);
        }
    }

    @Inject(method = "getLightmapCoordinates", at = @At("RETURN"))
    private void legacyculling$cacheParticleLight(float tickDelta, CallbackInfoReturnable<Integer> cir) {
        if (LegacyCullingMod.CONFIG.enabled && LegacyCullingMod.CONFIG.staticParticleColor
                && (Object) this instanceof Particle && !legacyculling$hasParticleLight) {
            legacyculling$particleLight = cir.getReturnValue();
            legacyculling$hasParticleLight = true;
        }
    }
}
