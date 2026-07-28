package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.LegacyCullingMod;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteAtlasTexture.class)
public abstract class SpriteAtlasTextureMixin {
    @Unique
    private int legacyculling$animationTick;

    @Inject(method = "update", at = @At("HEAD"))
    private void legacyculling$advanceAnimationClock(CallbackInfo ci) {
        legacyculling$animationTick++;
    }

    @Redirect(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/texture/Sprite;update()V"))
    private void legacyculling$limitAnimationUpdates(Sprite sprite) {
        if (!LegacyCullingMod.CONFIG.enabled || !LegacyCullingMod.CONFIG.lowAnimationTick) {
            sprite.update();
            return;
        }
        int rate = Math.max(20, Math.min(1000, LegacyCullingMod.CONFIG.animationTickRate));
        int period = Math.max(1, 1000 / rate);
        if (legacyculling$animationTick % period == 0) {
            sprite.update();
        }
    }
}
