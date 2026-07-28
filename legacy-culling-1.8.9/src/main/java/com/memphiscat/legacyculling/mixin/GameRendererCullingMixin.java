package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.visibility.LegacyDepthPyramid;
import com.memphiscat.legacyculling.visibility.LegacyFrameState;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererCullingMixin {
    @Inject(method = "renderWorld(IFJ)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;updateFog(F)V",
                    shift = At.Shift.AFTER))
    private void legacyculling$captureFog(int pass, float tickDelta, long finishTimeNano, CallbackInfo ci) {
        LegacyFrameState.updateFogDistance();
    }

    @Inject(method = "renderWorld(IFJ)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;clear(I)V",
                    ordinal = 1,
                    shift = At.Shift.BEFORE))
    private void legacyculling$captureDepthBeforeHand(int pass, float tickDelta, long finishTimeNano,
                                                       CallbackInfo ci) {
        if (pass != 0) LegacyDepthPyramid.capture();
    }

    @ModifyConstant(method = "renderWeather", constant = @Constant(intValue = 10))
    private int legacyculling$limitFancyWeatherRadius(int original) {
        return LegacyFrameState.weatherRadius(original);
    }

    @ModifyConstant(method = "renderWeather", constant = @Constant(intValue = 5))
    private int legacyculling$limitFastWeatherRadius(int original) {
        return LegacyFrameState.weatherRadius(original);
    }
}
