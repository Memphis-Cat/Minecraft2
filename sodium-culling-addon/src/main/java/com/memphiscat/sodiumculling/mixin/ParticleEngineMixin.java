package com.memphiscat.sodiumculling.mixin;

import com.memphiscat.sodiumculling.visibility.ParticleAdmission;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void sodiumculling$limitParticleAdmission(Particle particle, CallbackInfo ci) {
        if (ParticleAdmission.reject(particle)) {
            ci.cancel();
        }
    }
}
