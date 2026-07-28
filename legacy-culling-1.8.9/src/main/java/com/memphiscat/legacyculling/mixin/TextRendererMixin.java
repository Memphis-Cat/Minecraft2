package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import com.memphiscat.legacyculling.font.BoundedLruMap;
import com.memphiscat.legacyculling.font.FontDisplayListEntry;
import com.memphiscat.legacyculling.font.FontRenderKey;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.resource.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mixin(TextRenderer.class)
public abstract class TextRendererMixin {
    @Unique
    private final Map<String, Integer> legacyculling$widthCache = new BoundedLruMap<String, Integer>(4096);
    @Unique
    private final Map<String, String> legacyculling$trimCache = new BoundedLruMap<String, String>(1024);
    @Unique
    private final Map<String, List<String>> legacyculling$wrapCache =
            new BoundedLruMap<String, List<String>>(512);
    @Unique
    private final LinkedHashMap<FontRenderKey, FontDisplayListEntry> legacyculling$renderCache =
            new LinkedHashMap<FontRenderKey, FontDisplayListEntry>(256, 0.75F, true);
    @Unique
    private FontRenderKey legacyculling$compilingKey;
    @Unique
    private int legacyculling$compilingList;

    @Inject(method = "reload", at = @At("HEAD"))
    private void legacyculling$clearFontCaches(ResourceManager manager, CallbackInfo ci) {
        legacyculling$widthCache.clear();
        legacyculling$trimCache.clear();
        legacyculling$wrapCache.clear();
        legacyculling$deleteRenderCache();
    }

    @Inject(method = "draw(Ljava/lang/String;FFIZ)I", at = @At("HEAD"), cancellable = true)
    private void legacyculling$reuseRenderedString(String text, float x, float y, int color, boolean shadow,
                                                    CallbackInfoReturnable<Integer> cir) {
        if (!legacyculling$optimizedRenderer() || text == null || text.length() > 512
                || legacyculling$compilingList != 0) return;
        FontRenderKey key = new FontRenderKey(text, Float.floatToIntBits(x), Float.floatToIntBits(y), color, shadow);
        FontDisplayListEntry cached = legacyculling$renderCache.get(key);
        if (cached != null) {
            GL11.glCallList(cached.listId);
            cir.setReturnValue(cached.returnValue);
            return;
        }
        int list = GL11.glGenLists(1);
        if (list == 0) return;
        legacyculling$compilingKey = key;
        legacyculling$compilingList = list;
        GL11.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
    }

    @Inject(method = "draw(Ljava/lang/String;FFIZ)I", at = @At("RETURN"))
    private void legacyculling$finishRenderedString(String text, float x, float y, int color, boolean shadow,
                                                     CallbackInfoReturnable<Integer> cir) {
        if (legacyculling$compilingList == 0 || legacyculling$compilingKey == null) return;
        GL11.glEndList();
        legacyculling$renderCache.put(legacyculling$compilingKey,
                new FontDisplayListEntry(legacyculling$compilingList, cir.getReturnValue()));
        legacyculling$compilingKey = null;
        legacyculling$compilingList = 0;
        legacyculling$trimRenderCache();
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
            legacyculling$wrapCache.put(width + ":" + text, new ArrayList<String>(cir.getReturnValue()));
        }
    }

    @Unique
    private void legacyculling$trimRenderCache() {
        while (legacyculling$renderCache.size() > 512) {
            Iterator<Map.Entry<FontRenderKey, FontDisplayListEntry>> iterator =
                    legacyculling$renderCache.entrySet().iterator();
            Map.Entry<FontRenderKey, FontDisplayListEntry> entry = iterator.next();
            GL11.glDeleteLists(entry.getValue().listId, 1);
            iterator.remove();
        }
    }

    @Unique
    private void legacyculling$deleteRenderCache() {
        for (FontDisplayListEntry entry : legacyculling$renderCache.values()) {
            GL11.glDeleteLists(entry.listId, 1);
        }
        legacyculling$renderCache.clear();
        if (legacyculling$compilingList != 0) {
            GL11.glEndList();
            GL11.glDeleteLists(legacyculling$compilingList, 1);
            legacyculling$compilingList = 0;
            legacyculling$compilingKey = null;
        }
    }

    @Unique
    private static boolean legacyculling$optimizedRenderer() {
        return LegacyCullingMod.CONFIG.enabled && LegacyCullingMod.CONFIG.optimizedFontRenderer;
    }

    @Unique
    private static boolean legacyculling$fontDataCache() {
        return LegacyCullingMod.CONFIG.enabled && LegacyCullingMod.CONFIG.cacheFontData;
    }
}
