package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.performance.LegacyFarTerrainRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererFarTerrainMixin {
    @Inject(method = "renderLayer", at = @At("RETURN"))
    private void legacyculling$renderFarTerrain(RenderLayer layer, double tickDelta, int pass,
                                                 Entity camera,
                                                 CallbackInfoReturnable<Integer> cir) {
        if (layer == RenderLayer.SOLID && pass == 0) {
            LegacyFarTerrainRenderer.render(camera, (float) tickDelta);
        }
    }

    @Inject(method = "onRenderRegionUpdate(IIIIII)V", at = @At("RETURN"))
    private void legacyculling$invalidateFarTerrain(int minX, int minY, int minZ,
                                                     int maxX, int maxY, int maxZ,
                                                     CallbackInfo ci) {
        LegacyFarTerrainRenderer.markRegion(minX, minZ, maxX, maxZ);
    }

    @Inject(method = "setWorld", at = @At("HEAD"))
    private void legacyculling$clearFarTerrain(ClientWorld world, CallbackInfo ci) {
        LegacyFarTerrainRenderer.clear();
    }
}
