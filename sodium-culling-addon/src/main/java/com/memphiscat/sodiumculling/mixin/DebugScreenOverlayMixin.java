package com.memphiscat.sodiumculling.mixin;

import com.memphiscat.sodiumculling.SodiumCullingClient;
import com.memphiscat.sodiumculling.visibility.CullingStats;
import com.memphiscat.sodiumculling.visibility.DepthPyramid;
import com.memphiscat.sodiumculling.visibility.VisibilityEngine;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayMixin {
    @ModifyArg(method = "extractRenderState", index = 1,
            at = @At(value = "INVOKE", ordinal = 1,
                    target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;extractLines(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;Z)V"))
    private List<String> sodiumculling$appendStatistics(List<String> lines) {
        if (SodiumCullingClient.CONFIG == null || !SodiumCullingClient.CONFIG.showStatistics) {
            return lines;
        }

        CullingStats.Snapshot stats = CullingStats.snapshot();
        lines.add("");
        lines.add(ChatFormatting.AQUA + "[Sodium Culling Addon 0.2.0] "
                + (SodiumCullingClient.CONFIG.enabled ? ChatFormatting.GREEN + "ACTIVE" : ChatFormatting.RED + "DISABLED"));
        lines.add(String.format("Culled E:%d  BE:%d  P:%d (+%d admission)",
                stats.entities(), stats.blockEntities(), stats.particles(), stats.particleAdmission()));
        lines.add(String.format("Weather:%d  Leaf faces:%d  Sign sides:%d",
                stats.weatherColumns(), stats.leafFaces(), stats.signSides()));
        lines.add(String.format("HZB:%s captures:%d hits:%d  Ray tests:%d",
                DepthPyramid.isAvailable() ? "ready" : "warming", stats.hzbCaptures(), stats.hzbHits(), stats.rayTests()));
        lines.add("Iris shader pack: " + (VisibilityEngine.shaderPackActive() ? "active (main-pass culling enabled)" : "not active"));
        return lines;
    }
}
