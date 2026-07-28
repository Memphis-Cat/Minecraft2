package com.memphiscat.sodiumculling.mixin;

import com.memphiscat.sodiumculling.visibility.DepthPyramid;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererDepthPyramidMixin {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.AFTER))
    private void sodiumculling$captureDepthPyramid(DeltaTracker deltaTracker, boolean advanceGameTime,
                                                   CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) {
            DepthPyramid.capture(Minecraft.getInstance().gameRenderer.gameRenderState()
                    .levelRenderState.cameraRenderState);
        }
    }
}
