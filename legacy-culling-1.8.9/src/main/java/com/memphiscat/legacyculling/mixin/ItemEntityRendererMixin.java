package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
    @Inject(method = "method_10222", at = @At("HEAD"), cancellable = true)
    private void legacyculling$renderOneGroundItem(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (LegacyCullingMod.CONFIG.enabled && LegacyCullingMod.CONFIG.unstackedItems) {
            cir.setReturnValue(1);
        }
    }
}
