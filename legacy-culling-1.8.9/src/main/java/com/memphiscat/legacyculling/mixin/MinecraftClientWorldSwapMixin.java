package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.performance.LegacyFarTerrainRenderer;
import com.memphiscat.legacyculling.visibility.LegacyFrameState;
import com.memphiscat.legacyculling.visibility.LegacyVisibilityEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LoadingScreenRenderer;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientWorldSwapMixin {
    @Inject(method = "connect(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V", at = @At("HEAD"))
    private void legacyculling$resetTransientVisibility(ClientWorld world, String message, CallbackInfo ci) {
        LegacyVisibilityEngine.reset();
        LegacyFrameState.reset();
        LegacyFarTerrainRenderer.clear();
    }

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
