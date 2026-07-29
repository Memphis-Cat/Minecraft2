package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class PerformanceOverlayMixin extends DrawableHelper {
    @Shadow
    @Final
    private MinecraftClient client;

    @Unique
    private int legacyculling$overlayFrame;
    @Unique
    private String legacyculling$overlayText = "0 FPS | -- ms";

    @Inject(method = "render", at = @At("RETURN"))
    private void legacyculling$drawPerformanceOverlay(float tickDelta, CallbackInfo ci) {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.minimalPerformanceOverlay
                || client.options.hudHidden || client.options.debugEnabled) {
            return;
        }

        if ((legacyculling$overlayFrame++ % 5) == 0) {
            int fps = MinecraftClient.getCurrentFps();
            int ping = legacyculling$getPing();
            legacyculling$overlayText = fps + " FPS | " + (ping < 0 ? "--" : Integer.toString(ping)) + " ms";
        }
        drawWithShadow(client.textRenderer, legacyculling$overlayText, 2, 2, 0xFFFFFF);
    }

    @Unique
    private int legacyculling$getPing() {
        if (client.player == null) return -1;
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null) return -1;
        PlayerListEntry entry = handler.getPlayerListEntry(client.player.getUuid());
        return entry == null ? -1 : Math.max(0, entry.getLatency());
    }
}
