package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.visibility.CullingStats;
import net.minecraft.client.render.entity.feature.StuckArrowsFeatureRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StuckArrowsFeatureRenderer.class)
public abstract class StuckArrowsFeatureRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void legacyculling$disableAttachedArrows(LivingEntity entity, float limbAngle, float limbDistance,
                                                      float tickDelta, float animationProgress, float headYaw,
                                                      float headPitch, float scale, CallbackInfo ci) {
        if (LegacyCullingMod.CONFIG.enabled && LegacyCullingMod.CONFIG.disableAttachedArrows) {
            CullingStats.disabledRenderer();
            ci.cancel();
        }
    }
}
