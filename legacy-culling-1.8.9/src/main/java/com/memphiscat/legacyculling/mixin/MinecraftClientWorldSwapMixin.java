package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LoadingScreenRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientWorldSwapMixin {
    @Redirect(method = "connect(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/System;gc()V"))
    private void legacyculling$skipForcedWorldSwapGc() {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.optimizedWorldSwapping) {
            System.gc();
        }
    }

    @Redirect(method = "connect(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/LoadingScreenRenderer;setTitleAndTask(Ljava/lang/String;)V"))
    private void legacyculling$skipWorldSwapTitle(LoadingScreenRenderer renderer, String text) {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.optimizedWorldSwapping) {
            renderer.setTitleAndTask(text);
        }
    }

    @Redirect(method = "connect(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/LoadingScreenRenderer;setTask(Ljava/lang/String;)V"))
    private void legacyculling$skipWorldSwapTask(LoadingScreenRenderer renderer, String text) {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.optimizedWorldSwapping) {
            renderer.setTask(text);
        }
    }
}
