package com.memphiscat.legacyculling.mixin;

import com.memphiscat.legacyculling.performance.LegacyFarTerrainRenderer;
import com.memphiscat.legacyculling.renderer.NativeRendererCoordinator;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public abstract class ClientWorldFarTerrainMixin {
    @Inject(method = "handleChunk", at = @At("RETURN"))
    private void legacyculling$trackChunk(int chunkX, int chunkZ, boolean loaded, CallbackInfo ci) {
        LegacyFarTerrainRenderer.onChunkState(chunkX, chunkZ, loaded);
        NativeRendererCoordinator.onChunkState(chunkX, chunkZ, loaded);
    }
}
