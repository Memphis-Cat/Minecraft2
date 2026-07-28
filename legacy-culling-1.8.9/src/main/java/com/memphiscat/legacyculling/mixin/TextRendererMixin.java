package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.font.BoundedLruMap;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.resource.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Only caches pure text measurements. Caching complete draw calls is unsafe in
 * Minecraft 1.8.9 because the font renderer depends on transient texture,
 * matrix, blend and color state owned by the caller.
 */
@Mixin(TextRenderer.class)
public abstract class TextRendererMixin {
    @Unique
    private final Map<String, Integer> legacyculling$widthCache =
            new BoundedLruMap<String, Integer>(4096);
    @Unique
    private final Map<String, String> legacyculling$trimCache =
            new BoundedLruMap<String, String>(1024);
    @Unique
    private final Map<String, List<String>> legacyculling$wrapCache =
            new BoundedLruMap<String, List<String>>(512);

    @Inject(method = "reload", at = @At("HEAD"))
    private void legacyculling$clearFontCaches(ResourceManager manager, CallbackInfo ci) {
        legacyculling$widthCache.clear();
        legacyculling$trimCache.clear();
        legacyculling$wrapCache.clear();
    }

    @Inject(method = "getStringWidth", at = @At("HEAD"), cancellable = true)
    private void legacyculling$reuseStringWidth(String text, CallbackInfoReturnable<Integer> cir) {
        if (!legacyculling$fontDataCache() || text == null) return;
        Integer width = legacyculling$widthCache.get(text);
        if (width != null) cir.setReturnValue(width);
    }

    @Inject(method = "getStringWidth", at = @At("RETURN"))
    private void legacyculling$cacheStringWidth(String text, CallbackInfoReturnable<Integer> cir) {
        if (legacyculling$fontDataCache() && text != null) {
            legacyculling$widthCache.put(text, cir.getReturnValue());
        }
    }

    @Inject(method = "trimToWidth(Ljava/lang/String;IZ)Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void legacyculling$reuseTrimmedText(String text, int width, boolean backwards,
                                                 CallbackInfoReturnable<String> cir) {
        if (!legacyculling$fontDataCache() || text == null) return;
        String value = legacyculling$trimCache.get(width + ":" + backwards + ":" + text);
        if (value != null) cir.setReturnValue(value);
    }

    @Inject(method = "trimToWidth(Ljava/lang/String;IZ)Ljava/lang/String;", at = @At("RETURN"))
    private void legacyculling$cacheTrimmedText(String text, int width, boolean backwards,
                                                 CallbackInfoReturnable<String> cir) {
        if (legacyculling$fontDataCache() && text != null) {
            legacyculling$trimCache.put(width + ":" + backwards + ":" + text, cir.getReturnValue());
        }
    }

    @Inject(method = "wrapLines", at = @At("HEAD"), cancellable = true)
    private void legacyculling$reuseWrappedText(String text, int width,
                                                 CallbackInfoReturnable<List<String>> cir) {
        if (!legacyculling$fontDataCache() || text == null) return;
        List<String> value = legacyculling$wrapCache.get(width + ":" + text);
        if (value != null) cir.setReturnValue(new ArrayList<String>(value));
    }

    @Inject(method = "wrapLines", at = @At("RETURN"))
    private void legacyculling$cacheWrappedText(String text, int width,
                                                 CallbackInfoReturnable<List<String>> cir) {
        if (legacyculling$fontDataCache() && text != null && cir.getReturnValue() != null) {
            legacyculling$wrapCache.put(width + ":" + text,
                    new ArrayList<String>(cir.getReturnValue()));
        }
    }

    @Unique
    private static boolean legacyculling$fontDataCache() {
        return LegacyCullingMod.CONFIG.enabled
                && (LegacyCullingMod.CONFIG.cacheFontData
                || LegacyCullingMod.CONFIG.optimizedFontRenderer);
    }
}
