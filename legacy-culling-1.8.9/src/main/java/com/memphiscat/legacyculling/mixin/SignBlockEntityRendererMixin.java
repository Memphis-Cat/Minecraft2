package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.visibility.LegacyVisibilityEngine;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.block.entity.SignBlockEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SignBlockEntityRenderer.class)
public abstract class SignBlockEntityRendererMixin {
    @Unique
    private boolean legacyculling$drawText = true;

    @Inject(method = "render(Lnet/minecraft/block/entity/SignBlockEntity;DDDFI)V", at = @At("HEAD"))
    private void legacyculling$captureTextSide(SignBlockEntity sign, double x, double y, double z,
                                                float tickDelta, int destroyStage, CallbackInfo ci) {
        legacyculling$drawText = LegacyVisibilityEngine.isSignTextVisible(sign);
    }

    @Redirect(method = "render(Lnet/minecraft/block/entity/SignBlockEntity;DDDFI)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;III)I"))
    private int legacyculling$skipBackText(TextRenderer renderer, String text, int x, int y, int color) {
        return legacyculling$drawText ? renderer.draw(text, x, y, color) : 0;
    }

    @Inject(method = "render(Lnet/minecraft/block/entity/SignBlockEntity;DDDFI)V", at = @At("RETURN"))
    private void legacyculling$clearTextSide(SignBlockEntity sign, double x, double y, double z,
                                              float tickDelta, int destroyStage, CallbackInfo ci) {
        legacyculling$drawText = true;
    }
}
