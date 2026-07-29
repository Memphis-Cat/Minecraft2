package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.compat.OptiFineCompat;
import com.memphiscat.legacyculling.visibility.CullingStats;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DebugHud.class)
public abstract class DebugHudMixin {
    @Inject(method = "getRightText", at = @At("RETURN"))
    private void legacyculling$appendStatistics(CallbackInfoReturnable<List<String>> cir) {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.showStatistics) return;
        CullingStats.Snapshot stats = CullingStats.snapshot();
        List<String> lines = cir.getReturnValue();
        lines.add("");
        lines.add("[Legacy Culling 0.3.0] ACTIVE");
        lines.add("Culled E:" + stats.entities + " BE:" + stats.blockEntities + " P:" + stats.particles);
        lines.add("Limits P:" + stats.particleLimit + " Leaf:" + stats.leafFaces + " Sign:" + stats.signText);
        lines.add("Ray tests:" + stats.rayTests + " HZB:" + stats.hzbHits + "/" + stats.hzbCaptures);
        lines.add("Hide delay:" + LegacyCullingMod.CONFIG.occlusionHideFrames + " frames");
        lines.add("Chunk submissions:" + stats.chunkUpdates);
        if (OptiFineCompat.shadersActive()) {
            lines.add("OptiFine shaders: active (smart entity occlusion paused)");
        }
    }
}
