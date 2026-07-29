package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.renderer.NativeRegionTerrainRenderer;
import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.world.VboChunkRenderManager;
import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(VboChunkRenderManager.class)
public abstract class VboChunkRenderManagerMixin {
    @Inject(method = "render(Lnet/minecraft/client/render/RenderLayer;)V", at = @At("HEAD"), cancellable = true)
    private void legacyculling$renderSolidRegions(RenderLayer layer, CallbackInfo ci) {
        if (layer != RenderLayer.SOLID) return;

        AbstractChunkRenderManagerAccessor manager = (AbstractChunkRenderManagerAccessor) (Object) this;
        if (!manager.legacyculling$isActive()) return;

        List<BuiltChunk> helpers = manager.legacyculling$getHelpers();
        if (!NativeRegionTerrainRenderer.tryRenderSolid(
                helpers,
                manager.legacyculling$getViewX(),
                manager.legacyculling$getViewY(),
                manager.legacyculling$getViewZ())) return;

        GLX.gl15BindBuffer(GLX.arrayBuffer, 0);
        GlStateManager.clearColor();
        helpers.clear();
        ci.cancel();
    }
}
