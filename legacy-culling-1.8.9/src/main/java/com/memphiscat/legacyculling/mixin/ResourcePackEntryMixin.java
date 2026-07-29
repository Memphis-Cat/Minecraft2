package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.minecraft.client.resource.ResourcePackLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

@Mixin(ResourcePackLoader.Entry.class)
public abstract class ResourcePackEntryMixin {
    @Shadow
    private BufferedImage image;

    @Inject(method = "loadIcon", at = @At("RETURN"))
    private void legacyculling$downscalePackIcon(CallbackInfo ci) {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.downscalePackImages || image == null) return;
        if (image.getWidth() <= 64 && image.getHeight() <= 64) return;

        BufferedImage scaled = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            graphics.drawImage(image, 0, 0, 64, 64, null);
        } finally {
            graphics.dispose();
        }
        image.flush();
        image = scaled;
    }
}
