package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererNametagMixin {
    @Redirect(method = "renderLabelIfPresent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/BufferBuilder;color(FFFF)Lnet/minecraft/client/render/BufferBuilder;"))
    private BufferBuilder legacyculling$removeNametagBox(BufferBuilder buffer,
                                                          float red, float green, float blue, float alpha) {
        if (LegacyCullingMod.CONFIG.enabled && LegacyCullingMod.CONFIG.disableNametagBoxes) {
            alpha = 0.0F;
        }
        return buffer.color(red, green, blue, alpha);
    }
}
