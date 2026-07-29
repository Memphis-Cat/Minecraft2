package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Inject(method = "renderGlint", at = @At("HEAD"), cancellable = true)
    private void legacyculling$disableEnchantmentGlint(BakedModel model, CallbackInfo ci) {
        if (LegacyCullingMod.CONFIG.enabled && LegacyCullingMod.CONFIG.disableEnchantmentGlint) {
            ci.cancel();
        }
    }
}
