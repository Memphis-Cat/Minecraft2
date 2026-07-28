package com.memphiscat.sodiumculling.mixin;

import com.memphiscat.sodiumculling.visibility.VisibilityEngine;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(QuadParticleGroup.class)
public abstract class QuadParticleGroupMixin {
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
    private boolean sodiumculling$cullParticlePoint(Frustum frustum, double x, double y, double z) {
        return !VisibilityEngine.shouldCullParticlePoint(x, y, z);
    }
}
